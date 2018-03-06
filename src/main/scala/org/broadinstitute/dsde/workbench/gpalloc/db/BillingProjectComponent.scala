package org.broadinstitute.dsde.workbench.gpalloc.db

import java.sql.Timestamp
import java.time.Instant

import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus.BillingProjectStatus

import scala.concurrent.duration.Duration

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

//Exception to indicate that project assignment was racy and failed
case object RacyProjectsException extends RuntimeException

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
      (billingProjectQuery += BillingProjectRecord(billingProject, None, status, None)) map { _ =>
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

    def statusStats(): DBIO[Map[BillingProjectStatus, Int]] = {
      billingProjectQuery.groupBy( _.status ).map { case (status, recs) => (status, recs.length) }.result map { recs =>
        recs.map{ case (status, count) => BillingProjectStatus.withNameIgnoreCase(status) -> count }.toMap
      }
    }

    /**
      * NOTE: This may throw RacyProjectsException if two threads attempt to acquire the same project at the same time
      * In this case one of them will win and the other will throw. You, the caller, should handle RacyProjectsException.
      */
    def assignProjectFromPool(owner: String): DBIO[Option[String]] = {
      //query for a billing project that's free
      val freeBillingProject = billingProjectQuery.filter(_.status === BillingProjectStatus.Unassigned.toString).take(1).forUpdate

      //update it to be assigned to this owner
      freeBillingProject.result flatMap { bps: Seq[BillingProjectRecord] =>
        bps.headOption match {
          case Some(bp) =>
            //attempt to do the update, but only update the record if its status is Unassigned
            //this prevent racing if two calls to this function happen at the same time and return the same billing project
            //in theory, the forUpdate in the first line of this function should handle it, but in practice, it doesn't seem to :(
            val update = findBillingProject(bp.billingProjectName)
              .filter(b => b.status === BillingProjectStatus.Unassigned.toString)
              .map(b => (b.owner, b.status, b.lastAssignedTime))
              .update(Some(owner), BillingProjectStatus.Assigned.toString, BillingProjectRecord.tsToDB(Instant.now()))

            //count the number of rows we updated
            update flatMap {
              case 0 =>
                //if we updated 0 rows, someone else stole the billing project from under our feet
                //throw an exception and let the caller handle it
                DBIO.failed(RacyProjectsException)
              case 1 =>
                DBIO.successful(Some(bp.billingProjectName))
            }
          case None => DBIO.successful(None)
        }
      }
    }

    def countUnassignedProjects: DBIO[Int] = {
      billingProjectQuery.filter(_.status === BillingProjectStatus.Unassigned.toString).length.result
    }

    //This weird function allows us to ask "do we need to kick off creating any more projects right now?"
    def countUnassignedAndFutureProjects: DBIO[Int] = {
      billingProjectQuery.filter(_.status inSetBind(
        BillingProjectStatus.creatingStatuses.map(_.toString) ++ Seq(BillingProjectStatus.Unassigned.toString)) )
        .length.result
    }

    def getAbandonedProjects(abandonmentTime: Duration): DBIO[Seq[BillingProjectRecord]] = {
      billingProjectQuery
        .filter(_.status === BillingProjectStatus.Assigned.toString)
        .filter(bp => bp.lastAssignedTime < Timestamp.from(Instant.now().minusMillis(abandonmentTime.toMillis)) )
        .result
    }

    //Does nothing if your project isn't in Assigned.
    def releaseProject(billingProject: String): DBIO[Int] = {
      findBillingProject(billingProject)
        .filter(_.status === BillingProjectStatus.Assigned.toString)
        .map(bp => (bp.owner, bp.status, bp.lastAssignedTime))
        .update(None, BillingProjectStatus.Unassigned.toString, BillingProjectRecord.tsToDB(None))
    }

    def listEverything(): DBIO[Seq[BillingProjectRecord]] = {
      billingProjectQuery.result
    }

  }
}
