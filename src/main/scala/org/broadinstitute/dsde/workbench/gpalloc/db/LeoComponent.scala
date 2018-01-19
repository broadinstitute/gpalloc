package org.broadinstitute.dsde.workbench.gpalloc.db

import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

trait GPAllocComponent {
  val profile: JdbcProfile
  implicit val executionContext: ExecutionContext
}