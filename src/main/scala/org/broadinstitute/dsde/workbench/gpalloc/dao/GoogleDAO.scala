package org.broadinstitute.dsde.workbench.gpalloc.dao

import org.broadinstitute.dsde.workbench.gpalloc.db.ActiveOperationRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.AssignedProject
import org.broadinstitute.dsde.workbench.gpalloc.util.Throttler

import scala.concurrent.Future

abstract class GoogleDAO {
  def transferProjectOwnership(project: String, owner: String): Future[AssignedProject]
  def scrubBillingProject(projectName: String): Future[Unit]

  def createProject(projectName: String, billingAccountId: String): Future[ActiveOperationRecord]
  def pollOperation(operation: ActiveOperationRecord): Future[ActiveOperationRecord]
  def cleanupDeployment(projectName: String): Unit

  def deleteProject(projectName: String): Future[Unit]
}
