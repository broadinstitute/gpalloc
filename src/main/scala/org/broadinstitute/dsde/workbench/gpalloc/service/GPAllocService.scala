package org.broadinstitute.dsde.workbench.gpalloc.service

import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.config.{GPAllocConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.gpalloc.dao.GoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{BillingProjectRecord, DbReference, RacyProjectsException}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.{AssignedProject, GPAllocException}
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.{RegisterGPAllocService, RequestNewProject}
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

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
                    (implicit val executionContext: ExecutionContext) extends LazyLogging {

  //on creation, tell the supervisor we exist
  projectCreationSupervisor ! RegisterGPAllocService(this)

  //on startup, flesh out to the minimum number of projects
  maybeCreateNewProjects()

  def requestGoogleProject(userInfo: UserInfo): Future[AssignedProject] = {
    val newProject = assignProjectFromPool(userInfo.userEmail) flatMap {
      case Some(projectName) =>
        val xferFuture = googleBillingDAO.transferProjectOwnership(projectName, userInfo.userEmail.value)
        logger.info(s"assigned project $projectName to ${userInfo.userEmail.value}")
        xferFuture
      case None => throw NoGoogleProjectAvailable()
    }
    newProject onComplete { _ => maybeCreateNewProjects() }
    newProject
  }

  //assigns a project from the pool
  protected def assignProjectFromPool(userEmail: WorkbenchEmail): Future[Option[String]] = {
    dbRef.inTransaction {
      dataAccess => dataAccess.billingProjectQuery.assignProjectFromPool(userEmail.value)
    } recoverWith {
      //it's possible that access to the database will race and another project will steal the bp intended for us
      //in this case, retrying should fix the problem. like good functional programmers, we achieve this using recursion
      case RacyProjectsException => assignProjectFromPool(userEmail)
    }
  }

  def releaseGoogleProject(userEmail: WorkbenchEmail, project: String, becauseAbandoned: Boolean = false): Future[Unit] = {
    logger.info(s"got release request from ${userEmail.value} of $project")
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
        val cleanup = for {
          overPetLimit <- overPetLimitOrProjectDeleted(project)
          _ <- if (overPetLimit) nukeProject(project) else releaseProjectInternal(project)
        } yield {
          logger.info(s"successfully ${ if(overPetLimit) "nuked" else "released" } ${ if(becauseAbandoned) "abandoned" else "" } project $project")
        }
        cleanup.onComplete {
          case Failure(e) => logger.error(s"releaseGoogleProject failed for $project", e)
          case Success(_) => //meh
        }
      case Failure(e) =>
        logger.error(s"failed to release $project because $e")
    }
    authCheck
  }

  def dumpState(): Future[Seq[BillingProjectRecord]] = {
    dbRef.inTransaction { da =>
      da.billingProjectQuery.listEverything()
    }
  }

  def dumpStats(): Future[Map[BillingProjectStatus, Int]] = {
    dbRef.inTransaction { da =>
      da.billingProjectQuery.statusStats()
    }
  }

  def releaseAbandonedProjects(): Future[Unit] = {
    for {
      abandonedProjects <- dbRef.inTransaction { da => da.billingProjectQuery.getAbandonedProjects(gpAllocConfig.abandonmentTime) }
      _ <- Future.traverse(abandonedProjects) { p => releaseGoogleProject(WorkbenchEmail(p.owner.get), p.billingProjectName, becauseAbandoned = true) }
    } yield {
      //meh
    }
  }

  def forceCleanup(project: String): Future[Unit] = {
    logger.info(s"attempting forceCleanup of $project")
    val cleanup = for {
      //the below future will fail if the project is already assigned to someone else.
      //that's okay -- we don't want to clean it up in that case.
      _ <- dbRef.inTransaction { da => da.billingProjectQuery.maybeRacyAssignProjectToOwner("gpalloc@cleaning.up", project) }
      overPetLimit <- overPetLimitOrProjectDeleted(project)
      _ <- if (overPetLimit) nukeProject(project) else releaseProjectInternal(project)
    } yield ()
    cleanup.onComplete {
      case Failure(RacyProjectsException) => logger.info(s"forceCleanup of $project because someone owns it")
      case Failure(e) => logger.error(s"surprise error forcing cleanup of $project because $e")
      case Success(_) => logger.info(s"successful forceCleanup of $project")
    }
    cleanup
  }

  private def overPetLimitOrProjectDeleted(project: String): Future[Boolean] = {
    googleBillingDAO.overPetLimit(project).recover {
      case t: GoogleJsonResponseException if t.getStatusCode == 404 => {
        logger.info(s"Could not locate $project in Google, it should be nuked")
        true
      }
    }
  }

  private def releaseProjectInternal(project: String): Future[Unit] = {
    for {
      _ <- googleBillingDAO.scrubBillingProject(project)
      _ <- dbRef.inTransaction { dataAccess => dataAccess.billingProjectQuery.releaseProject(project) }
    } yield ()
  }

  def forceCleanupAll(): Future[Unit] = {
    for {
      projects <- dbRef.inTransaction { da => da.billingProjectQuery.getUnassignedProjects }
      _ <- Future.traverse(projects) { project => forceCleanup(project.billingProjectName) }
    } yield ()
    //run the above future in the background because it's gonna take a while
    Future.successful(())
  }

  def nukeProject(project: String, deleteInGoogle: Boolean = true): Future[Unit] = {
    val nuke = nukeProjectInternal(project, deleteInGoogle)
    nuke.onComplete {
      case Failure(e) => logger.error(s"surprise error nuking project $project because $e")
      case Success(_) => {
        logger.info(s"successful nukeProject of $project")
        maybeCreateNewProjects()
      }
    }
    nuke
  }

  private def nukeProjectInternal(project: String, deleteInGoogle:Boolean = true): Future[Unit] = {
    logger.info(s"attempting nuke of project $project")
    for {
      _ <- dbRef.inTransaction { da => da.billingProjectQuery.deleteProject(project) }
      _ <- if(deleteInGoogle) googleBillingDAO.deleteProject(project) else Future.successful(())
    } yield ()
  }

  def nukeAllProjects(deleteInGoogle: Boolean = true): Future[Unit] = {
    logger.info("nuking all projects")
    val nukeAll = for {
      allProjects <- dbRef.inTransaction { da => da.billingProjectQuery.listEverything() }
      _ <- Future.traverse(allProjects) { rec => nukeProjectInternal(rec.billingProjectName, deleteInGoogle) }
    } yield ()
    nukeAll.onComplete {
      case _ => {
        logger.info(s"successful nukeAll. Creating projects back up to the minimum.")
        maybeCreateNewProjects()
      }
    }
    //run the above future in the background because it's gonna take a while
    Future.successful(())
  }

  //create new google project if we don't have any available
  protected def maybeCreateNewProjects(): Unit = {
    dbRef.inTransaction { da =>
      for {
        free <- da.billingProjectQuery.countUnassignedAndFutureProjects
        all <- da.billingProjectQuery.countAllProjects
      } yield {
        //the number of projects we need to make
        Math.max(gpAllocConfig.minimumFreeProjects - free, gpAllocConfig.minimumProjects - all)
      }
    } map {
      case count if count > 0 =>
        (1 to count) foreach { _ =>
          createNewGoogleProject()
        }
      case _ => //do nothing
    }
  }

  def createNewGoogleProject(): Unit = {
    projectCreationSupervisor ! RequestNewProject
  }
}
