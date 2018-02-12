package org.broadinstitute.dsde.workbench.gpalloc

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import org.broadinstitute.dsde.workbench.gpalloc.dao.MockGoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{BillingProjectRecord, DbReference, DbSingleton, TestComponent}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.{CreateProject, RegisterGPAllocService}
import org.broadinstitute.dsde.workbench.gpalloc.service.{GPAllocService, GoogleProjectNotFound, NoGoogleProjectAvailable, NotYourGoogleProject}
import org.broadinstitute.dsde.workbench.util.NoopActor
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.Eventually._

import scala.concurrent.duration._

class GPAllocServiceSpec extends TestKit(ActorSystem("gpalloctest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>
  import profile.api._

  //returns a service and a probe that watches the pretend supervisor actor
  def gpAllocService(dbRef: DbReference, projectCreationThreshold: Int, abandonmentTime: Duration = 20 hours): (GPAllocService, TestProbe, MockGoogleDAO) = {
    val mockGoogleDAO = new MockGoogleDAO()
    val probe = TestProbe()
    val noopActor = probe.childActorOf(NoopActor.props)
    testKit watch noopActor
    val gpAlloc = new GPAllocService(dbRef, swaggerConfig, probe.ref, mockGoogleDAO, projectCreationThreshold, abandonmentTime)
    probe.expectMsgClass(1 seconds, classOf[RegisterGPAllocService])
    (gpAlloc, probe, mockGoogleDAO)
  }

  "GPAllocService" should "request an existing google project" in isolatedDbTest {
    //add an unassigned project to find
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1

    //make a service with a project creation threshold of 0 to trigger making a new one once this one is alloc'd
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 0)

    val assignedProject = gpAlloc.requestGoogleProject(userInfo).futureValue
    assignedProject shouldEqual toAssignedProject(newProjectName)

    //should hit the threshold and ask the supervisor to create a project
    //(but this won't really do anything because the supervisor is a fake)
    probe.expectMsgClass(1 seconds, classOf[CreateProject])

    //no more unassigned projects!
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
  }

  it should "barf when you request a google project but there are none in the pool" in isolatedDbTest {
    //make a service with a project creation threshold of 0 to trigger making a new one once this one is alloc'd
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 0)

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
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 0)

    val assignedProject = gpAlloc.requestGoogleProject(userInfo).futureValue
    Seq(toAssignedProject(newProjectName), toAssignedProject(newProjectName2)) should contain(assignedProject)

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

    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0)

    gpAlloc.releaseGoogleProject(userInfo.userEmail, newProjectName).futureValue
    eventually {
      //flipping the database back to Unassigned happens in a separate future to
      //the one returned by releaseGoogleProject, so we need to wait a bit here
      dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    }

    mockGoogleDAO.scrubbedProjects should contain theSameElementsAs Set(newProjectName)
  }

  it should "check permissions when releasing a project" in isolatedDbTest {
    //add one to find and assign it to the test user
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) }
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0

    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0)

    val releaseExc = gpAlloc.releaseGoogleProject(badUserInfo.userEmail, newProjectName).failed.futureValue
    releaseExc shouldBe a [NotYourGoogleProject]
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
    mockGoogleDAO.scrubbedProjects shouldBe 'empty
  }

  it should "not let you release a project that doesn't exist" in isolatedDbTest {
    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0)
    val releaseExc = gpAlloc.releaseGoogleProject(userInfo.userEmail, "nonexistent").failed.futureValue
    releaseExc shouldBe a [GoogleProjectNotFound]
    mockGoogleDAO.scrubbedProjects shouldBe 'empty
  }

  it should "not let you release a project that's not assigned" in isolatedDbTest {
    //add an unassigned one
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.CreatingProject) } shouldEqual newProjectName

    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0)
    val releaseExc = gpAlloc.releaseGoogleProject(userInfo.userEmail, newProjectName).failed.futureValue
    releaseExc shouldBe a [GoogleProjectNotFound]
    mockGoogleDAO.scrubbedProjects shouldBe 'empty
  }

  it should "clean up abandoned projects" in isolatedDbTest {
    //add some projects, one abandoned, one not
    dbFutureValue { _.billingProjectQuery += assignedBillingProjectRecord(newProjectName, userInfo.userEmail, 1 hour) }
    dbFutureValue { _.billingProjectQuery += assignedBillingProjectRecord(newProjectName2, userInfo.userEmail, 1 minute) }

    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0, 30 minutes)

    //this should clean up newProjectName but not newProjectName2
    gpAlloc.releaseAbandonedProjects()

    eventually {
      //flipping the database back to Unassigned happens in a separate future to
      //the one returned by releaseGoogleProject, so we need to wait a bit here
      dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    }

    //shoulda scrubbed google
    mockGoogleDAO.scrubbedProjects should contain theSameElementsAs Set(newProjectName)
  }
}
