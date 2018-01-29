package org.broadinstitute.dsde.workbench.gpalloc.service

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.model.GPAllocException
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.CreateProject
import org.broadinstitute.dsde.workbench.model.UserInfo

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Success}

case class NoGoogleProjectAvailable()
  extends GPAllocException(s"Sorry, no free google projects. Make your own", StatusCodes.NotFound)

case class NotYourGoogleProject(project: String, requestingUser: String, ownerUser: String)
  extends GPAllocException(s"$requestingUser is not authorized to delete $project; make $ownerUser do it", StatusCodes.Unauthorized)

case class GoogleProjectNotFound(project: String)
  extends GPAllocException(s"$project not found", StatusCodes.NotFound)

class GPAllocService(protected val dbRef: DbReference,
                     projectCreationSupervisor: ActorRef,
                     googleBillingDAO: HttpGoogleBillingDAO)
                    (implicit val executionContext: ExecutionContext) {

  def requestGoogleProject(userInfo: UserInfo): Future[String] = {
    val newProject = dbRef.inTransaction { dataAccess => dataAccess.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) } flatMap {
      case Some(projectName) => googleBillingDAO.transferProjectOwnership(projectName, userInfo.userEmail.value)
      case None => throw NoGoogleProjectAvailable()
    }
    newProject onComplete { _ => maybeCreateNewProject() }
    newProject
  }

  def releaseGoogleProject(userInfo: UserInfo, project: String): Future[Unit] = {
    val authCheck = dbRef.inTransaction { da =>
      da.billingProjectQuery.getBillingProject(project) map {
        case Some(bp) =>
          if( bp.owner.getOrElse("") != userInfo.userEmail.value ) {
            //only assigned projects will have the owner field populated
            throw NotYourGoogleProject(project, userInfo.userEmail.value, bp.owner.getOrElse(""))
          }
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

  //create new google project if we don't have any available
  private def maybeCreateNewProject(): Unit = {
    dbRef.inTransaction { da => da.billingProjectQuery.countUnassignedProjects } map {
      case 0 => createNewGoogleProject()
      case _ => //do nothing
    }
  }

  private def createNewGoogleProject(): Unit = {
    projectCreationSupervisor ! CreateProject(s"gpalloc-${Random.alphanumeric.take(7).mkString}")
  }
}
