package org.broadinstitute.dsde.workbench.gpalloc.db

import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus.BillingProjectStatus

case class ActiveOperationRecord(billingProjectName: String,
                                 operationType: BillingProjectStatus,
                                 operationId: String,
                                 done: Boolean,
                                 errorMessage: Option[String])

object ActiveOperationRecord {
  //these give us magic conversions of enums to and from the db
  def fromDB(dbRow: (String, String, String, Boolean, Option[String])): ActiveOperationRecord = {
    val (billingProjectName, operationType, operationId, done, errorMessage) = dbRow
    ActiveOperationRecord(billingProjectName, BillingProjectStatus.withNameIgnoreCase(operationType), operationId, done, errorMessage)
  }

  def toDB(rec: ActiveOperationRecord): Option[(String, String, String, Boolean, Option[String])] = {
    Some((rec.billingProjectName, rec.operationType.toString, rec.operationId, rec.done, rec.errorMessage))
  }
}


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

    def * = (billingProjectName, operationType, operationId, done, errorMessage) <> (ActiveOperationRecord.fromDB, ActiveOperationRecord.toDB)
  }

  object operationQuery extends TableQuery(new ActiveOperationTable(_)) {

    def findOperations(billingProject: String) = {
      operationQuery.filter(_.billingProjectName === billingProject)
    }

    def getOperations(billingProject: String): DBIO[Seq[ActiveOperationRecord]] = {
      findOperations(billingProject).result
    }

    def saveNewOperations(newOperationRecs: Seq[ActiveOperationRecord]): DBIO[Seq[ActiveOperationRecord]] = {
      (operationQuery ++= newOperationRecs) map { _ => newOperationRecs }
    }

    def getActiveOperationsByType(billingProject: String): DBIO[Map[BillingProjectStatus, Seq[ActiveOperationRecord]]] = {
      findOperations(billingProject).filter(!_.done).result map { ops =>
        ops.groupBy(_.operationType)
      }
    }

    def updateOperations(updatedOps: Seq[ActiveOperationRecord]): DBIO[Int] = {
      DBIO.sequence(updatedOps.map { rec =>
        operationQuery
          .filter(o => o.billingProjectName === rec.billingProjectName && o.operationId === rec.operationId )
          .update(rec)
      }) map { _.sum }
    }

    def deleteOpsForProject(billingProject: String): DBIO[Int] = {
      findOperations(billingProject).delete
    }

  }
}
