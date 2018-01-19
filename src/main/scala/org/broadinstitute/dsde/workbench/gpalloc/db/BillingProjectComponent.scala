package org.broadinstitute.dsde.workbench.gpalloc.db

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

import cats.implicits._
import org.broadinstitute.dsde.workbench.google.gcs.GcsPath
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import slick.dbio.Effect
import slick.sql.FixedSqlAction

case class BillingProjectRecord(id: Long,
                                billingProjectName: String,
                                owner: Option[String])

trait BillingProjectComponent extends GPAllocComponent {

  import profile.api._

  class BillingProjectTable(tag: Tag) extends Table[BillingProjectRecord](tag, "CLUSTER") {
    def id =                          column[Long]              ("id",                    O.PrimaryKey, O.AutoInc)
    def billingProjectName =          column[String]            ("billingProjectName",    O.Length(254))
    def owner =                       column[Option[String]]    ("owner",                 O.Length(254))

    def uniqueKey = index("BILLING_PROJECT_NAME", billingProjectName, unique = true)

    def * = (id, billingProjectName, owner) <> (BillingProjectRecord.tupled, BillingProjectRecord.unapply)
  }

  object billingProjectQuery extends TableQuery(new BillingProjectTable(_)) {

    def saveNew(billingProject: String): DBIO[String] = {
      (billingProjectQuery returning billingProjectQuery.map(_.id) += BillingProjectRecord(0, billingProject, None)) map { _ =>
        billingProject
      }
    }

    def assignPooledBillingProject(owner: String): DBIO[Option[BillingProjectRecord]] = {
      val freeBillingProject = billingProjectQuery.filter(_.owner.isEmpty).take(1).forUpdate
      freeBillingProject.result flatMap { bps: Seq[BillingProjectRecord] =>
        bps.headOption match {
          case Some(bp) => freeBillingProject.map(aa => aa.owner).update(Some(owner)) map { _ => Some(bp) }
          case None => DBIO.successful(None)
        }
      }
    }

    def reclaimProject(project: String): DBIO[Unit] = {
      billingProjectQuery.filter(_.billingProjectName === project).map(_.owner).update(None).map { _ => () }
    }

  }
}
