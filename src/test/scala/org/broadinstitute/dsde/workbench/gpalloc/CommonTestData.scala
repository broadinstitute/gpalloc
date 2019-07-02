package org.broadinstitute.dsde.workbench.gpalloc

import java.sql.Timestamp
import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.gpalloc.config.{GPAllocConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.gpalloc.dao.MockGoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{ActiveOperationRecord, BillingProjectRecord, DbReference, DbSingleton}
import org.broadinstitute.dsde.workbench.gpalloc.model.{AssignedProject, BillingProjectStatus}
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.util.NoopActor
import org.scalactic.Equality
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Random

object CommonTestData {
  object BillingProjectRecordEquality {
    //custom test equality for billing project records: they're equal if their last assigned times are both defined
    implicit val billingProjectRecordEq: Equality[BillingProjectRecord] =
      new Equality[BillingProjectRecord] {
        def areEqual(a: BillingProjectRecord, b: Any): Boolean =
          b match {
            case bp: BillingProjectRecord =>
              a.billingProjectName == bp.billingProjectName &&
                a.status == bp.status &&
                a.owner == bp.owner &&
                a.lastAssignedTime.isDefined == bp.lastAssignedTime.isDefined
            case _ => false
          }
      }

    //this is necessary because https://github.com/scalatest/scalatest/issues/917
    implicit val optBillingProjectRecordEq: Equality[Option[BillingProjectRecord]] =
      new Equality[Option[BillingProjectRecord]] {
        def areEqual(a: Option[BillingProjectRecord], b: Any): Boolean =
          b match {
            case Some(bp: BillingProjectRecord) =>
              a.isDefined &&
                billingProjectRecordEq.areEqual(a.get, bp)
            case None => a.isEmpty
            case _ => false
          }
      }
  }
}

trait CommonTestData {
  this: ScalaFutures =>

  val testBillingAccount = "test-billing-account"
  val newProjectName = "new-test-project"
  val newProjectName2 = "new-test-project2"
  val newProjectName3 = "new-test-project3"
  val requestingUser = "user@example.com"
  val badUser = "evil@villainy.com"
  val whenever = Some(Timestamp.from(Instant.now()))

  val userInfo = UserInfo(OAuth2BearerToken("ya29.xxxxxx"), WorkbenchUserId("1234567890"), WorkbenchEmail(requestingUser), 60)
  val badUserInfo = UserInfo(OAuth2BearerToken("ya29.bwahaha"), WorkbenchUserId("0000000000"), WorkbenchEmail(badUser), 60)

  val dbRef = DbSingleton.ref

  val config = ConfigFactory.parseResources("gpalloc.conf").withFallback(ConfigFactory.load())
  val swaggerConfig = config.as[SwaggerConfig]("swagger")
  val gpAllocConfig = config.as[GPAllocConfig]("gpalloc")

  def freshBillingProjectRecord(projectName: String): BillingProjectRecord = {
    BillingProjectRecord(projectName, None, BillingProjectStatus.Queued, None)
  }

  def assignedBillingProjectRecord(projectName: String, owner: WorkbenchEmail, ago: FiniteDuration): BillingProjectRecord = {
    BillingProjectRecord(projectName, Some(owner.value), BillingProjectStatus.Assigned, Some(Timestamp.from(Instant.now().minusMillis(ago.toMillis))))
  }

  def freshOpRecord(projectName: String): ActiveOperationRecord = {
    val random = Random.alphanumeric.take(5).mkString
    ActiveOperationRecord(projectName, BillingProjectStatus.CreatingProject, s"opid-$random", done = false, None)
  }

  def toAssignedProject(projectName: String): AssignedProject = {
    AssignedProject(projectName, s"cromwell-bucket-$projectName")
  }
}
