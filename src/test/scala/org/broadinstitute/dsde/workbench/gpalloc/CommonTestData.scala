package org.broadinstitute.dsde.workbench.gpalloc

import org.broadinstitute.dsde.workbench.gpalloc.db.{ActiveOperationRecord, BillingProjectRecord}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.scalatest.concurrent.ScalaFutures

import scala.util.Random

trait CommonTestData { this: ScalaFutures =>
  val newProjectName = "new-test-project"
  val newProjectName2 = "new-test-project2"
  val requestingUser = "user@example.com"

  def freshBillingProjectRecord(projectName: String) = {
    BillingProjectRecord(projectName, None, BillingProjectStatus.CreatingProject.toString)
  }

  def freshOpRecord(projectName: String) = {
    val random = Random.alphanumeric.take(5).mkString
    ActiveOperationRecord(projectName, BillingProjectStatus.CreatingProject.toString, s"opid-$random", done = false, None)
  }
}
