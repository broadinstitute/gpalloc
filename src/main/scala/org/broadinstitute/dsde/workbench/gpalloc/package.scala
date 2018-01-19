package org.broadinstitute.dsde.workbench

import org.broadinstitute.dsde.workbench.model.ErrorReportSource

package object gpalloc {
  implicit val errorReportSource = ErrorReportSource("gpalloc")
}
