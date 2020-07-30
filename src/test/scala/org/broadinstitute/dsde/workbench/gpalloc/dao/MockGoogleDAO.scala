package org.broadinstitute.dsde.workbench.gpalloc.dao

import org.broadinstitute.dsde.workbench.gpalloc.db.ActiveOperationRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.{AssignedProject, BillingProjectStatus}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.broadinstitute.dsde.workbench.gpalloc.util.Throttler
import org.broadinstitute.dsde.workbench.model.WorkbenchException

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Random

class MockGoogleDAO(operationsReturnError: Boolean = false, operationsDoneYet: Boolean = true, pollException: Boolean = false) extends GoogleDAO {
  val servicesToEnable = Seq("fooService", "barService", "bazService")

  var createdProjects: mutable.Set[String] = mutable.Set.empty[String]
  var polledOpIds: mutable.Set[String] = mutable.Set.empty[String]
  var scrubbedProjects: mutable.Set[String] = mutable.Set.empty[String]
  var deletedProjects: mutable.Set[String] = mutable.Set.empty[String]

  protected def randomOpName(opType: Option[String] = None): String =
    Seq(Some("googleOp"), opType, Some(Random.alphanumeric.take(5).mkString)).flatten.mkString("-")

  def transferProjectOwnership(project: String, owner: String): Future[AssignedProject] = {
    Future.successful(AssignedProject(project, s"cromwell-bucket-$project"))
  }

  def scrubBillingProject(projectName: String): Future[Unit] = {
    scrubbedProjects += projectName
    Future.successful(())
  }

  def pollOperation(operation: ActiveOperationRecord): Future[ActiveOperationRecord] = {
    if(!pollException) {
      polledOpIds += operation.operationId
      Future.successful(operation.copy(done=operationsDoneYet, errorMessage = if(operationsReturnError) Some("boom") else None))
    } else {
      Future.failed(new RuntimeException("boom"))
    }
  }

  def createProject(projectName: String, billingAccount: String): Future[ActiveOperationRecord] = {
    createdProjects += projectName
    Future.successful(ActiveOperationRecord(projectName, CreatingProject, randomOpName(), done = false, None))
  }

  override def cleanupDeployment(projectName: String): Future[Unit] = Future.successful(())

  override def deleteProject(projectName: String): Future[Unit] = {
    deletedProjects += projectName
    Future.successful(())
  }

  override def overPetLimit(projectName: String): Future[Boolean] = Future.successful(false)
}
