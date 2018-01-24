package org.broadinstitute.dsde.workbench.gpalloc.db

import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus.BillingProjectStatus

case class ActiveOperationRecord(billingProjectName: String,
                                 operationType: String,
                                 operationId: String,
                                 done: Boolean,
                                 errorMessage: Option[String])

trait ActiveOperationComponent extends GPAllocComponent {
  this: BillingProjectComponent =>

  import profile.api._

  class ActiveOperationTable(tag: Tag) extends Table[ActiveOperationRecord](tag, "ACTIVE_OPERATION") {
    def billingProjectName =          column[String]            ("billingProjectName",  O.Length(254))
    def operationType =               column[String]            ("operationType",       O.Length(254))
    def operationId =                 column[String]            ("operationId",         O.Length(254))
    def done =                        column[Boolean]           ("done")
    def errorMessage =                column[Option[String]]    ("errorMessage",       O.Length(1024))

    def fkBillingProject = foreignKey("FK_BILLING_PROJECT", billingProjectName, billingProjectQuery)(_.billingProjectName)

    def * = (billingProjectName, operationType, operationId, done, errorMessage) <> (ActiveOperationRecord.tupled, ActiveOperationRecord.unapply)
  }

  object operationQuery extends TableQuery(new ActiveOperationTable(_)) {

    def findOperations(billingProject: String) = {
      operationQuery.filter(_.billingProjectName === billingProject)
    }

    def getOperations(billingProject: String) = {
      findOperations(billingProject).result
    }

    def saveNewOperations(newOperationRecs: Seq[ActiveOperationRecord]) = {
      operationQuery ++= newOperationRecs
    }

    def getActiveOperationsByType(billingProject: String): DBIO[Map[String, Seq[ActiveOperationRecord]]] = {
      findOperations(billingProject).filter(!_.done).result map { ops =>
        ops.groupBy(_.operationType)
      }
    }

    def updateOperations(updatedOps: Seq[ActiveOperationRecord]) = {
      DBIO.sequence(updatedOps.map { rec =>
        operationQuery
          .filter(o => o.billingProjectName === rec.billingProjectName && o.operationId === rec.operationId )
          .update(rec)
      })
    }

  }
}
