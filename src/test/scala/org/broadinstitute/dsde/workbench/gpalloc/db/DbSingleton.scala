package org.broadinstitute.dsde.workbench.gpalloc.db

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.gpalloc.TestExecutionContext

// initialize database tables and connection pool only once
object DbSingleton {
  import TestExecutionContext.testExecutionContext
  val ref: DbReference = DbReference.init(ConfigFactory.load())
}
