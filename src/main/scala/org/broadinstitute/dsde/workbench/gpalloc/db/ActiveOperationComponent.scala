package org.broadinstitute.dsde.workbench.gpalloc.db

import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus.BillingProjectStatus

case class ActiveOperationRecord(billingProjectId: Long,
                                 operation: String)

trait ActiveOperationComponent extends GPAllocComponent {
  this: BillingProjectComponent =>

  import profile.api._

  class ActiveOperationTable(tag: Tag) extends Table[ActiveOperationRecord](tag, "ACTIVE_OPERATION") {
    def billingProjectId =            column[Long]              ("billingProjectId")
    def operation =                   column[String]            ("operation", O.Length(254))

    def fkBillingProject = foreignKey("FK_BILLING_PROJECT", billingProjectId, billingProjectQuery)(_.id)

    def * = (billingProjectId, operation) <> (ActiveOperationRecord.tupled, ActiveOperationRecord.unapply)
  }

  object operationQuery extends TableQuery(new ActiveOperationTable(_)) {

    /*
    def findBillingProject(billingProject: String) = {
      billingProjectQuery.filter(_.billingProjectName === billingProject)
    }

    def getBillingProject(billingProject: String): DBIO[Option[BillingProjectRecord]] = {
      findBillingProject(billingProject).result.headOption
    }

    def saveNew(billingProject: String, status: BillingProjectStatus = BillingProjectStatus.BrandNew): DBIO[String] = {
      (billingProjectQuery returning billingProjectQuery.map(_.id) += BillingProjectRecord(0, billingProject, None, status.toString)) map { _ =>
        billingProject
      }
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
    */

  }
}
