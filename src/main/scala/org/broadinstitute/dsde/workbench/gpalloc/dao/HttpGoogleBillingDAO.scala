package org.broadinstitute.dsde.workbench.gpalloc.dao

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpResponseException
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.cloudbilling.Cloudbilling
import com.google.api.services.cloudbilling.model.ProjectBillingInfo
import com.google.api.services.cloudresourcemanager.CloudResourceManager
import com.google.api.services.cloudresourcemanager.model.{Binding, Project, SetIamPolicyRequest, Operation => CRMOperation}
import com.google.api.services.compute.model.UsageExportLocation
import com.google.api.services.compute.{Compute, ComputeScopes}
import com.google.api.services.iam.v1.Iam
import com.google.api.services.serviceusage.v1beta1.ServiceUsage
import com.google.api.services.serviceusage.v1beta1.model.{BatchEnableServicesRequest, Operation => SUOperation}
import com.google.api.services.storage.model.Bucket.Lifecycle
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule.{Action, Condition}
import com.google.api.services.storage.model.{Bucket, BucketAccessControl, ObjectAccessControl}
import com.google.api.services.storage.Storage
import io.grpc.Status.Code
import org.broadinstitute.dsde.workbench.google.GoogleUtilities
import org.broadinstitute.dsde.workbench.gpalloc.config.GPAllocConfig
import org.broadinstitute.dsde.workbench.gpalloc.db.ActiveOperationRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.broadinstitute.dsde.workbench.gpalloc.model.{AssignedProject, GPAllocException}
import org.broadinstitute.dsde.workbench.gpalloc.util.{Sequentially, Throttler}
import org.broadinstitute.dsde.workbench.metrics.GoogleInstrumentedService
import org.broadinstitute.dsde.workbench.model.ErrorReportSource

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class HttpGoogleBillingDAO(appName: String,
                           serviceAccountPemFile: String,
                           billingPemEmail: String,
                           billingEmail: String,
                           gpAllocConfig: GPAllocConfig)
                           (implicit val system: ActorSystem, val executionContext: ExecutionContext)
  extends GoogleDAO with GoogleUtilities with Sequentially {

  protected val workbenchMetricBaseName = "billing"
  implicit val service = GoogleInstrumentedService.Iam

  implicit val counters = googleCounters(service)
  implicit val histo = googleRetryHistogram(service)

  implicit val errorReportSource = ErrorReportSource("gpalloc-google")

  lazy val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  lazy val jsonFactory = JacksonFactory.getDefaultInstance

  //Google throttles other project service management requests (like operation polls) to 200 calls per 100 seconds.
  //However this is per SOURCE project of the SA making the requests, NOT the project you're making the request ON!
  val opThrottler = new Throttler(system, gpAllocConfig.opsThrottle, gpAllocConfig.opsThrottlePerDuration, "GoogleOpThrottler")

  //And of course GCP has a totally different ratelimit for the call to batchEnable.
  val batchEnableThrottler = new Throttler(system, gpAllocConfig.enableThrottle, gpAllocConfig.enableThrottlePerDuration, "GoogleBatchEnableThrottler")

  //giant bundle of scopes we need
  val saScopes = Seq(
    "https://www.googleapis.com/auth/cloud-billing",
    ComputeScopes.CLOUD_PLATFORM
   )

  val credential: Credential = {
    new GoogleCredential.Builder()
      .setTransport(httpTransport)
      .setJsonFactory(jsonFactory)
      .setServiceAccountScopes(saScopes.asJava)
      .setServiceAccountId(billingPemEmail)
      .setServiceAccountPrivateKeyFromPemFile(new java.io.File(serviceAccountPemFile))
      .setServiceAccountUser(billingEmail)
      .build()
  }

  private def billing: Cloudbilling = {
    new Cloudbilling.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
  }

  def cloudResources: CloudResourceManager = {
    new CloudResourceManager.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
  }

  def serviceUsage: ServiceUsage = {
    new ServiceUsage.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
  }

  def computeManager: Compute = {
    new Compute.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
  }

  def storage: Storage = {
    new Storage.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
  }

  def iam: Iam = {
    new Iam.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
  }

  //leaving this floating around because it's useful for debugging
  implicit class DebuggableFuture[T](f: Future[T]) {
    def debug(str: String) = {
      f.onComplete {
        case Success(_) =>
        case Failure(e) => logger.error(str, e)
      }
      f
    }
  }

  override def transferProjectOwnership(project: String, owner: String): Future[AssignedProject] = {
    /* NOTE: There is no work to be done here. It is up to the caller, inside their own FC stack to:
     * - add the project to the rawls db
     * - tell sam about the resource
     */
    Future.successful(AssignedProject(project, cromwellAuthBucketName(project)))
  }

  override def scrubBillingProject(projectName: String): Future[Unit] = {
    for {
      googleProject <- getGoogleProject(projectName)
      _ <- cleanupPolicyBindings(projectName, googleProject.getProjectNumber)
      _ <- cleanupPets(projectName)
      _ <- cleanupCromwellAuthBucket(projectName)
    } yield {
      //nah
    }
  }

  //poll google for what's going on
  override def pollOperation(operation: ActiveOperationRecord): Future[ActiveOperationRecord] = {

    // this code is a colossal DRY violation but because the operations collection is different
    // for cloudResManager and serviceUsage and they return different but identical Status objects
    // there is not much else to be done... too bad scala does not have duck typing.
    operation.operationType match {
      case CreatingProject =>
        opThrottler.throttle(() => retryWithRecoverWhen500orGoogleError(() => {
          executeGoogleRequest(cloudResources.operations().get(operation.operationId))
        }) {
          case t: HttpResponseException if t.getStatusCode == 429 =>
            //429 is Too Many Requests. If we get this back, just say we're not done yet and try again later
            logger.warn(s"Google 429 for pollOperation ${operation.billingProjectName} ${operation.operationType}. Retrying next round...")
            new CRMOperation().setDone(false).setError(null)
        }).map { op =>
          operation.copy(done = toScalaBool(op.getDone), errorMessage = Option(op.getError).map(error => toErrorMessage(error.getMessage, error.getCode)))
        }

      case EnablingServices =>
        opThrottler.throttle(() => retryWithRecoverWhen500orGoogleError(() => {
          executeGoogleRequest(serviceUsage.operations().get(operation.operationId))
        }) {
          case t: HttpResponseException if t.getStatusCode == 429 =>
            //429 is Too Many Requests. If we get this back, just say we're not done yet and try again later
            logger.warn(s"Google 429 for pollOperation ${operation.billingProjectName} ${operation.operationType}. Retrying next round...")
            new SUOperation().setDone(false).setError(null)
        }).map { op =>
          operation.copy(done = toScalaBool(op.getDone), errorMessage = Option(op.getError).map(error => toErrorMessage(error.getMessage, error.getCode)))
        }
    }
  }

  case class GoogleProjectConflict(projectName: String)
    extends GPAllocException(s"A google project by the name $projectName already exists", StatusCodes.Conflict)

  //part 1
  override def createProject(projectName: String, billingAccount: String): Future[ActiveOperationRecord] = {
    retryWhen500orGoogleError(() => {
      executeGoogleRequest(cloudResources.projects().create(
        new Project()
          .setName(projectName)
          .setProjectId(projectName)))
    }).recover {
      case t: HttpResponseException if StatusCode.int2StatusCode(t.getStatusCode) == StatusCodes.Conflict =>
        throw GoogleProjectConflict(projectName)
    } map ( googleOperation => {
      if (toScalaBool(googleOperation.getDone) && Option(googleOperation.getError).exists(_.getCode == Code.ALREADY_EXISTS.value())) {
        throw GoogleProjectConflict(projectName)
      }
      ActiveOperationRecord(projectName, CreatingProject, googleOperation.getName, toScalaBool(googleOperation.getDone), Option(googleOperation.getError).map(error => toErrorMessage(error.getMessage, error.getCode)))
    })
  }

  /**
    * converts a possibly null java boolean to a scala boolean, null is treated as false
    */
  private def toScalaBool(b: java.lang.Boolean) = Option(b).contains(java.lang.Boolean.TRUE)

  private def toErrorMessage(message: String, code: Int): String = {
    s"${Option(message).getOrElse("")} - code ${code}"
  }

  // part 2
  override def enableCloudServices(projectName: String, billingAccount: String): Future[Seq[ActiveOperationRecord]] = {
    val billingManager = billing

    val projectResourceName = s"projects/$projectName"

    //batchEnable has a limit of 20 APIs to enable per call. we are currently at 20.
    //next person to add an API has to make a second call ;)
    val services = Seq(
      "autoscaler.googleapis.com",
      "bigquery-json.googleapis.com",
      "clouddebugger.googleapis.com",
      "container.googleapis.com",
      "dataflow.googleapis.com",
      "dataproc.googleapis.com",
      "deploymentmanager.googleapis.com",
      "genomics.googleapis.com",
      "logging.googleapis.com",
      "replicapool.googleapis.com",
      "replicapoolupdater.googleapis.com",
      "resourceviews.googleapis.com",
      "sql-component.googleapis.com",
      "storage-api.googleapis.com",
      "storage-component.googleapis.com"
    )

    def batchEnableGoogleServices(projectNumber: Long) = {
      val batchEnableProjectNumber = s"projects/$projectNumber"
      retryWhen500orGoogleError(() => {
        executeGoogleRequest(serviceUsage.services.batchEnable(batchEnableProjectNumber, new BatchEnableServicesRequest().setServiceIds(services.asJava)))
      }) map { googleOperation =>
        Seq(ActiveOperationRecord(projectName, EnablingServices, googleOperation.getName, toScalaBool(googleOperation.getDone), Option(googleOperation.getError).map(error => toErrorMessage(error.getMessage, error.getCode))))
      }
    }

    // all of these things should be idempotent
    for {
    // set the billing account
      billing <- opThrottler.throttle( () => retryWhen500orGoogleError(() => {
        executeGoogleRequest(billingManager.projects().updateBillingInfo(projectResourceName, new ProjectBillingInfo().setBillingEnabled(true).setBillingAccountName(billingAccount)))
      }))

      googleProject <- getGoogleProject(projectName)

      // enable appropriate google apis
      operations <- batchEnableThrottler.throttle(() => batchEnableGoogleServices(googleProject.getProjectNumber))

    } yield {
      operations
    }
  }

  //part 3
  override def setupProjectBucketAccess(projectName: String): Future[Unit] = {
    val usageBucketName = s"${projectName}-usage-export"

    // all of these things should be idempotent
    for {
    // create project usage export bucket
      exportBucket <- retryWithRecoverWhen500orGoogleError(() => {
        val bucket = new Bucket().setName(usageBucketName)
        executeGoogleRequest(storage.buckets.insert(projectName, bucket))
      }) { case t: HttpResponseException if t.getStatusCode == 409 => new Bucket().setName(usageBucketName) }

      // create bucket for workspace bucket storage/usage logs
      storageLogsBucket <- createStorageLogsBucket(projectName)
      _ <- retryWhen500orGoogleError(() => { allowGoogleCloudStorageWrite(storageLogsBucket) })

      googleProject <- getGoogleProject(projectName)

      cromwellAuthBucket <- createCromwellAuthBucket(projectName, googleProject.getProjectNumber)

      _ <- retryWhen500orGoogleError(() => {
        val usageLoc = new UsageExportLocation().setBucketName(usageBucketName).setReportNamePrefix("usage")
        executeGoogleRequest(computeManager.projects().setUsageExportBucket(projectName, usageLoc))
      })
    } yield {
      // nothing
    }
  }

  //part ways
  override def deleteProject(projectName: String): Future[Unit] = {
    retryWhen500orGoogleError(() => {
      executeGoogleRequest(cloudResources.projects.delete(projectName))
    })
  }

  def getGoogleProject(projectName: String): Future[Project] = {
    retryWhen500orGoogleError(() => {
      executeGoogleRequest(cloudResources.projects().get(projectName))
    })
  }

  protected def googleRq[T](op: AbstractGoogleClientRequest[T]) = {
    retryWhen500orGoogleError(() => executeGoogleRequest(op))
  }

  protected def cromwellAuthBucketName(bpName: String) = s"cromwell-auth-$bpName"

  protected def createCromwellAuthBucket(billingProjectName: String, projectNumber: Long): Future[String] = {
    val bucketName = cromwellAuthBucketName(billingProjectName)
    retryWithRecoverWhen500orGoogleError(
      () => {
        //Note we have to give ourselves access to the bucket too, otherwise we can't clean up the bucket when we're done.
        val bucketAcls = List(
          new BucketAccessControl().setEntity("user-" + billingEmail).setRole("OWNER"),
          new BucketAccessControl().setEntity("project-editors-" + projectNumber).setRole("OWNER"),
          new BucketAccessControl().setEntity("project-owners-" + projectNumber).setRole("OWNER")).asJava
        val defaultObjectAcls = List(new ObjectAccessControl().setEntity("project-editors-" + projectNumber).setRole("OWNER"), new ObjectAccessControl().setEntity("project-owners-" + projectNumber).setRole("OWNER")).asJava
        val bucket = new Bucket().setName(bucketName).setAcl(bucketAcls).setDefaultObjectAcl(defaultObjectAcls)
        val inserter = storage.buckets.insert(billingProjectName, bucket)
        executeGoogleRequest(inserter)

        bucketName
      }) { case t: HttpResponseException if t.getStatusCode == 409 => bucketName }
  }

  protected def leaveThisIAMPolicy(entityName: String, projectNumber: Long): Boolean = {
    if(entityName.startsWith("serviceAccount:")) {
      /* of course, Google's pre-generated SAs aren't consistently named:
       *   projectNumber-compute@developer.gserviceaccount.com
       *   projectNumber@cloudservices.gserviceaccount.com
       *   service-projectNumber@containerregistry.iam.gserviceaccount.com
       * so we'll just leave SAs that contain the project number.
       */
      entityName.split("@").head.contains(s"$projectNumber")
    } else {
      //the only other acceptable user is billing@thisdomain.firecloud.org
      entityName == s"user:$billingEmail"
    }
  }

  protected def cleanupPolicyBindings(projectName: String, projectNumber: Long): Future[Unit] = {
    for {
      existingPolicy <- opThrottler.throttle( () => retryWhen500orGoogleError(() => {
        executeGoogleRequest(cloudResources.projects().getIamPolicy(projectName, null))
      }))

      _ <- opThrottler.throttle( () => retryWhen500orGoogleError(() => {
        val existingPolicies: Map[String, Seq[String]] = existingPolicy.getBindings.asScala.map { policy => policy.getRole -> policy.getMembers.asScala }.toMap

        val updatedPolicies = existingPolicies.map { case (role, members) =>
          (role, members.filter(entityName => leaveThisIAMPolicy(entityName, projectNumber)))
        }.collect {
          case (role, members) if members.nonEmpty =>
            new Binding().setRole(role).setMembers(members.distinct.asJava)
        }.toList

        // when setting IAM policies, always reuse the existing policy so the etag is preserved.
        val policyRequest = new SetIamPolicyRequest().setPolicy(existingPolicy.setBindings(updatedPolicies.asJava))
        executeGoogleRequest(cloudResources.projects().setIamPolicy(projectName, policyRequest))
      }))
    } yield ()
  }



  protected def shouldDeleteBucketACL(entityName: String): Boolean = {
    !(entityName.startsWith("project-owners-") || entityName.startsWith("project-editors-"))
  }

  //removes all acls added to the cromwell auth bucket that aren't the project owner/editor ones
  protected def cleanupCromwellAuthBucket(billingProjectName: String): Future[Unit] = {
    val bucketName = cromwellAuthBucketName(billingProjectName)
    for {
      oAcls <- googleRq( storage.defaultObjectAccessControls.list(bucketName) )
      deleteOAcls = oAcls.getItems.asScala.filter(a => shouldDeleteBucketACL(a.getEntity))
      _ <- sequentially(deleteOAcls) { d => googleRq(storage.defaultObjectAccessControls.delete(bucketName, d.getEntity)) }

      bAcls <- googleRq( storage.bucketAccessControls.list(bucketName) )
      deleteBAcls = bAcls.getItems.asScala.filter(a => shouldDeleteBucketACL(a.getEntity))
      _ <- sequentially(deleteBAcls) { d => googleRq(storage.bucketAccessControls.delete(bucketName, d.getEntity)) }
    } yield {
      //nah
    }
  }

  def googNull[T](list: java.util.List[T]): Seq[T] = {
    Option(list).map(_.asScala).getOrElse(Seq())
  }

  //dear god, google. surely there's a better way
  def gProjectPath(project: String) = s"projects/$project"

  protected def cleanupPets(projectName: String): Future[Unit] = {
    for {
      serviceAccounts <- googleRq( iam.projects().serviceAccounts().list(gProjectPath(projectName)) )
      pets = googNull(serviceAccounts.getAccounts).filter(_.getEmail.startsWith("pet-"))
      _ <- sequentially(pets) { pet => googleRq( iam.projects.serviceAccounts.delete(pet.getName) ) }
    } yield {
      //nah
    }
  }

  protected def createStorageLogsBucket(billingProjectName: String): Future[String] = {
    val bucketName = s"storage-logs-$billingProjectName"
    logger debug s"storage log bucket: $bucketName"

    retryWithRecoverWhen500orGoogleError(() => {
      val bucket = new Bucket().setName(bucketName)
      val storageLogExpiration = new Lifecycle.Rule()
        .setAction(new Action().setType("Delete"))
        .setCondition(new Condition().setAge(180)) //in days, stolen from rawls:reference.conf
      bucket.setLifecycle(new Lifecycle().setRule(List(storageLogExpiration).asJava))
      val inserter = storage.buckets().insert(billingProjectName, bucket)
      executeGoogleRequest(inserter)

      bucketName
    }) {
      // bucket already exists
      case t: HttpResponseException if t.getStatusCode == 409 => bucketName
    }
  }

  protected def allowGoogleCloudStorageWrite(bucketName: String): Unit = {
    // add cloud-storage-analytics@google.com as a writer so it can write logs
    // do it as a separate call so bucket gets default permissions plus this one
    val bac = new BucketAccessControl().setEntity("group-cloud-storage-analytics@google.com").setRole("WRITER")
    executeGoogleRequest(storage.bucketAccessControls.insert(bucketName, bac))
  }
}
