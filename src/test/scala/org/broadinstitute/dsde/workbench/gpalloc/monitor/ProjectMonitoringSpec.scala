package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{ActorRef, ActorSystem, Terminated}
import akka.testkit.{TestKit, TestProbe}
import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, MockGoogleDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.TestComponent
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.CreateProject
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

class ProjectMonitoringSpec extends TestKit(ActorSystem("gpalloctest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>

  def createSupervisor(gDAO: GoogleDAO): ActorRef = {
    system.actorOf(TestProjectCreationSupervisor.props("testBillingAccount", dbRef, gDAO, 100 millis, this), "testProjectCreationSupervisor")
  }

  def findMonitorActor(projectName: String): Future[ActorRef] = {
    system.actorSelection(s"/user/bpmon-$newProjectName").resolveOne(100 milliseconds)
  }

  "ProjectCreationSupervisor" should "create and monitor new projects" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO(false)

    val supervisor = createSupervisor(mockGoogleDAO)
    supervisor ! CreateProject(newProjectName)

    //we're now racing against the project monitor actor, so everything from here on is eventually
    eventually { findMonitorActor(newProjectName).futureValue }

    //did the monitor actor call the right things in google?
    eventually { mockGoogleDAO.createdProjects should contain(newProjectName) }
    eventually { mockGoogleDAO.enabledProjects should contain(newProjectName) }
    eventually { mockGoogleDAO.bucketedProjects should contain(newProjectName) }
    eventually { mockGoogleDAO.enabledProjects.size shouldEqual 4 } //one for create, plus 3 more service-enabling ops

    //TestProjectCreationSupervisor registers its children with TestKit, so when the child is done it should self-terminate
    expectMsgClass(1 second, classOf[Terminated])
  }

}
