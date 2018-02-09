package org.broadinstitute.dsde.workbench.gpalloc.db

import java.sql.Timestamp
import java.time.Instant

import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus.BillingProjectStatus

case class BillingProjectRecord(billingProjectName: String,
                                owner: Option[String],
                                status: BillingProjectStatus,
                                lastAssignedTime: Option[Timestamp])

object BillingProjectRecord {
  val TIMESTAMP_SENTINEL = Instant.EPOCH.plusMillis(1000)

  //MySQL doesn't like nullable timestamps so we represent the None value as Epoch aka Timestamp 0.
  //These functions convert to and fro.
  def tsToDB(ts: Option[Timestamp]): Timestamp =
    ts.getOrElse(Timestamp.from(TIMESTAMP_SENTINEL))

  def tsToDB(ts: Instant): Timestamp =
    Timestamp.from(ts)

  def tsFromDB(ts: Timestamp): Option[Timestamp] =
    if( ts == Timestamp.from(TIMESTAMP_SENTINEL) ) None else Some(ts)

  //these give us magic conversions of enums to and from the db
  def fromDB(dbRow: (String, Option[String], String, Timestamp)): BillingProjectRecord = {
    val (billingProjectName, owner, status, lastAssignedTime) = dbRow
    BillingProjectRecord(billingProjectName, owner, BillingProjectStatus.withNameIgnoreCase(status), tsFromDB(lastAssignedTime))
  }

  def toDB(rec: BillingProjectRecord): Option[(String, Option[String], String, Timestamp)] = {
    Some((rec.billingProjectName, rec.owner, rec.status.toString, tsToDB(rec.lastAssignedTime)))
  }
}

trait BillingProjectComponent extends GPAllocComponent {
  this: ActiveOperationComponent =>

  import profile.api._

  class BillingProjectTable(tag: Tag) extends Table[BillingProjectRecord](tag, "BILLING_PROJECT") {
    def billingProjectName =          column[String]            ("billingProjectName",    O.PrimaryKey, O.Length(254))
    def owner =                       column[Option[String]]    ("owner",                 O.Length(254))
    def status =                      column[String]            ("status",                O.Length(254))
    def lastAssignedTime =            column[Timestamp]         ("lastAssignedTime",      O.SqlType("TIMESTAMP(6)"))

    def * = (billingProjectName, owner, status, lastAssignedTime) <> (BillingProjectRecord.fromDB, BillingProjectRecord.toDB)
  }

  object billingProjectQuery extends TableQuery(new BillingProjectTable(_)) {

    def findBillingProject(billingProject: String) = {
      billingProjectQuery.filter(_.billingProjectName === billingProject)
    }

    def getBillingProject(billingProject: String): DBIO[Option[BillingProjectRecord]] = {
      findBillingProject(billingProject).result.headOption
    }

    def getAssignedBillingProject(billingProject: String): DBIO[Option[BillingProjectRecord]] = {
      findBillingProject(billingProject).filter(_.status === BillingProjectStatus.Assigned.toString).result.headOption
    }

    def getCreatingProjects: DBIO[Seq[BillingProjectRecord]] = {
      billingProjectQuery.filter(_.status inSetBind BillingProjectStatus.creatingStatuses.map(_.toString) ).result
    }

    private[db] def saveNew(billingProject: String, status: BillingProjectStatus = BillingProjectStatus.CreatingProject): DBIO[String] = {
      (billingProjectQuery  += BillingProjectRecord(billingProject, None, status, None)) map { _ =>
        billingProject
      }
    }

    def saveNewProject(billingProject: String, operationRecord: ActiveOperationRecord, status: BillingProjectStatus = BillingProjectStatus.CreatingProject): DBIO[String] = {
      DBIO.seq(
        saveNew(billingProject, status),
        operationQuery.saveNewOperations(Seq(operationRecord))) map { _ => billingProject }
    }

    def updateStatus(billingProject: String, status: BillingProjectStatus): DBIO[Unit] = {
      findBillingProject(billingProject).map(bp => bp.status).update(status.toString).map{ _ => ()}
    }

    def assignProjectFromPool(owner: String): DBIO[Option[String]] = {
      val freeBillingProject = billingProjectQuery.filter(_.status === BillingProjectStatus.Unassigned.toString).take(1).forUpdate
      freeBillingProject.result flatMap { bps: Seq[BillingProjectRecord] =>
        bps.headOption match {
          case Some(bp) =>
            findBillingProject(bp.billingProjectName)
              .map(b => (b.owner, b.status, b.lastAssignedTime))
              .update(Some(owner), BillingProjectStatus.Assigned.toString, BillingProjectRecord.tsToDB(Instant.now())) map { _ => Some(bp.billingProjectName) }
          case None => DBIO.successful(None)
        }
      }
    }

    def countUnassignedProjects: DBIO[Int] = {
      billingProjectQuery.filter(_.status === BillingProjectStatus.Unassigned.toString).length.result
    }

    //Does nothing if your project isn't in Assigned.
    def releaseProject(billingProject: String): DBIO[Int] = {
      findBillingProject(billingProject)
        .filter(_.status === BillingProjectStatus.Assigned.toString)
        .map(bp => (bp.owner, bp.status, bp.lastAssignedTime))
        .update(None, BillingProjectStatus.Unassigned.toString, BillingProjectRecord.tsToDB(None))
    }

  }
}
