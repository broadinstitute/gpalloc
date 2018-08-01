package org.broadinstitute.dsde.workbench.gpalloc.dao

import org.broadinstitute.dsde.workbench.gpalloc.db.ActiveOperationRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.AssignedProject
import org.broadinstitute.dsde.workbench.gpalloc.util.Throttler

import scala.concurrent.Future

abstract class GoogleDAO {
  def transferProjectOwnership(project: String, owner: String): Future[AssignedProject]
  def scrubBillingProject(projectName: String): Future[Unit]

  def pollOperation(operation: ActiveOperationRecord): Future[ActiveOperationRecord]

  def createProject(projectName: String, billingAccount: String): Future[ActiveOperationRecord]
  def enableCloudServices(projectName: String, billingAccount: String): Future[Seq[ActiveOperationRecord]]
  def setupProjectBucketAccess(projectName: String): Future[Unit]
}
