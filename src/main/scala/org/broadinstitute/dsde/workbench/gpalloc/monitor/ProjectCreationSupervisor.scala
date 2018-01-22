package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{DataAccess, DbReference}
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.{CreateProject, ProjectCreationSupervisorMessage}

import scala.concurrent.Future
import scala.concurrent.duration._

object ProjectCreationSupervisor {
  sealed trait ProjectCreationSupervisorMessage
  case class CreateProject(projectName: String) extends ProjectCreationSupervisorMessage

  def props(dbRef: DbReference,
            googleDAO: HttpGoogleBillingDAO,
            pollInterval: FiniteDuration = 1 minutes): Props = {
    Props(new ProjectCreationSupervisor(dbRef, googleDAO, pollInterval))
  }
}

class ProjectCreationSupervisor(dbRef: DbReference, googleDAO: HttpGoogleBillingDAO, pollInterval: FiniteDuration)
  extends Actor
  with LazyLogging {

  import context._

  override def receive = {
    case CreateProject(projectName) =>
      createProject(projectName)
  }

  def rescanAllProjects() = {
    //TODO: make new monitors foreach project being tracked and send them WakeUp
  }

  def createProject(projectName: String): Unit = {
    val newProjectMonitor = actorOf(ProjectCreationMonitor.props(projectName, dbRef, googleDAO), projectName)
    newProjectMonitor ! ProjectCreationMonitor.CreateProject
  }
}
