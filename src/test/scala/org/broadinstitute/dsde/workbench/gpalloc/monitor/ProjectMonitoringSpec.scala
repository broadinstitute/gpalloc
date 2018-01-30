package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestKit
import org.broadinstitute.dsde.workbench.google.GoogleIamDAO
import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.dao.GoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.TestComponent
import org.scalatest.FlatSpecLike

class ProjectMonitoringSpec extends TestKit(ActorSystem("gpalloctest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>
  def createClusterSupervisor(gDAO: GoogleDAO): ActorRef = {
    val supervisorActor = system.actorOf(TestClusterSupervisorActor.props(dataprocConfig, gdDAO, iamDAO, DbSingleton.ref, cacheActor, testKit))
    new LeonardoService(dataprocConfig, clusterFilesConfig, clusterResourcesConfig, proxyConfig, swaggerConfig, gdDAO, iamDAO, DbSingleton.ref, supervisorActor, whitelistAuthProvider, serviceAccountProvider, whitelist)
    supervisorActor
  }
}
