package org.broadinstitute.dsde.workbench.gpalloc

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.google.api.client.json.jackson2.JacksonFactory
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.gpalloc.api.{GPAllocRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.gpalloc.config.{DeploymentManagerConfig, GPAllocConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.ResumeAllProjects
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService

import scala.concurrent.duration._

object Boot extends App with LazyLogging {

  private def startup(): Unit = {

    val config = ConfigFactory.parseResources("gpalloc.conf").withFallback(ConfigFactory.load())
    val gcsConfig = config.getConfig("gcs")
    val dmConfig = DeploymentManagerConfig(config.getConfig("deploymentManager"))
    val swaggerConfig = config.as[SwaggerConfig]("swagger")
    val gpAllocConfig = config.as[GPAllocConfig]("gpalloc")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("gpalloc")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val dbRef = DbReference.init(config)

    val defaultBillingAccount = gcsConfig.getString("billingAccount")

    val googleBillingDAO = new HttpGoogleBillingDAO(
      "gpalloc", //appName
      gcsConfig.getString("pathToBillingPem"), //serviceAccountPemFile
      gcsConfig.getString("billingPemEmail"), //billingPemEmail -- setServiceAccountId
      gcsConfig.getString("billingEmail"), //billingEmail -- setServiceAccountUser
      gcsConfig.getString("billingGroupEmail"), //terra-billing@fc.org
      defaultBillingAccount,
      dmConfig.orgID,
      dmConfig.projectID,
      dmConfig.templatePath,
      dmConfig.cleanupDeploymentAfterCreating,
      dmConfig.requesterPaysRole,
      gpAllocConfig.opsThrottle,
      gpAllocConfig.opsThrottlePerDuration,
      gpAllocConfig.maxPets)

    val projectCreationSupervisor = system.actorOf(
      ProjectCreationSupervisor.props(
        defaultBillingAccount,
        dbRef,
        googleBillingDAO,
        gpAllocConfig),
      "projectCreationSupervisor")
    projectCreationSupervisor ! ResumeAllProjects

    val gpAllocService = new GPAllocService(dbRef, swaggerConfig, projectCreationSupervisor, googleBillingDAO, gpAllocConfig)
    val gpallocRoutes = new GPAllocRoutes(gpAllocService, swaggerConfig) with StandardUserInfoDirectives

      Http().bindAndHandle(gpallocRoutes.route, "0.0.0.0", 8080)
        .recover {
          case t: Throwable =>
            logger.error("FATAL - failure starting http server", t)
            throw t
        }
  }

  startup()
}
