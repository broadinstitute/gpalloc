package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorRef, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy}
import akka.contrib.throttle.TimerBasedThrottler
import akka.contrib.throttle.Throttler.{RateInt, SetTarget}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.config.GPAllocConfig
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, HttpGoogleBillingDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor._
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService
import org.broadinstitute.dsde.workbench.gpalloc.util.Throttler

import scala.concurrent.Future
import scala.concurrent.duration._

object ProjectCreationSupervisor {
  sealed trait ProjectCreationSupervisorMessage
  case class RequestNewProject(projectName: String) extends ProjectCreationSupervisorMessage
  case object ResumeAllProjects extends ProjectCreationSupervisorMessage
  case class RegisterGPAllocService(service: GPAllocService) extends ProjectCreationSupervisorMessage
  case object SweepAbandonedProjects extends ProjectCreationSupervisorMessage

  //secret message that only we send to ourselves
  protected[monitor] case class CreateProject(projectName: String) extends ProjectCreationSupervisorMessage

  def props(billingAccount: String,
            dbRef: DbReference,
            googleDAO: GoogleDAO,
            gpAllocConfig: GPAllocConfig): Props = {
    Props(new ProjectCreationSupervisor(billingAccount, dbRef, googleDAO, gpAllocConfig))
  }
}

class ProjectCreationSupervisor(billingAccount: String, dbRef: DbReference, googleDAO: GoogleDAO, gpAllocConfig: GPAllocConfig)
  extends Actor
  with LazyLogging {

  import context._

  /* As soon as a project creation request comes in, it's saved to the db and set to Queued.
   * Then it goes into this throttle. Once it emerges from the throttle, it will actually be created.
   */
  val projectCreationThrottler = new Throttler(context, gpAllocConfig.projectsThrottle, gpAllocConfig.projectsThrottlePerDuration, "ProjectCreation")

  var gpAlloc: GPAllocService = _

  override def receive: PartialFunction[Any, Unit] = {
    case RequestNewProject(projectName) =>
      requestNewProject(projectName)
    case CreateProject(projectName) =>
      createProject(projectName)
    case ResumeAllProjects =>
      resumeAllProjects
    case RegisterGPAllocService(service) =>
      gpAlloc = service
      self ! SweepAbandonedProjects
    case SweepAbandonedProjects =>
      sweepAssignedProjects()
  }

  //if a project creation monitor dies, restart it
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() { case _ => Restart }

  val monitorNameBase = "bpmon-"
  def monitorName(bp: String) = s"$monitorNameBase$bp"

  def resumeAllProjects: Future[Unit] = {
    dbRef.inTransaction { da =>
      for {
        queuedProjects <- da.billingProjectQuery.getQueuedProjects
        creatingProjects <- da.billingProjectQuery.getCreatingProjects
      } yield {
        //Queued projects go back in the throttle, since they never made it to Google land.
        queuedProjects.foreach(qp => addNewProjectToThrottle(qp.billingProjectName))

        //Creating projects did make it to Google land, so they get monitor actors made for them.
        creatingProjects.foreach { cp =>
          val newProjectMonitor = createChildActor(cp.billingProjectName)
          newProjectMonitor ! ProjectCreationMonitor.WakeUp
        }
      }
    }
  }

  def sweepAssignedProjects(): Unit = {
    gpAlloc.releaseAbandonedProjects()
    system.scheduler.scheduleOnce(gpAllocConfig.abandonmentSweepInterval, self, SweepAbandonedProjects)
  }

  def requestNewProject(projectName: String): Future[Unit] = {
    //telling the db immediately means we can get an accurate handle on the number projects that are in flight.
    //otherwise things in the throttle queue are invisible to us, and we create the wrong number of projects.
    dbRef.inTransaction { da =>
      da.billingProjectQuery.saveNew(projectName, BillingProjectStatus.Queued)
    } flatMap { _ =>
      addNewProjectToThrottle(projectName)
    }
  }

  def addNewProjectToThrottle(projectName: String): Future[Unit] = {
    logger.info(s"Request to create $projectName goes into the throttle.")
    projectCreationThrottler.throttle( () => Future.successful(createProject(projectName)) )
  }

  def createProject(projectName: String): Unit = {
    val newProjectMonitor = createChildActor(projectName)
    newProjectMonitor ! ProjectCreationMonitor.CreateProject
  }

  def createChildActor(projectName: String): ActorRef = {
    //use context.actorOf so we create children that will be killed if we get PoisonPilled
    context.actorOf(ProjectCreationMonitor.props(projectName, billingAccount, dbRef, googleDAO, gpAllocConfig), monitorName(projectName))
  }

  //TODO: hook this up. drop the database, optionally delete the projects
  def stopMonitoringEverything(): Unit = {
    system.actorSelection( self.path / s"$monitorNameBase*" ) ! PoisonPill
  }
}
