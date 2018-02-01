package org.broadinstitute.dsde.workbench.gpalloc

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.workbench.gpalloc.dao.MockGoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{ActiveOperationRecord, BillingProjectRecord, DbReference, DbSingleton}
import org.broadinstitute.dsde.workbench.gpalloc.model.{AssignedProject, BillingProjectStatus}
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.util.NoopActor
import org.scalatest.concurrent.ScalaFutures

import scala.util.Random

trait CommonTestData { this: ScalaFutures =>
  val testBillingAccount = "test-billing-account"
  val newProjectName = "new-test-project"
  val newProjectName2 = "new-test-project2"
  val requestingUser = "user@example.com"
  val badUser = "evil@villainy.com"

  val userInfo = UserInfo(OAuth2BearerToken("ya29.xxxxxx"), WorkbenchUserId("1234567890"), WorkbenchEmail(requestingUser), 60)
  val badUserInfo = UserInfo(OAuth2BearerToken("ya29.bwahaha"), WorkbenchUserId("0000000000"), WorkbenchEmail(badUser), 60)

  val dbRef = DbSingleton.ref

  def freshBillingProjectRecord(projectName: String): BillingProjectRecord = {
    BillingProjectRecord(projectName, None, BillingProjectStatus.CreatingProject)
  }

  def freshOpRecord(projectName: String): ActiveOperationRecord = {
    val random = Random.alphanumeric.take(5).mkString
    ActiveOperationRecord(projectName, BillingProjectStatus.CreatingProject, s"opid-$random", done = false, None)
  }

  def toAssignedProject(projectName: String): AssignedProject = {
    AssignedProject(projectName,s"cromwell-bucket-$projectName")
  }
}
