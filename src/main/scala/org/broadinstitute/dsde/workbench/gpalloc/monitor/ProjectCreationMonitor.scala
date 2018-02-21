package org.broadinstitute.dsde.workbench.gpalloc.monitor

import java.io.{PrintWriter, StringWriter}

import akka.actor.Status.Failure
import akka.actor.{Actor, Cancellable, Props}
import akka.contrib.throttle.TimerBasedThrottler
import akka.contrib.throttle.Throttler.{RateInt, SetTarget}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.config.GPAllocConfig
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, HttpGoogleBillingDAO}
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

  case class GoogleThrottleOp(googleOp: ActiveOperationRecord) extends ProjectCreationMonitorMessage

  case object Success extends ProjectCreationMonitorMessage

  def props(projectName: String,
            billingAccount: String,
            dbRef: DbReference,
            googleDAO: GoogleDAO,
            gpAllocConfig: GPAllocConfig): Props = {
    Props(new ProjectCreationMonitor(projectName, billingAccount, dbRef, googleDAO, gpAllocConfig))
  }
}

class ProjectCreationMonitor(projectName: String,
                             billingAccount: String,
                             dbRef: DbReference,
                             googleDAO: GoogleDAO,
                             gpAllocConfig: GPAllocConfig)
  extends Actor
  with LazyLogging {

  import context._

  //yes this is deprecated. no i'm not going to move to akka streams
  //this is for GCP ratelimit, which is 10 do-things-to-projects ops per second
  val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], gpAllocConfig.opsPerSecondThrottle msgsPer 1.second))
  throttler ! SetTarget(Some(self))

  override def receive: PartialFunction[Any, Unit] = {
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

    //special message we use to throttle ourselves
    case GoogleThrottleOp(googleOp) =>
      googleThrottleOp(googleOp) pipeTo sender

    case ScheduleNextPoll(status) => scheduleNextPoll(status)

    //stop because project creation completed successfully
    case Success => stop(self)

    //stop because google said an operation failed
    case Fail(failedOps) =>
      logger.error(s"Creation of new project $projectName failed. These opids died: ${failedOps.map(op => s"id: ${op.operationId}, error: ${op.errorMessage}").mkString(", ")}")
      stop(self)

    //stop because something (probably google polling) throw an exception
    case Failure(throwable) =>
      val stackTrace = new StringWriter
      throwable.printStackTrace(new PrintWriter(new StringWriter))
      logger.error(s"Creation of new project $projectName failed because of an exception: ${throwable.getMessage} \n${stackTrace.toString}")
      stop(self)
  }

  def scheduleNextPoll(status: BillingProjectStatus): Unit = {
    context.system.scheduler.scheduleOnce(gpAllocConfig.projectMonitorPollInterval, self, PollForStatus(status))
  }

  def resumeInflightProject: Future[ProjectCreationMonitorMessage] = {
    dbRef.inTransaction { da => da.billingProjectQuery.getBillingProject(projectName) } map {
      case Some(bp) =>
        logger.info(s"ProjectCreationMonitor.resumeInflightProject $projectName ${bp.status}")
        PollForStatus(bp.status)
      case None =>
        throw new WorkbenchException(s"ProjectCreationMonitor asked to find missing project $projectName")
    }
  }

  def createNewProject: Future[ProjectCreationMonitorMessage] = {
    logger.info(s"ProjectCreationMonitor.createNewProject $projectName")
    for {
      newOperationRec <- googleDAO.createProject(projectName, billingAccount)
      _ <- dbRef.inTransaction { da => da.billingProjectQuery.saveNewProject(projectName, newOperationRec) }
    } yield {
      PollForStatus(CreatingProject)
    }
  }

  def enableServices: Future[ProjectCreationMonitorMessage] = {
    logger.info(s"ProjectCreationMonitor.enableServices $projectName")
    for {
      serviceOps <- googleDAO.enableCloudServices(projectName, billingAccount)
      _ <- dbRef.inTransaction { da => DBIO.seq(
          da.billingProjectQuery.updateStatus(projectName, EnablingServices),
          da.operationQuery.saveNewOperations(serviceOps)) }
    } yield {
      PollForStatus(EnablingServices)
    }
  }

  def completeSetup: Future[ProjectCreationMonitorMessage] = {
    logger.info(s"ProjectCreationMonitor.completeSetup $projectName")
    for {
      _ <- googleDAO.setupProjectBucketAccess(projectName)
      _ <- dbRef.inTransaction { da => da.billingProjectQuery.updateStatus(projectName, Unassigned) }
    } yield {
      Success
    }
  }

  //checks Google for status of active operations and figures out what next
  def pollForStatus(status: BillingProjectStatus): Future[ProjectCreationMonitorMessage] = {
    //timeout for self ? GoogleThrottleOp
    implicit val timeout = Timeout(5 seconds)

    logger.info(s"ProjectCreationMonitor.pollForStatus $projectName $status")
    val updatedOpsF = for {
      //get ops in progress
      activeOpMap <- dbRef.inTransaction { da => da.operationQuery.getActiveOperationsByType(projectName) }
      activeCurrentStatusOps = activeOpMap.getOrElse(status, Seq())
      //ask google, but throttle ourselves
      updatedOpsAny <- Future.traverse(activeCurrentStatusOps.filter(!_.done)) { op => throttler ? GoogleThrottleOp(op) }
      updatedOps = updatedOpsAny.map(_.asInstanceOf[ActiveOperationRecord])
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

  def googleThrottleOp(op: ActiveOperationRecord): Future[ActiveOperationRecord] = {
    googleDAO.pollOperation(op)
  }

  def getNextStatusMessage(status: BillingProjectStatus.BillingProjectStatus): ProjectCreationMonitorMessage = {
    status match {
      case CreatingProject => EnableServices
      case EnablingServices => CompleteSetup
      case _ => throw new WorkbenchException(s"ProjectCreationMonitor for $projectName called getNextStatusMessage with surprising status $status")
    }
  }
}
