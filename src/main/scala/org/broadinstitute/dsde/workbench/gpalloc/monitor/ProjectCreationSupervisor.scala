package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{Actor, PoisonPill, Props, SupervisorStrategy}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, HttpGoogleBillingDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor._

import scala.concurrent.Future
import scala.concurrent.duration._

object ProjectCreationSupervisor {
  sealed trait ProjectCreationSupervisorMessage
  case class CreateProject(projectName: String) extends ProjectCreationSupervisorMessage
  case object ResumeAllProjects extends ProjectCreationSupervisorMessage

  def props(billingAccount: String,
            dbRef: DbReference,
            googleDAO: GoogleDAO,
            pollInterval: FiniteDuration = 1 minutes): Props = {
    Props(new ProjectCreationSupervisor(billingAccount, dbRef, googleDAO, pollInterval))
  }
}

class ProjectCreationSupervisor(billingAccount: String, dbRef: DbReference, googleDAO: GoogleDAO, pollInterval: FiniteDuration)
  extends Actor
  with LazyLogging {

  import context._

  override def receive: PartialFunction[Any, Unit] = {
    case CreateProject(projectName) =>
      createProject(projectName)
    case ResumeAllProjects =>
      resumeAllProjects
  }

  //if a project creation monitor dies, give up on it
  override val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  val monitorNameBase = "bpmon-"
  def monitorName(bp: String) = s"$monitorNameBase$bp"

  def resumeAllProjects: Future[Unit] = {
    dbRef.inTransaction { da => da.billingProjectQuery.getCreatingProjects } map { _.foreach { bp =>
      val newProjectMonitor = context.actorOf(ProjectCreationMonitor.props(bp.billingProjectName, billingAccount, dbRef, googleDAO, pollInterval), monitorName(bp.billingProjectName))
      newProjectMonitor ! ProjectCreationMonitor.WakeUp
    }}
  }

  def createProject(projectName: String): Unit = {
    val newProjectMonitor = context.actorOf(ProjectCreationMonitor.props(projectName, billingAccount, dbRef, googleDAO, pollInterval), monitorName(projectName))
    newProjectMonitor ! ProjectCreationMonitor.CreateProject
  }

  //TODO: hook this up. drop the database, optionally delete the projects
  def stopMonitoringEverything(): Unit = {
    system.actorSelection(s"/user/${monitorNameBase}*") ! PoisonPill
  }
}
