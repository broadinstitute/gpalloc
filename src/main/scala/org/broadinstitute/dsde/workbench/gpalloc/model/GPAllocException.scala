package org.broadinstitute.dsde.workbench.gpalloc.model

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchException}
import org.broadinstitute.dsde.workbench.gpalloc.errorReportSource

abstract class GPAllocException(
                        val message: String = null,
                        val statusCode: StatusCode = StatusCodes.InternalServerError,
                        val cause: Throwable = null) extends WorkbenchException(message) {
  def toErrorReport: ErrorReport = {
    ErrorReport(Option(getMessage).getOrElse(""), Some(statusCode), Seq(), Seq(), Some(this.getClass))
  }
}
