package org.broadinstitute.dsde.workbench.gpalloc.db

import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus.BillingProjectStatus

case class BillingProjectRecord(billingProjectName: String,
                                owner: Option[String],
                                status: String)

trait BillingProjectComponent extends GPAllocComponent {
  this: ActiveOperationComponent =>

  import profile.api._

  class BillingProjectTable(tag: Tag) extends Table[BillingProjectRecord](tag, "BILLING_PROJECT") {
    def billingProjectName =          column[String]            ("billingProjectName",    O.PrimaryKey, O.Length(254))
    def owner =                       column[Option[String]]    ("owner",                 O.Length(254))
    def status =                      column[String]            ("status",                O.Length(254))

    def * = (billingProjectName, owner, status) <> (BillingProjectRecord.tupled, BillingProjectRecord.unapply)
  }

  object billingProjectQuery extends TableQuery(new BillingProjectTable(_)) {

    def findBillingProject(billingProject: String) = {
      billingProjectQuery.filter(_.billingProjectName === billingProject)
    }

    def getBillingProject(billingProject: String): DBIO[Option[BillingProjectRecord]] = {
      findBillingProject(billingProject).result.headOption
    }

    private def saveNew(billingProject: String, status: BillingProjectStatus = BillingProjectStatus.BrandNew): DBIO[String] = {
      (billingProjectQuery  += BillingProjectRecord(billingProject, None, status.toString)) map { _ =>
        billingProject
      }
    }

    def saveNewProject(billingProject: String, operationRecord: ActiveOperationRecord, status: BillingProjectStatus = BillingProjectStatus.BrandNew): DBIO[String] = {
      DBIO.seq(
        saveNew(billingProject),
        operationQuery.saveNewOperation(operationRecord)) map { _ => billingProject }
    }

    def updateStatus(billingProject: String, status: BillingProjectStatus): DBIO[Unit] = {
      findBillingProject(billingProject).map(bp => bp.status).update(status.toString).map{ _ => ()}
    }

    def assignPooledBillingProject(owner: String): DBIO[Option[BillingProjectRecord]] = {
      val freeBillingProject = billingProjectQuery.filter(_.status === BillingProjectStatus.Unassigned.toString).take(1).forUpdate
      freeBillingProject.result flatMap { bps: Seq[BillingProjectRecord] =>
        bps.headOption match {
          case Some(bp) => freeBillingProject.map(bp => bp.owner).update(Some(owner)) map { _ => Some(bp) }
          case None => DBIO.successful(None)
        }
      }
    }

    def reclaimProject(billingProject: String): DBIO[Unit] = {
      findBillingProject(billingProject).map(bp => (bp.owner, bp.status)).update(None, BillingProjectStatus.Unassigned.toString).map { _ => () }
    }

  }
}
