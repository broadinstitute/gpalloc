package org.broadinstitute.dsde.workbench.gpalloc

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.workbench.gpalloc.dao.MockGoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{ActiveOperationRecord, BillingProjectRecord, DbReference, DbSingleton}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.util.NoopActor
import org.scalatest.concurrent.ScalaFutures

import scala.util.Random

trait CommonTestData { this: ScalaFutures =>
  val newProjectName = "new-test-project"
  val newProjectName2 = "new-test-project2"
  val requestingUser = "user@example.com"

  val userInfo = UserInfo(OAuth2BearerToken("ya29.xxxxxx"), WorkbenchUserId("1234567890"), WorkbenchEmail(requestingUser), 60)

  val dbRef = DbSingleton.ref

  def freshBillingProjectRecord(projectName: String) = {
    BillingProjectRecord(projectName, None, BillingProjectStatus.CreatingProject.toString)
  }

  def freshOpRecord(projectName: String) = {
    val random = Random.alphanumeric.take(5).mkString
    ActiveOperationRecord(projectName, BillingProjectStatus.CreatingProject.toString, s"opid-$random", done = false, None)
  }
}
