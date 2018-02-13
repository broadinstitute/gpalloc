package org.broadinstitute.dsde.workbench.gpalloc

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

import scala.concurrent.duration.FiniteDuration

package object config {
  implicit val liquibaseReader: ValueReader[LiquibaseConfig] = ValueReader.relative { config =>
    LiquibaseConfig(config.as[String]("changelog"), config.as[Boolean]("initWithLiquibase"))
  }

  implicit val swaggerReader: ValueReader[SwaggerConfig] = ValueReader.relative { config =>
    SwaggerConfig(
      config.getString("googleClientId"),
      config.getString("realm")
    )
  }

  implicit val gpAllocReader: ValueReader[GPAllocConfig] = ValueReader.relative {config =>
    GPAllocConfig(
      config.as[FiniteDuration]("projectMonitorPollInterval"),
      config.as[FiniteDuration]("abandonmentTime"),
      config.as[FiniteDuration]("abandonmentSweepInterval"),
      config.as[Int]("minimumFreeProjects"),
      config.as[Int]("projectsPerSecondThrottle"),
      config.as[String]("projectPrefix")
    )
  }
}
