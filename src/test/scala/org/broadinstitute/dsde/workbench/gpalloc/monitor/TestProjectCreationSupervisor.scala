package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{ActorRef, Props}
import akka.testkit.TestKit
import org.broadinstitute.dsde.workbench.gpalloc.dao.GoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference

import scala.concurrent.duration._

object TestProjectCreationSupervisor {
  def props(billingAccount: String, dbRef: DbReference, googleDAO: GoogleDAO, pollInterval: FiniteDuration, abandonmentSweepInterval: FiniteDuration, testKit: TestKit): Props =
    Props(new TestProjectCreationSupervisor(billingAccount, dbRef, googleDAO, pollInterval, abandonmentSweepInterval, testKit))
}

/**
  * Extends ClusterMonitorSupervisor so the akka TestKit can watch the child ClusterMonitorActors.
  */
class TestProjectCreationSupervisor(billingAccount: String, dbRef: DbReference, googleDAO: GoogleDAO, pollInterval: FiniteDuration, abandonmentSweepInterval: FiniteDuration, testKit: TestKit)
  extends ProjectCreationSupervisor(billingAccount, dbRef, googleDAO, pollInterval, abandonmentSweepInterval) {
  override def createChildActor(projectName: String): ActorRef = {
    val child = super.createChildActor(projectName)
    testKit watch child
    child
  }
}
