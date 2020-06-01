package org.broadinstitute.dsde.workbench.gpalloc.dao

import java.io.IOException
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
import com.google.api.services.deploymentmanager.model.{ConfigFile, Deployment, ImportFile, TargetConfiguration}
import com.google.api.services.deploymentmanager.DeploymentManagerV2Beta
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
import org.broadinstitute.dsde.workbench.metrics.{GoogleInstrumented, GoogleInstrumentedService, Histogram}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource, WorkbenchExceptionWithErrorReport}
import net.jcazevedo.moultingyaml._
import spray.json.JsValue

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.matching.Regex
import scala.util.{Failure, Success}

case class Resources (
                       name: String,
                       `type`: String,
                       properties: Map[String, YamlValue]
                     )
case class ConfigContents (
                            resources: Seq[Resources]
                          )

//we're not using camelcase here because these become GCS labels, which all have to be lowercase.
case class TemplateLocation(
                             template_org: String,
                             template_repo: String,
                             template_branch: String,
                             template_path: String
                           )

object DeploymentManagerYamlSupport {
  import net.jcazevedo.moultingyaml.DefaultYamlProtocol._
  implicit val resourceYamlFormat = yamlFormat3(Resources)
  implicit val configContentsYamlFormat = yamlFormat1(ConfigContents)
  implicit val templateLocationYamlFormat = yamlFormat4(TemplateLocation)
}

class HttpGoogleBillingDAO(appName: String,
                           serviceAccountPemFile: String,
                           billingPemEmail: String,
                           billingEmail: String,
                           billingGroupEmail: String,
                           defaultBillingAccount: String,
                           orgID: Long,
                           deploymentMgrProject: String,
                           dmTemplatePath: String,
                           cleanupDeploymentAfterCreating: Boolean,
                           requesterPaysRole: String,
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

  def deploymentManager: DeploymentManagerV2Beta = {
    new DeploymentManagerV2Beta.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
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

  def labelSafeString(s: String, prefix: String = "fc-"): String = {
    // https://cloud.google.com/compute/docs/labeling-resources#restrictions
    prefix + s.toLowerCase.replaceAll("[^a-z0-9\\-_]", "-").take(63)
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
      _ <- cleanupVMs(projectName)
      _ <- cleanupPolicyBindings(projectName, googleProject.getProjectNumber)
      _ <- cleanupPets(projectName)
      _ <- cleanupCromwellAuthBucket(projectName)
      _ <- updateGoogleBillingInfo(projectName, defaultBillingAccount)
    } yield {
      //nah
    }
  }

  override def pollOperation(operation: ActiveOperationRecord): Future[ActiveOperationRecord] = {
    opThrottler.throttle( () => retryWhen500orGoogleError(() => {
      executeGoogleRequest(deploymentManager.operations().get(deploymentMgrProject, operation.operationId))
    })).map { op =>
      val errorStr = Option(op.getError).map(errors => errors.getErrors.asScala.map(e => toErrorMessage(e.getMessage, e.getCode)).mkString("\n"))
      operation.copy(done = op.getStatus == "DONE", errorMessage = errorStr)
    }
  }

  case class GoogleProjectConflict(projectName: String)
    extends GPAllocException(s"A google project by the name $projectName already exists", StatusCodes.Conflict)

  def getDMConfigYamlString(projectName: String, dmTemplatePath: String, properties: Map[String, YamlValue]): String = {
    import DeploymentManagerYamlSupport._
    ConfigContents(Seq(Resources(projectName, dmTemplatePath, properties))).toYaml.prettyPrint
  }

  protected def whenDeploymentDeleteConflict(throwable: Throwable): Boolean = {
    throwable match {
      case t: GoogleJsonResponseException => t.getStatusCode == 409
      case _ => false
    }
  }

  /*
 * Set the deployment policy to "abandon" -- i.e. allows the created project to persist even if the deployment is deleted --
 * and then delete the deployment. There's a limit of 1000 deployments so this is important to do.
 */
  override def cleanupDeployment(projectName: String): Future[Unit] = {
    if( cleanupDeploymentAfterCreating ) {
      retryExponentially(whenDeploymentDeleteConflict) { () => Future(blocking(executeGoogleRequest(
        deploymentManager.deployments().delete(deploymentMgrProject, projectToDM(projectName)).setDeletePolicy("ABANDON"))
      ))} map ( _ => () )
    } else {
      Future.successful(())
    }
  }

  def projectToDM(projectName: String) = s"dm-$projectName"


  def parseTemplateLocation(path: String): Option[TemplateLocation] = {
    val rx: Regex = "https://raw.githubusercontent.com/(.*)/(.*)/(.*)/(.*)".r
    rx.findAllMatchIn(path).toList.headOption map { groups =>
      TemplateLocation(
        labelSafeString(groups.subgroups(0), ""),
        labelSafeString(groups.subgroups(1), ""),
        labelSafeString(groups.subgroups(2), ""),
        labelSafeString(groups.subgroups(3), ""))
    }
  }

  override def createProject(projectName: String, billingAccountId: String): Future[ActiveOperationRecord] = {
    import DefaultYamlProtocol._
    import DeploymentManagerYamlSupport._

    val templateLabels = parseTemplateLocation(dmTemplatePath).map(_.toYaml).getOrElse(Map("template_path" -> labelSafeString(dmTemplatePath)).toYaml)

    val properties = Map (
      "billingAccountId" -> billingAccountId.toYaml,
      "projectId" -> projectName.toYaml,
      "parentOrganization" -> orgID.toYaml,
      "fcBillingGroup" -> billingGroupEmail.toYaml,
      //"projectOwnersGroup" -> ownerGroupEmail.toYaml,                 //NOTE: these are set by rawls. DM lets these be empty
      //"projectViewersGroup" -> computeUserGroupEmail.toYaml,
      "fcProjectOwners" -> List(s"group:$billingGroupEmail").toYaml,
      //"fcProjectEditors" -> projectTemplate.editors.toYaml,
      "requesterPaysRole" -> requesterPaysRole.toYaml,
      "highSecurityNetwork" -> false.toYaml,
      "labels" -> templateLabels
    )

    //a list of one resource: type=composite-type, name=whocares, properties=pokein
    val yamlConfig = new ConfigFile().setContent(getDMConfigYamlString(projectName, dmTemplatePath, properties))
    val deploymentConfig = new TargetConfiguration().setConfig(yamlConfig)

    opThrottler.throttle ( () => retryWhen500orGoogleError(() => {
        executeGoogleRequest {
          deploymentManager.deployments().insert(deploymentMgrProject, new Deployment().setName(projectToDM(projectName)).setTarget(deploymentConfig))
      }})) map { googleOperation =>
        val errorStr = Option(googleOperation.getError).map(errors => errors.getErrors.asScala.map(e => toErrorMessage(e.getMessage, e.getCode)).mkString("\n"))
        ActiveOperationRecord(projectName, CreatingProject, googleOperation.getName, toScalaBool(googleOperation.getStatus == "DONE"), errorStr)
    }
  }

  /**
    * converts a possibly null java boolean to a scala boolean, null is treated as false
    */
  private def toScalaBool(b: java.lang.Boolean) = Option(b).contains(java.lang.Boolean.TRUE)

  private def toErrorMessage(message: String, code: Int): String = {
    s"${Option(message).getOrElse("")} - code ${code}"
  }

  private def toErrorMessage(message: String, code: String): String = {
    s"${Option(message).getOrElse("")} - code ${code}"
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
      //the only other acceptable users:
      // - terra-billing@thisdomain.firecloud.org
      // - billing@thisdomain.firecloud.org
      entityName == s"group:$billingGroupEmail" ||
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


  protected def when500orGoogleErrorButNot404(throwable: Throwable): Boolean = {
    throwable match {
      case t: GoogleJsonResponseException => {
        ((t.getStatusCode == 403 || t.getStatusCode == 429) && t.getDetails.getErrors.asScala.head.getDomain.equalsIgnoreCase("usageLimits")) ||
          (t.getStatusCode == 400 && t.getDetails.getErrors.asScala.head.getReason.equalsIgnoreCase("invalid")) ||
          t.getStatusCode/100 == 5
      }
      case t: HttpResponseException => t.getStatusCode/100 == 5
      case ioe: IOException => true
      case _ => false
    }
  }

  protected def retryForPetDeletion(op: () => Unit)(implicit histo: Histogram): Future[Unit] = {
    retryExponentially(when500orGoogleErrorButNot404){() =>
      Future(blocking(op())).recover {
        //404 is okay, means the pet is already deleted which is what we want.
        //probably what happened is the test deleted the project, which now deletes the pets.
        //Google can be a bit "eventually" in this instance when you then list which pets are in the project.
        case t: GoogleJsonResponseException if t.getStatusCode == 404 => ()
        case t: HttpResponseException if t.getStatusCode == 404 => ()
      }
    }
  }

  protected def petGoogleRq[T](op: AbstractGoogleClientRequest[T]) = {
    retryForPetDeletion(() => executeGoogleRequest(op))
  }

  protected def cleanupPets(projectName: String): Future[Unit] = {
    for {
      serviceAccounts <- googleRq( iam.projects().serviceAccounts().list(gProjectPath(projectName)) )
      pets = googNull(serviceAccounts.getAccounts).filter(_.getEmail.contains(s"@$projectName.iam.gserviceaccount.com"))
      _ <- sequentially(pets) { pet => petGoogleRq( iam.projects.serviceAccounts.delete(pet.getName) ) }
    } yield {
      //nah
    }
  }

  // Leonardo currently only creates clusters in the region us-central1. If it were to start supporting other regions, this should be updated.
  def cleanupClusters(projectName: String): Future[Unit] =
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
    } yield {}

  // Leonardo currently only created GCE VMs in zone us-central1-a. If it were to start supporting other zones, this will need to be updated.
  def cleanupVMs(projectName: String): Future[Unit] =
    for {
      result <- googleRq(computeManager.instances().list(projectName, "us-central1-a"))
      instances = googNull(result.getItems)
      instanceNames = instances.map(i => i.getName)
      _ <- sequentially(instanceNames) { instanceName =>
        googleRq(computeManager.instances().delete(projectName, "us-central1-a", instanceName))
        googleRq(computeManager.instances().delete(projectName, "us-central1-a", instanceName))
          //.recover {
          // ignore errors on already deleting clusters
          //case ge: GoogleJsonResponseException if ge.getStatusCode == 400 && ge.getMessage.contains("while it has other pending delete operations") =>
        //}
      }
    } yield {}


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
