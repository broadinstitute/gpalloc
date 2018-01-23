package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{ActiveOperationRecord, DataAccess, DbReference}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationMonitor._
import org.broadinstitute.dsde.workbench.model.WorkbenchException
import slick.dbio.DBIO

import scala.concurrent.Future
import scala.concurrent.duration._

object ProjectCreationMonitor {
  sealed trait ProjectCreationMonitorMessage
  case object WakeUp extends ProjectCreationMonitorMessage
  case object CreateProject extends ProjectCreationMonitorMessage
  case object EnableServices extends ProjectCreationMonitorMessage
  case object CompleteSetup extends ProjectCreationMonitorMessage
  case class PollForStatus(status: BillingProjectStatus) extends ProjectCreationMonitorMessage
  case class ScheduleNextPoll(status: BillingProjectStatus) extends ProjectCreationMonitorMessage
  case class Fail(failedOps: Seq[ActiveOperationRecord]) extends ProjectCreationMonitorMessage
  case object Success extends ProjectCreationMonitorMessage

  def props(projectName: String,
            billingAccount: String,
            dbRef: DbReference,
            googleDAO: HttpGoogleBillingDAO,
            pollInterval: FiniteDuration): Props = {
    Props(new ProjectCreationMonitor(projectName, billingAccount, dbRef, googleDAO, pollInterval))
  }
}

class ProjectCreationMonitor(projectName: String,
                             billingAccount: String,
                             dbRef: DbReference,
                             googleDAO: HttpGoogleBillingDAO,
                             pollInterval: FiniteDuration)
  extends Actor
  with LazyLogging {

  import context._

  override def receive = {
    case WakeUp =>
      resumeInflightProject pipeTo self
    case CreateProject =>
      createNewProject pipeTo self
    case EnableServices =>
      enableServices pipeTo self
    case CompleteSetup =>
      completeSetup pipeTo self
    case PollForStatus(status) =>
      pollForStatus(status) pipeTo self

    case ScheduleNextPoll(status) => scheduleNextPoll(status)
    case Fail(failedOps) => stop(self)
    case Success => stop(self)
  }

  def scheduleNextPoll(status: BillingProjectStatus) = {
    context.system.scheduler.scheduleOnce(pollInterval, self, PollForStatus(status))
  }

  def resumeInflightProject: Future[ProjectCreationMonitorMessage] = {
    dbRef.inTransaction { da => da.billingProjectQuery.getBillingProject(projectName) } map {
      case Some(bp) => PollForStatus(BillingProjectStatus.withNameIgnoreCase(bp.status))
      case None => throw new WorkbenchException(s"ProjectCreationMonitor asked to find missing project $projectName")
    }
  }

  def createNewProject: Future[ProjectCreationMonitorMessage] = {
    for {
      newOperationRec <- googleDAO.createProject(projectName, billingAccount)
      _ <- dbRef.inTransaction { da => da.billingProjectQuery.saveNewProject(projectName, newOperationRec) }
    } yield {
      ScheduleNextPoll(CreatingProject)
    }
  }

  def enableServices: Future[ProjectCreationMonitorMessage] = {
    for {
      serviceOps <- googleDAO.enableCloudServices(projectName, billingAccount)
      _ <- dbRef.inTransaction { da => DBIO.seq(
          da.billingProjectQuery.updateStatus(projectName, EnablingServices),
          da.operationQuery.saveNewOperations(serviceOps)) }
    } yield {
      ScheduleNextPoll(EnablingServices)
    }
  }

  def completeSetup: Future[ProjectCreationMonitorMessage] = {
    for {
      _ <- googleDAO.setupProjectBucketAccess(projectName)
      _ <- dbRef.inTransaction { da => da.billingProjectQuery.updateStatus(projectName, Unassigned) }
    } yield {
      Success
    }
  }

  //checks Google for status of active operations and figures out what next
  def pollForStatus(status: BillingProjectStatus): Future[ProjectCreationMonitorMessage] = {
    val updatedOpsF = for {
      //get ops in progress
      activeOpMap <- dbRef.inTransaction { da => da.operationQuery.getActiveOperationsByType(projectName) }
      activeCurrentStatusOps = activeOpMap(status.toString)
      //ask google
      updatedOps <- Future.traverse(activeCurrentStatusOps.filter(!_.done)) { op => googleDAO.pollOperation(op) }
      //update the db with new op status
      _ <- dbRef.inTransaction { da => da.operationQuery.updateOperations(updatedOps) }
    } yield {
      updatedOps
    }

    //now we have some some updated ops; decide how to move forward
    //note that we only need to check
    updatedOpsF map { ops =>
      if( ops.exists(_.errorMessage.isDefined) ) {
        //fail-fast
        Fail(ops.filter(_.errorMessage.isDefined))
      } else if ( ops.forall(_.done) ){
        //all done!
        getNextStatusMessage(status)
      } else {
        //not done yet; schedule next poll
        ScheduleNextPoll(status)
      }
    }
  }

  def getNextStatusMessage(status: BillingProjectStatus.BillingProjectStatus): ProjectCreationMonitorMessage = {
    status match {
      case CreatingProject => EnableServices
      case EnablingServices => CompleteSetup
      case _ => throw new WorkbenchException("what the")
    }
  }
}
