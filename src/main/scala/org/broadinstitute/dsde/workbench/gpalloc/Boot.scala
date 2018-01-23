package org.broadinstitute.dsde.workbench.gpalloc


import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.gpalloc.api.{GPAllocRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService

import scala.concurrent.{ExecutionContext, Future}

object Boot extends App with LazyLogging {

  private def startup(): Unit = {

    val config = ConfigFactory.load()

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("gpalloc")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val dbRef = DbReference.init(config)
    val googleBillingDAO = new HttpGoogleBillingDAO("gpalloc")
    val projectCreationSupervisor = system.actorOf(ProjectCreationSupervisor.props("fixme-billing-account", dbRef, googleBillingDAO))

    val gpAllocService = new GPAllocService(dbRef, projectCreationSupervisor, googleBillingDAO)

    val gpallocRoutes = new GPAllocRoutes(gpAllocService) with StandardUserInfoDirectives

      Http().bindAndHandle(gpallocRoutes.route, "0.0.0.0", 8080)
        .recover {
          case t: Throwable =>
            logger.error("FATAL - failure starting http server", t)
            throw t
        }
  }

  startup()
}
