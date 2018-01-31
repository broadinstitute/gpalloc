package org.broadinstitute.dsde.workbench.gpalloc.dao

import org.broadinstitute.dsde.workbench.gpalloc.db.ActiveOperationRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.{AssignedProject, BillingProjectStatus}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.broadinstitute.dsde.workbench.model.WorkbenchException

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Random

class MockGoogleDAO(operationsReturnError: Boolean = false) extends GoogleDAO {
  val servicesToEnable = Seq("fooService", "barService", "bazService")

  var createdProjects: mutable.Set[String] = mutable.Set.empty[String]
  var enabledProjects: mutable.Set[String] = mutable.Set.empty[String]
  var bucketedProjects: mutable.Set[String] = mutable.Set.empty[String]
  var polledOpIds: mutable.Set[String] = mutable.Set.empty[String]

  protected def randomOpName(opType: Option[String] = None): String =
    Seq(Some("googleOp"), opType, Some(Random.alphanumeric.take(5).mkString)).flatten.mkString("-")

  def transferProjectOwnership(project: String, owner: String): Future[AssignedProject] = {
    Future.successful(AssignedProject(project, s"cromwell-bucket-$project"))
  }

  def scrubBillingProject(projectName: String): Future[Unit] = {
    Future.successful(())
  }

  def pollOperation(operation: ActiveOperationRecord): Future[ActiveOperationRecord] = {
    polledOpIds += operation.operationId
    Future.successful(operation.copy(done=true, errorMessage = if(operationsReturnError) Some("boom") else None))
  }

  def createProject(projectName: String, billingAccount: String): Future[ActiveOperationRecord] = {
    createdProjects += projectName
    Future.successful(ActiveOperationRecord(projectName, CreatingProject, randomOpName(), false, None))
  }

  def enableCloudServices(projectName: String, billingAccount: String): Future[Seq[ActiveOperationRecord]] = {
    enabledProjects += projectName
    Future.successful(servicesToEnable map { svc =>
      ActiveOperationRecord(projectName, EnablingServices, randomOpName(Some(svc)), false, None)
    })
  }

  def setupProjectBucketAccess(projectName: String): Future[Unit] = {
    bucketedProjects += projectName
    Future.successful(())
  }
}
