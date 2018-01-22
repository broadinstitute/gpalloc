package org.broadinstitute.dsde.workbench.gpalloc.model

object BillingProjectStatus extends Enumeration {
  type BillingProjectStatus = Value
  val BrandNew, Unassigned, Assigned, Deleted = Value

  class StatusValue(status: BillingProjectStatus) {}

  implicit def enumConvert(status: BillingProjectStatus): StatusValue = new StatusValue(status)

  def withNameOpt(s: String): Option[BillingProjectStatus] = values.find(_.toString == s)

  def withNameIgnoreCase(str: String): BillingProjectStatus = {
    values.find(_.toString.equalsIgnoreCase(str)).getOrElse(throw new IllegalArgumentException(s"Unknown cluster status: $str"))
  }
}