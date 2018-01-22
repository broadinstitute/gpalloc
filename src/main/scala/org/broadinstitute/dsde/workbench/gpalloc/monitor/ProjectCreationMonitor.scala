package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{DataAccess, DbReference}
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationMonitor._

import scala.concurrent.Future
import scala.concurrent.duration._

object ProjectCreationMonitor {
  sealed trait ProjectCreationMonitorMessage
  case object WakeUp extends ProjectCreationMonitorMessage
  case object CreateProject extends ProjectCreationMonitorMessage

  def props(projectName: String,
            dbRef: DbReference,
            googleDAO: HttpGoogleBillingDAO): Props = {
    Props(new ProjectCreationMonitor(projectName, dbRef, googleDAO))
  }
}

class ProjectCreationMonitor(projectName: String, dbRef: DbReference, googleDAO: HttpGoogleBillingDAO)
  extends Actor
  with LazyLogging {

  import context._

  override def receive = {
    case WakeUp =>
      findInflightProject pipeTo self
    case CreateProject =>
      createNewProject pipeTo self
  }

  def findInflightProject: Future[ProjectCreationMonitorMessage] = {
    dbRef.inTransaction { da => da.billingProjectQuery.getBillingProject(projectName) } flatMap {
      case Some(bp) => pollForStatus()
      case None => Future.successful(CreateProject)
    }
  }

  def pollForStatus(): Future[ProjectCreationMonitorMessage] = {
    dbRef.inTransaction { da =>
      da.operationQuery.getActiveOperations()
    }
  }

  def createNewProject: Future[ProjectCreationMonitorMessage] = {
    //googleDAO.makeIt
    //db.updateToPhase1
    //akka.scheduleNextMsg(poll)
  }
}