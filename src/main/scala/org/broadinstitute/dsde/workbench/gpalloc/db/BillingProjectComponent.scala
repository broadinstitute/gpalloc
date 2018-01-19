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


    def uniqueKey = index("IDX_CLUSTER_UNIQUE", id, unique = true)

    // Can't use the shorthand
    //   def * = (...) <> (ClusterRecord.tupled, ClusterRecord.unapply)
    // because CLUSTER has more than 22 columns.
    // So we split ClusterRecord into multiple case classes and bind them to slick in the following way.
    def * = (id, billingProjectName, owner) <> (BillingProjectRecord.tupled, BillingProjectRecord.unapply)
  }

  object billingProjectQuery extends TableQuery(new BillingProjectTable(_)) {

    def save(billingProject: String, owner: String): DBIO[String] = {
      (billingProjectQuery returning billingProjectQuery.map(_.id) += BillingProjectRecord(0, billingProject, Some(owner))) map { _ =>
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

  }
}
