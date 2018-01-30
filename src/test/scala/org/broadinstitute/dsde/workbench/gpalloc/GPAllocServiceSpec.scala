package org.broadinstitute.dsde.workbench.gpalloc

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import org.broadinstitute.dsde.workbench.gpalloc.dao.MockGoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{DbReference, DbSingleton, TestComponent}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.CreateProject
import org.broadinstitute.dsde.workbench.gpalloc.service.{GPAllocService, GoogleProjectNotFound, NoGoogleProjectAvailable, NotYourGoogleProject}
import org.broadinstitute.dsde.workbench.util.NoopActor
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.Eventually._

import scala.concurrent.duration._

class GPAllocServiceSpec  extends TestKit(ActorSystem("leonardotest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>

  //returns a service and a probe that watches the pretend supervisor actor
  def gpAllocService(dbRef: DbReference, projectCreationThreshold: Int): (GPAllocService, TestProbe) = {
    val mockGoogleDAO = new MockGoogleDAO(false)
    val probe = TestProbe()
    val noopActor = probe.childActorOf(NoopActor.props)
    testKit watch noopActor
    (new GPAllocService(dbRef, probe.ref, mockGoogleDAO, 0), probe)
  }

  "GPAllocService" should "request an existing google project" in isolatedDbTest {
    //add an unassigned project to find
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1

    //make a service with a project creation threshold of 0 to trigger making a new one once this one is alloc'd
    val (gpAlloc, probe) = gpAllocService(dbRef, 0)

    val assignedProject = gpAlloc.requestGoogleProject(userInfo).futureValue
    assignedProject shouldEqual newProjectName

    //should hit the threshold and ask the supervisor to create a project
    //(but this won't really do anything because the supervisor is a fake)
    probe.expectMsgClass(1 seconds, classOf[CreateProject])

    //no more unassigned projects!
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
  }

  it should "barf when you request a google project but there are none in the pool" in isolatedDbTest {
    //make a service with a project creation threshold of 0 to trigger making a new one once this one is alloc'd
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
    val (gpAlloc, probe) = gpAllocService(dbRef, 0)

    val noProjectExc = gpAlloc.requestGoogleProject(userInfo).failed.futureValue
    noProjectExc shouldBe a [NoGoogleProjectAvailable]

    //should hit the threshold and ask the supervisor to create a project
    //(but this won't really do anything because the supervisor is a fake)
    probe.expectMsgClass(1 seconds, classOf[CreateProject])

    //still no projects (because the supervisor is fake)
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
  }

  it should "only ask the supervisor to create a project when below the threshold" in isolatedDbTest {
    //add two unassigned projects to the pool
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName2, freshOpRecord(newProjectName2), BillingProjectStatus.Unassigned) } shouldEqual newProjectName2
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 2

    //after assigning a project, we'll have 1 left, so a threshold of 0 means we shouldn't create another
    val (gpAlloc, probe) = gpAllocService(dbRef, 0)

    val assignedProject = gpAlloc.requestGoogleProject(userInfo).futureValue
    Seq(newProjectName, newProjectName2) should contain(assignedProject)

    //should NOT ask the supervisor to create a new project
    //annoyingly there's no expectNoMsgClass, which i'd have preferred here
    probe.expectNoMsg()

    //one fewer project now
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
  }

  it should "release a project" in isolatedDbTest {
    //add one to find and assign it to the test user
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) }
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0

    val (gpAlloc, _) = gpAllocService(dbRef, 0)

    gpAlloc.releaseGoogleProject(userInfo, newProjectName).futureValue
    eventually {
      //flipping the database back to Unassigned happens in a separate future to
      //the one returned by releaseGoogleProject, so we need to wait a bit here
      dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    }
  }

  it should "check permissions when releasing a project" in isolatedDbTest {
    //add one to find and assign it to the test user
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) }
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0

    val (gpAlloc, _) = gpAllocService(dbRef, 0)

    val releaseExc = gpAlloc.releaseGoogleProject(badUserInfo, newProjectName).failed.futureValue
    releaseExc shouldBe a [NotYourGoogleProject]
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
  }

  it should "not let you release a project that doesn't exist" in isolatedDbTest {
    val (gpAlloc, _) = gpAllocService(dbRef, 0)
    val releaseExc = gpAlloc.releaseGoogleProject(userInfo, "nonexistent").failed.futureValue
    releaseExc shouldBe a [GoogleProjectNotFound]
  }

  it should "not let you release a project that's not assigned" in isolatedDbTest {
    //add an unassigned one
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.CreatingProject) } shouldEqual newProjectName

    val (gpAlloc, _) = gpAllocService(dbRef, 0)
    val releaseExc = gpAlloc.releaseGoogleProject(userInfo, newProjectName).failed.futureValue
    releaseExc shouldBe a [GoogleProjectNotFound]
  }
}

