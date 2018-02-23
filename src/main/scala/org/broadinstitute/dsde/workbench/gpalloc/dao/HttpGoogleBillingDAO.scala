package org.broadinstitute.dsde.workbench.gpalloc.dao

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.{GoogleClientSecrets, GoogleCredential}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class HttpGoogleBillingDAO(appName: String, serviceAccountPemFile: String, billingPemEmail: String, billingEmail: String)
                           (implicit val system: ActorSystem, val executionContext: ExecutionContext)
  extends GoogleDAO with GoogleUtilities {

  protected val workbenchMetricBaseName = "billing"
  implicit val service = GoogleInstrumentedService.Iam

  implicit val counters = googleCounters(service)
  implicit val histo = googleRetryHistogram(service)

  implicit val errorReportSource = ErrorReportSource("gpalloc-google")

  lazy val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  lazy val jsonFactory = JacksonFactory.getDefaultInstance

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
      _ <- cleanupPolicyBindings(projectName)
      _ <- cleanupPetSAKeys(projectName)
      _ <- cleanupCromwellAuthBucket(projectName)
    } yield {
      //nah
    }
  }

  //poll google for what's going on
  override def pollOperation(operation: ActiveOperationRecord, throttler: Throttler): Future[ActiveOperationRecord] = {

    // this code is a colossal DRY violation but because the operations collection is different
    // for cloudResManager and servicesManager and they return different but identical Status objects
    // there is not much else to be done... too bad scala does not have duck typing.
    operation.operationType match {
      case CreatingProject =>
        throttler.throttle(() => retryWhen500orGoogleError(() => {
          executeGoogleRequest(cloudResources.operations().get(operation.operationId))
        })).map { op =>
          operation.copy(done = toScalaBool(op.getDone), errorMessage = Option(op.getError).map(error => toErrorMessage(error.getMessage, error.getCode)))
        }

      case EnablingServices =>
        throttler.throttle(() => retryWhen500orGoogleError(() => {
          executeGoogleRequest(servicesManager.operations().get(operation.operationId))
        })).map { op =>
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
  override def enableCloudServices(projectName: String, billingAccount: String, throttler: Throttler): Future[Seq[ActiveOperationRecord]] = {

    val billingManager = billing
    val serviceManager = servicesManager

    val projectResourceName = s"projects/$projectName"
    val services = Seq("autoscaler", "bigquery", "clouddebugger", "container", "compute_component", "dataflow.googleapis.com", "dataproc", "deploymentmanager", "genomics", "logging.googleapis.com", "replicapool", "replicapoolupdater", "resourceviews", "sql_component", "storage_api", "storage_component")

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
      billing <- throttler.throttle( () => retryWhen500orGoogleError(() => {
        executeGoogleRequest(billingManager.projects().updateBillingInfo(projectResourceName, new ProjectBillingInfo().setBillingEnabled(true).setBillingAccountName(billingAccount)))
      }))

      // enable appropriate google apis
      operations <- throttler.sequence(services.map { service => { () => enableGoogleService(service) } })

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

  def getGoogleProject(projectName: String): Future[Project] = {
    retryWhen500orGoogleError(() => {
      executeGoogleRequest(cloudResources.projects().get(projectName))
    })
  }

  def googleRq[T](op: AbstractGoogleClientRequest[T]) = {
    retryWhen500orGoogleError(() => executeGoogleRequest(op))
  }

  def cromwellAuthBucketName(bpName: String) = s"cromwell-auth-$bpName"

  def createCromwellAuthBucket(billingProjectName: String, projectNumber: Long): Future[String] = {
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

  private def shouldDelete(entityName: String): Boolean = {
    !(entityName.startsWith("project-owners-") || entityName.startsWith("project-editors-"))
  }

  def cleanupPolicyBindings(projectName: String): Future[Unit] = {
    for {
      existingPolicy <- retryWhen500orGoogleError(() => {
        executeGoogleRequest(cloudResources.projects().getIamPolicy(projectName, null))
      })

      _ <- retryWhen500orGoogleError(() => {
        val existingPolicies: Map[String, Seq[String]] = existingPolicy.getBindings.asScala.map { policy => policy.getRole -> policy.getMembers.asScala }.toMap

        val updatedPolicies = existingPolicies.map { case (role, members) =>
          (role, members.filterNot(_.startsWith("group:policy-")))
        }.collect {
          case (role, members) if members.nonEmpty =>
            new Binding().setRole(role).setMembers(members.distinct.asJava)
        }.toList

        // when setting IAM policies, always reuse the existing policy so the etag is preserved.
        val policyRequest = new SetIamPolicyRequest().setPolicy(existingPolicy.setBindings(updatedPolicies.asJava))
        executeGoogleRequest(cloudResources.projects().setIamPolicy(projectName, policyRequest))
      })
    } yield ()
  }

  //removes all acls added to the cromwell auth bucket that aren't the project owner/editor ones
  def cleanupCromwellAuthBucket(billingProjectName: String): Future[Unit] = {
    val bucketName = cromwellAuthBucketName(billingProjectName)
    for {
      oAcls <- googleRq( storage.defaultObjectAccessControls.list(bucketName) )
      deleteOAcls = oAcls.getItems.asScala.filter(a => shouldDelete(a.getEntity))
      _ <- Future.traverse(deleteOAcls) { d => googleRq(storage.defaultObjectAccessControls.delete(bucketName, d.getEntity)) }

      bAcls <- googleRq( storage.bucketAccessControls.list(bucketName) )
      deleteBAcls = bAcls.getItems.asScala.filter(a => shouldDelete(a.getEntity))
      _ <- Future.traverse(deleteBAcls) { d => googleRq(storage.bucketAccessControls.delete(bucketName, d.getEntity)) }
    } yield {
      //nah
    }
  }

  //dear god, google. surely there's a better way
  def gProjectPath(project: String) = s"projects/$project"
  def gSAPath(project: String, serviceAccountEmail: String) = gProjectPath(project) + s"/serviceAccounts/$serviceAccountEmail"
  def gKeyPath(project: String, serviceAccountEmail: String, keyEmail: String) = gSAPath(project, serviceAccountEmail) + s"/keys/$keyEmail"

  def cleanupPetSAKeys(projectName: String): Future[Unit] = {
    for {
      serviceAccounts <- googleRq( iam.projects().serviceAccounts().list(gProjectPath(projectName)) )
      pets = serviceAccounts.getAccounts.asScala.filter(_.getEmail.startsWith("pet-"))
      _ <- removeKeysForPets(projectName, pets)
    } yield {
      //nah
    }
  }

  def removeKeysForPets(projectName: String, pets: Seq[ServiceAccount]): Future[Unit] = {
    Future.traverse(pets){ pet => //these run in parallel
      for {
        petKeys <- googleRq(iam.projects.serviceAccounts.keys.list(gSAPath(projectName, pet.getEmail)))
        _ <- removeKeysForPet(projectName, pet.getEmail, petKeys.getKeys.asScala)
      } yield {
        //nah
      }
    }.map(_ => ())
  }

  def removeKeysForPet(projectName: String, petEmail: String, petKeys: Seq[ServiceAccountKey]): Future[Unit] = {
    Future.traverse(petKeys){ petKey => //these run in parallel
      for {
        _ <- googleRq(iam.projects.serviceAccounts.keys.delete(gKeyPath(projectName, petEmail, petKey.getName)))
      } yield {
        //nah
      }
    }.map(_ => ())
  }

  def createStorageLogsBucket(billingProjectName: String): Future[String] = {
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

  def allowGoogleCloudStorageWrite(bucketName: String): Unit = {
    // add cloud-storage-analytics@google.com as a writer so it can write logs
    // do it as a separate call so bucket gets default permissions plus this one
    val bac = new BucketAccessControl().setEntity("group-cloud-storage-analytics@google.com").setRole("WRITER")
    executeGoogleRequest(storage.bucketAccessControls.insert(bucketName, bac))
  }
}
