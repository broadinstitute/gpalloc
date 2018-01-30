package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, MockGoogleDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.TestComponent
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.CreateProject
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.Eventually._

import scala.concurrent.Future
import scala.concurrent.duration._

class ProjectMonitoringSpec extends TestKit(ActorSystem("gpalloctest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>

  def createSupervisor(gDAO: GoogleDAO): ActorRef = {
    system.actorOf(ProjectCreationSupervisor.props("testBillingAccount", dbRef, gDAO, 100 millis))
  }

  def findMonitorActor(projectName: String): Future[ActorRef] = {
    system.actorSelection(s"/user/bpmon-$newProjectName").resolveOne(100 milliseconds)
  }

  "ProjectCreationSupervisor" should "create and monitor new projects" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO(false)
    val supervisor = createSupervisor(mockGoogleDAO)
    supervisor ! CreateProject(newProjectName)

    eventually {
      findMonitorActor(newProjectName).futureValue
    }

  }

}
