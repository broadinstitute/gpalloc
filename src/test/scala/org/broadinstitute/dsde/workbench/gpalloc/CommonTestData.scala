package org.broadinstitute.dsde.workbench.gpalloc

import org.broadinstitute.dsde.workbench.gpalloc.db.BillingProjectRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.scalatest.concurrent.ScalaFutures

trait CommonTestData { this: ScalaFutures =>
  val newProjectName = "new-test-project"
  val newProjectName2 = "new-test-project2"
  val requestingUser = "user@example.com"

  def freshBillingProjectRecord(projectName: String) = {
    BillingProjectRecord(projectName, None, BillingProjectStatus.CreatingProject.toString)
  }
}
