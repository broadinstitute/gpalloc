package org.broadinstitute.dsde.workbench.gpalloc.monitor

import java.io.{PrintWriter, StringWriter}

import akka.actor.Status.Failure
import akka.actor.{Actor, Props}
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.config.GPAllocConfig
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, HttpGoogleBillingDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.{ActiveOperationRecord, DataAccess, DbReference}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationMonitor._
import org.broadinstitute.dsde.workbench.gpalloc.util.Throttler
import org.broadinstitute.dsde.workbench.model.WorkbenchException
import slick.dbio.DBIO

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

object ProjectCreationMonitor {
  sealed trait ProjectCreationMonitorMessage
  case object WakeUp extends ProjectCreationMonitorMessage
  case object CreateProject extends ProjectCreationMonitorMessage
  case object EnableServices extends ProjectCreationMonitorMessage
  case object CompleteSetup extends ProjectCreationMonitorMessage
  case class PollForStatus(status: BillingProjectStatus) extends ProjectCreationMonitorMessage
  case class ScheduleNextPoll(status: BillingProjectStatus, interval: FiniteDuration) extends ProjectCreationMonitorMessage
  case class Fail(failedOps: Seq[ActiveOperationRecord]) extends ProjectCreationMonitorMessage
  case object Success extends ProjectCreationMonitorMessage

  def props(projectName: String,
            billingAccount: String,
            dbRef: DbReference,
            googleDAO: GoogleDAO,
            gpAllocConfig: GPAllocConfig,
            googleOpThrottler: Throttler): Props = {
    Props(new ProjectCreationMonitor(projectName, billingAccount, dbRef, googleDAO, gpAllocConfig, googleOpThrottler))
  }
}

class ProjectCreationMonitor(projectName: String,
                             billingAccount: String,
                             dbRef: DbReference,
                             googleDAO: GoogleDAO,
                             gpAllocConfig: GPAllocConfig,
                             googleOpThrottler: Throttler)
  extends Actor
  with LazyLogging {

  import context._

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

    case ScheduleNextPoll(status, interval) => scheduleNextPoll(status, interval)

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

  def scheduleNextPoll(status: BillingProjectStatus, pollTime: FiniteDuration = gpAllocConfig.projectMonitorPollInterval): Unit = {
    context.system.scheduler.scheduleOnce(pollTime, self, PollForStatus(status))
  }

  def resumeInflightProject: Future[ProjectCreationMonitorMessage] = {
    dbRef.inTransaction { da => da.billingProjectQuery.getBillingProject(projectName) } map {
      case Some(bp) => ScheduleNextPoll(bp.status, FiniteDuration((Random.nextDouble() * gpAllocConfig.projectMonitorPollInterval).toMillis, MILLISECONDS))
      case None => throw new WorkbenchException(s"ProjectCreationMonitor asked to find missing project $projectName")
    }
  }

  def createNewProject: Future[ProjectCreationMonitorMessage] = {
    for {
      newOperationRec <- googleDAO.createProject(projectName, billingAccount)
      _ <- dbRef.inTransaction { da => da.billingProjectQuery.saveNewProject(projectName, newOperationRec) }
    } yield {
      logger.info(s"Create request submitted for $projectName.")
      PollForStatus(CreatingProject)
    }
  }

  def enableServices: Future[ProjectCreationMonitorMessage] = {
    for {
      serviceOps <- googleDAO.enableCloudServices(projectName, billingAccount, googleOpThrottler)
      _ <- dbRef.inTransaction { da => DBIO.seq(
          da.billingProjectQuery.updateStatus(projectName, EnablingServices),
          da.operationQuery.saveNewOperations(serviceOps)) }
    } yield {
      logger.info(s"Asked Google to enable services for $projectName.")
      PollForStatus(EnablingServices)
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
      activeCurrentStatusOps = activeOpMap.getOrElse(status, Seq())
      //ask google
      updatedOps <- Future.traverse(activeCurrentStatusOps.filter(!_.done)) { op => googleDAO.pollOperation(op, googleOpThrottler) }
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
        logger.info(s"$projectName completed $status")
        getNextStatusMessage(status)
      } else {
        //not done yet; schedule next poll
        ScheduleNextPoll(status, gpAllocConfig.projectMonitorPollInterval)
      }
    }
  }

  def getNextStatusMessage(status: BillingProjectStatus.BillingProjectStatus): ProjectCreationMonitorMessage = {
    status match {
      case CreatingProject => EnableServices
      case EnablingServices => CompleteSetup
      case _ => throw new WorkbenchException(s"ProjectCreationMonitor for $projectName called getNextStatusMessage with surprising status $status")
    }
  }
}
