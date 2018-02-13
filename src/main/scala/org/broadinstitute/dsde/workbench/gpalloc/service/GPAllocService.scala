package org.broadinstitute.dsde.workbench.gpalloc.service

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.gpalloc.config.{GPAllocConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, HttpGoogleBillingDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.model.{AssignedProject, GPAllocException}
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.{RequestNewProject, RegisterGPAllocService}
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Success}

case class NoGoogleProjectAvailable()
  extends GPAllocException(s"Sorry, no free google projects. Make your own", StatusCodes.NotFound)

case class NotYourGoogleProject(project: String, requestingUser: String, ownerUser: String)
  extends GPAllocException(s"$requestingUser is not authorized to delete $project; make $ownerUser do it", StatusCodes.Unauthorized)

case class GoogleProjectNotFound(project: String)
  extends GPAllocException(s"$project not found", StatusCodes.NotFound)

class GPAllocService(protected val dbRef: DbReference,
                     protected val swaggerConfig: SwaggerConfig,
                     projectCreationSupervisor: ActorRef,
                     googleBillingDAO: GoogleDAO,
                     gpAllocConfig: GPAllocConfig)
                    (implicit val executionContext: ExecutionContext) {

  //on creation, tell the supervisor we exist
  projectCreationSupervisor ! RegisterGPAllocService(this)

  //on startup, flesh out to the minimum number of projects
  maybeCreateNewProjects()

  def requestGoogleProject(userInfo: UserInfo): Future[AssignedProject] = {
    val newProject = dbRef.inTransaction { dataAccess => dataAccess.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) } flatMap {
      case Some(projectName) => googleBillingDAO.transferProjectOwnership(projectName, userInfo.userEmail.value)
      case None => throw NoGoogleProjectAvailable()
    }
    newProject onComplete { _ => maybeCreateNewProjects() }
    newProject
  }

  def releaseGoogleProject(userEmail: WorkbenchEmail, project: String): Future[Unit] = {
    val authCheck = dbRef.inTransaction { da =>
      da.billingProjectQuery.getAssignedBillingProject(project) map {
        case Some(bp) =>
          //assigned projects will have the owner field populated, but let's be cautious
          if( bp.owner.getOrElse("") != userEmail.value ) {
            throw NotYourGoogleProject(project, userEmail.value, bp.owner.getOrElse(""))
          }
        //we say Not Found for a project that isn't in assigned yet
        case None => throw GoogleProjectNotFound(project)
      }
    }
    authCheck onComplete {
      case Success(_) =>
        //nuke the billing project if no auth failures.
        //onComplete will return the original future, i.e. authCheck, and not wait for onComplete to complete.
        //we're kicking off this work but not monitoring it.
        for {
          _ <- googleBillingDAO.scrubBillingProject(project)
          _ <- dbRef.inTransaction { dataAccess => dataAccess.billingProjectQuery.releaseProject(project) }
        } yield {
          ()
        }
      case _ => //never mind
    }
    authCheck
  }

  def releaseAbandonedProjects(): Future[Unit] = {
    for {
      abandonedProjects <- dbRef.inTransaction { da => da.billingProjectQuery.getAbandonedProjects(gpAllocConfig.abandonmentTime) }
      _ <- Future.traverse(abandonedProjects) { p => releaseGoogleProject(WorkbenchEmail(p.owner.get), p.billingProjectName) }
    } yield {
      //meh
    }
  }

  //create new google project if we don't have any available
  private def maybeCreateNewProjects(): Unit = {
    dbRef.inTransaction { da => da.billingProjectQuery.countUnassignedAndFutureProjects } map {
      case count if count < gpAllocConfig.minimumFreeProjects =>
        (1 to (gpAllocConfig.minimumFreeProjects-count)) foreach { _ =>
          createNewGoogleProject()
        }
      case _ => //do nothing
    }
  }

  private def createNewGoogleProject(): Unit = {
    projectCreationSupervisor ! RequestNewProject(s"${gpAllocConfig.projectPrefix}-${Random.alphanumeric.take(7).mkString.toLowerCase}")
  }
}
