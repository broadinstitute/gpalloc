package org.broadinstitute.dsde.workbench.gpalloc.model

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