package org.broadinstitute.dsde.workbench.gpalloc.monitor

import java.time.Instant

import akka.actor.{ActorRef, Props}
import akka.testkit.TestKit
import org.broadinstitute.dsde.workbench.gpalloc.config.GPAllocConfig
import org.broadinstitute.dsde.workbench.gpalloc.dao.GoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference

import scala.collection.mutable
import scala.concurrent.duration._

object TestProjectCreationSupervisor {
  def props(billingAccount: String, dbRef: DbReference, googleDAO: GoogleDAO, gpAllocConfig: GPAllocConfig, testKit: TestKit): Props =
    Props(new TestProjectCreationSupervisor(billingAccount, dbRef, googleDAO, gpAllocConfig, testKit))
}

/**
  * Extends ClusterMonitorSupervisor so the akka TestKit can watch the child ClusterMonitorActors.
  */
class TestProjectCreationSupervisor(billingAccount: String, dbRef: DbReference, googleDAO: GoogleDAO, gpAllocConfig: GPAllocConfig, testKit: TestKit)
  extends ProjectCreationSupervisor(billingAccount, dbRef, googleDAO, gpAllocConfig) {

  val projectCreationTimes: mutable.ListBuffer[Instant] = mutable.ListBuffer.empty[Instant]

  override def createProject(projectName: String): Unit = {
    projectCreationTimes += Instant.now()
    super.createProject(projectName)
  }

  override def createChildActor(projectName: String): ActorRef = {
    val child = super.createChildActor(projectName)
    testKit watch child
    child
  }
}
