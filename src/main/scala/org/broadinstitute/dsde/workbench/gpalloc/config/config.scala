package org.broadinstitute.dsde.workbench.gpalloc

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader

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
}
