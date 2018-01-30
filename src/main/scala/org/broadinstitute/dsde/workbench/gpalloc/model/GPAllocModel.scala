package org.broadinstitute.dsde.workbench.gpalloc.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol

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
  implicit val assignedProjectFormat = jsonFormat2(AssignedProject.apply)
}
