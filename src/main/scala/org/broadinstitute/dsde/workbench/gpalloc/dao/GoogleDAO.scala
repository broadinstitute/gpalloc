package org.broadinstitute.dsde.workbench.gpalloc.dao

import org.broadinstitute.dsde.workbench.gpalloc.db.ActiveOperationRecord

import scala.concurrent.Future

abstract class GoogleDAO {
  def transferProjectOwnership(project: String, owner: String): Future[String]
  def scrubBillingProject(projectName: String): Future[Unit]

  def pollOperation(operation: ActiveOperationRecord): Future[ActiveOperationRecord]

  def createProject(projectName: String, billingAccount: String): Future[ActiveOperationRecord]
  def enableCloudServices(projectName: String, billingAccount: String): Future[Seq[ActiveOperationRecord]]
  def setupProjectBucketAccess(projectName: String): Future[Unit]
}
