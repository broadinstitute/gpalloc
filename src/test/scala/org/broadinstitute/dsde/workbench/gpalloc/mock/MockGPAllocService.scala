package org.broadinstitute.dsde.workbench.gpalloc.mock

import akka.actor.ActorRef
import org.broadinstitute.dsde.workbench.gpalloc.config.{GPAllocConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.gpalloc.dao.GoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService

import scala.concurrent.ExecutionContext

class MockGPAllocService(dbRef: DbReference,
                         swaggerConfig: SwaggerConfig,
                         projectCreationSupervisor: ActorRef,
                         googleBillingDAO: GoogleDAO,
                         gpAllocConfig: GPAllocConfig) (executionContext: ExecutionContext)
  extends GPAllocService(
    dbRef,
    swaggerConfig,
    projectCreationSupervisor,
    googleBillingDAO,
    gpAllocConfig) (executionContext) {


  override def maybeCreateNewProjects(): Unit = {} //don't
}
