package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{Actor, Props}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
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
            googleDAO: HttpGoogleBillingDAO,
            pollInterval: FiniteDuration = 1 minutes): Props = {
    Props(new ProjectCreationSupervisor(billingAccount, dbRef, googleDAO, pollInterval))
  }
}

class ProjectCreationSupervisor(billingAccount: String, dbRef: DbReference, googleDAO: HttpGoogleBillingDAO, pollInterval: FiniteDuration)
  extends Actor
  with LazyLogging {

  import context._

  override def receive = {
    case CreateProject(projectName) =>
      createProject(projectName)
    case ResumeAllProjects =>
      resumeAllProjects
    //TODO: failure handling
  }

  def resumeAllProjects = {
    dbRef.inTransaction { da => da.billingProjectQuery.getCreatingProjects } map { _.foreach { bp =>
      val newProjectMonitor = actorOf(ProjectCreationMonitor.props(bp.billingProjectName, billingAccount, dbRef, googleDAO), bp.billingProjectName)
      newProjectMonitor ! ProjectCreationMonitor.WakeUp
    }}
  }

  def createProject(projectName: String): Unit = {
    val newProjectMonitor = actorOf(ProjectCreationMonitor.props(projectName, billingAccount, dbRef, googleDAO), projectName)
    newProjectMonitor ! ProjectCreationMonitor.CreateProject
  }
}
