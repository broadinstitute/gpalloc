package org.broadinstitute.dsde.workbench.gpalloc.model

import java.sql.Timestamp
import java.text.SimpleDateFormat

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.workbench.gpalloc.db.BillingProjectRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus.BillingProjectStatus
import spray.json.{DefaultJsonProtocol, DeserializationException, JsNumber, JsString, JsValue, JsonFormat}

import scala.language.implicitConversions

object BillingProjectStatus extends Enumeration {
  type BillingProjectStatus = Value
  val CreatingProject, EnablingServices, Unassigned, Assigned, Deleted = Value
  val creatingStatuses = Seq(CreatingProject, EnablingServices)

  class StatusValue(status: BillingProjectStatus) {
    def isCreating = creatingStatuses.contains(status)
  }

  implicit def enumConvert(status: BillingProjectStatus): StatusValue = new StatusValue(status)

  def withNameOpt(s: String): Option[BillingProjectStatus] = values.find(_.toString == s)

  def withNameIgnoreCase(str: String): BillingProjectStatus = {
    values.find(_.toString.equalsIgnoreCase(str)).getOrElse(throw new IllegalArgumentException(s"Unknown cluster status: $str"))
  }
}

case class AssignedProject(projectName: String, cromwellAuthBucketUrl: String)

object GPAllocJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object BillingProjectStatusFormat extends JsonFormat[BillingProjectStatus] {
    def write(obj: BillingProjectStatus) = JsString(obj.toString)

    def read(json: JsValue): BillingProjectStatus = json match {
      case JsString(status) => BillingProjectStatus.withName(status)
      case other => throw DeserializationException("Expected BillingProjectStatus, got: " + other)
    }
  }

  implicit object TimestampFormat extends JsonFormat[Timestamp] {
    def write(obj: Timestamp) = {
      val dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss:SSS z")
      JsString(dateFormat.format(obj))
    }


    def read(json: JsValue) = json match {
      case JsNumber(time) => new Timestamp(time.toLong)
      case _ => throw new DeserializationException("Date input as millis only please")
    }
  }

  implicit val assignedProjectFormat = jsonFormat2(AssignedProject.apply)
  implicit val billingProjectRecordFormat = jsonFormat4(BillingProjectRecord.apply)
}
