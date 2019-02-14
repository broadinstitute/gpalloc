package org.broadinstitute.dsde.workbench.gpalloc.dao

import java.util.UUID

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import cats.data.OptionT
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.{GoogleClientSecrets, GoogleCredential}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.HttpResponseException
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.admin.directory.DirectoryScopes
import com.google.api.services.cloudbilling.Cloudbilling
import com.google.api.services.cloudbilling.model.ProjectBillingInfo
import com.google.api.services.cloudresourcemanager.CloudResourceManager
import com.google.api.services.cloudresourcemanager.model.{Binding, Project, SetIamPolicyRequest, Operation => CRMOperation}
import com.google.api.services.compute.model.UsageExportLocation
import com.google.api.services.compute.{Compute, ComputeScopes}
import com.google.api.services.genomics.GenomicsScopes
import com.google.api.services.iam.v1.Iam
import com.google.api.services.dataproc.Dataproc
import com.google.api.services.iam.v1.model.{ServiceAccount, ServiceAccountKey}
import com.google.api.services.plus.PlusScopes
import com.google.api.services.servicemanagement.ServiceManagement
import com.google.api.services.servicemanagement.model.{EnableServiceRequest, Operation => SMOperation}
import com.google.api.services.storage.model.Bucket.Lifecycle
import com.google.api.services.storage.model.Bucket.Lifecycle.Rule.{Action, Condition}
import com.google.api.services.storage.model.{Bucket, BucketAccessControl, ObjectAccessControl}
import com.google.api.services.storage.{Storage, StorageScopes}
import io.grpc.Status.Code
import org.broadinstitute.dsde.workbench.google.GoogleUtilities
import org.broadinstitute.dsde.workbench.gpalloc.db.ActiveOperationRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.broadinstitute.dsde.workbench.gpalloc.model.{AssignedProject, BillingProjectStatus, GPAllocException}
import org.broadinstitute.dsde.workbench.gpalloc.util.Throttler
import org.broadinstitute.dsde.workbench.metrics.{GoogleInstrumented, GoogleInstrumentedService}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource, WorkbenchExceptionWithErrorReport}

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class HttpGoogleBillingDAO(appName: String,
                           serviceAccountPemFile: String,
                           billingPemEmail: String,
                           billingEmail: String,
                           defaultBillingAccount: String,
                           opsThrottle: Int,
                           opsThrottlePerDuration: FiniteDuration)
                           (implicit val system: ActorSystem, val executionContext: ExecutionContext)
  extends GoogleDAO with GoogleUtilities {

  protected val workbenchMetricBaseName = "billing"
  implicit val service = GoogleInstrumentedService.Iam

  implicit val counters = googleCounters(service)
  implicit val histo = googleRetryHistogram(service)

  implicit val errorReportSource = ErrorReportSource("gpalloc-google")

  lazy val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  lazy val jsonFactory = JacksonFactory.getDefaultInstance

  //Google throttles other project service management requests (like operation polls) to 200 calls per 100 seconds.
  //However this is per SOURCE project of the SA making the requests, NOT the project you're making the request ON!
  val opThrottler = new Throttler(system, opsThrottle, opsThrottlePerDuration, "GoogleOpThrottler")

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

  def servicesManager: ServiceManagement = {
    new ServiceManagement.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
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

  def dataproc: Dataproc = {
    new Dataproc.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
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

  private def updateGoogleBillingInfo(projectName: String, billingAccount: String) = {
    val projectResourceName = s"projects/$projectName"
    opThrottler.throttle( () => retryWhen500orGoogleError(() => {
      executeGoogleRequest(billing.projects().updateBillingInfo(projectResourceName, new ProjectBillingInfo().setBillingEnabled(true).setBillingAccountName(billingAccount)))
    }))
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
      _ <- cleanupClusters(projectName)
      _ <- cleanupPolicyBindings(projectName, googleProject.getProjectNumber)
      _ <- cleanupPets(projectName)
      _ <- cleanupCromwellAuthBucket(projectName)
      _ <- updateGoogleBillingInfo(projectName, defaultBillingAccount)
    } yield {
      //nah
    }
  }

  //poll google for what's going on
  override def pollOperation(operation: ActiveOperationRecord): Future[ActiveOperationRecord] = {

    // this code is a colossal DRY violation but because the operations collection is different
    // for cloudResManager and servicesManager and they return different but identical Status objects
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
          executeGoogleRequest(servicesManager.operations().get(operation.operationId))
        }) {
          case t: HttpResponseException if t.getStatusCode == 429 =>
            //429 is Too Many Requests. If we get this back, just say we're not done yet and try again later
            logger.warn(s"Google 429 for pollOperation ${operation.billingProjectName} ${operation.operationType}. Retrying next round...")
            new SMOperation().setDone(false).setError(null)
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

    val serviceManager = servicesManager

    val services = Seq("autoscaler", "bigquery", "clouddebugger", "container", "compute_component", "dataflow.googleapis.com", "dataproc", "deploymentmanager", "genomics", "logging.googleapis.com", "replicapool", "replicapoolupdater", "resourceviews", "sql_component", "storage_api", "storage_component", "cloudkms.googleapis.com")

    def enableGoogleService(service: String) = {
      retryWhen500orGoogleError(() => {
        executeGoogleRequest(serviceManager.services().enable(service, new EnableServiceRequest().setConsumerId(s"project:${projectName}")))
      }) map { googleOperation =>
        ActiveOperationRecord(projectName, EnablingServices, googleOperation.getName, toScalaBool(googleOperation.getDone), Option(googleOperation.getError).map(error => toErrorMessage(error.getMessage, error.getCode)))
      }
    }

    // all of these things should be idempotent
    for {
    // set the billing account
      _ <- updateGoogleBillingInfo(projectName, billingAccount)

      // enable appropriate google apis
      operations <- opThrottler.sequence(services.map { service => { () => enableGoogleService(service) } })

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

  //stolen: https://gist.github.com/ryanlecompte/6313683
  def sequentially[A,T](items: Seq[A])(f: A => Future[T]): Future[Unit] = {
    items.headOption match {
      case Some(nextItem) =>
        val fut = f(nextItem)
        fut.flatMap { _ =>
          // successful, let's move on to the next!
          sequentially(items.tail)(f)
        }
      case None =>
        // nothing left to process
        Future.successful(())
    }
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
      _ <- sequentially(deleteOAcls) { d =>
        googleRq(storage.defaultObjectAccessControls.delete(bucketName, d.getEntity)).recover {
          // ignore any errors where the group is already gone
          case ge: GoogleJsonResponseException if ge.getStatusCode == 400 && ge.getMessage.contains("Could not find group") =>
        }
      }

      bAcls <- googleRq( storage.bucketAccessControls.list(bucketName) )
      deleteBAcls = bAcls.getItems.asScala.filter(a => shouldDeleteBucketACL(a.getEntity))
      _ <- sequentially(deleteBAcls) { d =>
        googleRq(storage.bucketAccessControls.delete(bucketName, d.getEntity)).recover {
          // ignore any errors where the group is already gone
          case ge: GoogleJsonResponseException if ge.getStatusCode == 400 && ge.getMessage.contains("Could not find group") =>
        }
      }
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
      pets = googNull(serviceAccounts.getAccounts).filter(_.getEmail.contains(s"@$projectName.iam.gserviceaccount.com"))
      _ <- sequentially(pets) { pet => googleRq( iam.projects.serviceAccounts.delete(pet.getName) ) }
    } yield {
      //nah
    }
  }

  // Leonardo currently only creates clusters in the region us-central1. If it were to start supporting other regions, this should be updated.
  def cleanupClusters(projectName: String): Future[Unit] = {
    for {
      result <- googleRq(dataproc.projects().regions().clusters().list(projectName, "us-central1"))
      googleClusters = googNull(result.getClusters)
      clusterNames = googleClusters.map(c => c.getClusterName)
      _ <- sequentially(clusterNames) { clusterName =>
        googleRq(dataproc.projects().regions().clusters().delete(projectName, "us-central1", clusterName)).recover {
          // ignore errors on already deleting clusters
          case ge: GoogleJsonResponseException if ge.getStatusCode == 400 && ge.getMessage.contains("while it has other pending delete operations") =>
        }
      }
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
