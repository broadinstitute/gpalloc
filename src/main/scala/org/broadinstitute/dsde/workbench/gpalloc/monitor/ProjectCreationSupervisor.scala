package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{Actor, ActorRef, PoisonPill, Props, SupervisorStrategy}
import akka.contrib.throttle.TimerBasedThrottler
import akka.contrib.throttle.Throttler.{RateInt, SetTarget}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.config.GPAllocConfig
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, HttpGoogleBillingDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor._
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService

import scala.concurrent.Future
import scala.concurrent.duration._

object ProjectCreationSupervisor {
  sealed trait ProjectCreationSupervisorMessage
  case class RequestNewProject(projectName: String) extends ProjectCreationSupervisorMessage
  case object ResumeAllProjects extends ProjectCreationSupervisorMessage
  case class RegisterGPAllocService(service: GPAllocService) extends ProjectCreationSupervisorMessage
  case object SweepAbandonedProjects extends ProjectCreationSupervisorMessage

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

  //secret message that only we send to ourselves
  protected case class CreateProject(projectName: String) extends ProjectCreationSupervisorMessage

  //yes this is deprecated. no i'm not going to move to akka streams
  val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], gpAllocConfig.projectsPerHourThrottle msgsPer 1.hour))
  throttler ! SetTarget(Some(self))

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

  //if a project creation monitor dies, give up on it
  override val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  val monitorNameBase = "bpmon-"
  def monitorName(bp: String) = s"$monitorNameBase$bp"

  def resumeAllProjects: Future[Unit] = {
    dbRef.inTransaction { da => da.billingProjectQuery.getCreatingProjects } map { _.foreach { bp =>
      val newProjectMonitor = createChildActor(bp.billingProjectName)
      newProjectMonitor ! ProjectCreationMonitor.WakeUp
    }}
  }

  def sweepAssignedProjects(): Unit = {
    gpAlloc.releaseAbandonedProjects()
    system.scheduler.scheduleOnce(gpAllocConfig.abandonmentSweepInterval, self, SweepAbandonedProjects)
  }

  def requestNewProject(projectName: String): Unit = {
    throttler ! CreateProject(projectName)
  }

  def createProject(projectName: String): Unit = {
    val newProjectMonitor = createChildActor(projectName)
    newProjectMonitor ! ProjectCreationMonitor.CreateProject
  }

  def createChildActor(projectName: String): ActorRef = {
    system.actorOf(ProjectCreationMonitor.props(projectName, billingAccount, dbRef, googleDAO, gpAllocConfig.projectMonitorPollInterval), monitorName(projectName))
  }

  //TODO: hook this up. drop the database, optionally delete the projects
  def stopMonitoringEverything(): Unit = {
    system.actorSelection(s"/user/${monitorNameBase}*") ! PoisonPill
  }
}
