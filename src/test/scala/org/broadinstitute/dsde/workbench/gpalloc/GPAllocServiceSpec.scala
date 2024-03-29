package org.broadinstitute.dsde.workbench.gpalloc

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException.Builder
import org.broadinstitute.dsde.workbench.gpalloc.dao.MockGoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{BillingProjectRecord, DbReference, DbSingleton, TestComponent}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.{RegisterGPAllocService, RequestNewProject}
import org.broadinstitute.dsde.workbench.gpalloc.service.{GPAllocService, GoogleProjectNotFound, NoGoogleProjectAvailable, NotYourGoogleProject}
import org.broadinstitute.dsde.workbench.util.NoopActor
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.Eventually._

import scala.concurrent.Future
import scala.concurrent.duration._

class GPAllocServiceSpec extends TestKit(ActorSystem("gpalloctest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>
  import profile.api._

  //returns a service and a probe that watches the pretend supervisor actor
  def gpAllocService(dbRef: DbReference, minimumFreeProjects: Int, abandonmentTime: FiniteDuration = 20 hours, minimumProjects: Int = 0, googleDAO: MockGoogleDAO = new MockGoogleDAO()): (GPAllocService, TestProbe, MockGoogleDAO) = {
    val probe = TestProbe()
    val noopActor = probe.childActorOf(NoopActor.props)
    testKit watch noopActor
    val newConf = gpAllocConfig.copy(minimumFreeProjects=minimumFreeProjects, minimumProjects=minimumProjects, abandonmentTime=abandonmentTime)
    val gpAlloc = new GPAllocService(dbRef, swaggerConfig, probe.ref, googleDAO, newConf)
    probe.expectMsgClass(1 seconds, classOf[RegisterGPAllocService])
    (gpAlloc, probe, googleDAO)
  }

  "GPAllocService" should "request an existing google project" in isolatedDbTest {
    //add an unassigned project to find
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1

    //make a service with a project creation threshold of 1 to trigger making a new one once this one is alloc'd
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 1)

    val assignedProject = gpAlloc.requestGoogleProject(userInfo).futureValue
    assignedProject shouldEqual toAssignedProject(newProjectName)

    //should hit the threshold and ask the supervisor to create a project
    //(but this won't really do anything because the supervisor is a fake)
    probe.expectMsg(1 seconds, RequestNewProject)

    //no more unassigned projects!
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
  }

  it should "wake up and create new projects to minimum free" in isolatedDbTest {
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 2)

    //should hit the threshold and ask the supervisor to create a project
    //(but this won't really do anything because the supervisor is a fake)
    probe.expectMsg(1 seconds, RequestNewProject)
    probe.expectMsg(1 seconds, RequestNewProject)
  }

  it should "create projects up to the baseline minimum number" in isolatedDbTest {
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 0, 20 hours, 2)

    //should hit the threshold and ask the supervisor to create a project
    //(but this won't really do anything because the supervisor is a fake)
    probe.expectMsg(1 seconds, RequestNewProject)
    probe.expectMsg(1 seconds, RequestNewProject)
  }

  it should "not keep creating projects indefinitely if enough creating ones are in-flight" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Queued) } shouldEqual newProjectName
    saveProjectAndOps(newProjectName2, freshOpRecord(newProjectName2), BillingProjectStatus.CreatingProject) shouldEqual newProjectName2
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 2)

    //maybeCreateNewProjects shouldn't create projects here because we've got 2 being created
    probe.expectNoMsg()
  }

  it should "barf when you request a google project but there are none in the pool" in isolatedDbTest {
    //make a service with a project creation threshold of 1 to trigger making a new one once this one is alloc'd
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 1)

    //give me one as usual...
    val assignedProject = gpAlloc.requestGoogleProject(userInfo).futureValue
    assignedProject shouldEqual toAssignedProject(newProjectName)

    //should hit the threshold and ask the supervisor to create a project
    //(but this won't really do anything because the supervisor is a fake)
    probe.expectMsg(1 seconds, RequestNewProject)

    //give me another! no :(
    val noProjectExc = gpAlloc.requestGoogleProject(userInfo).failed.futureValue
    noProjectExc shouldBe a [NoGoogleProjectAvailable]

    //still no projects (because the supervisor is fake)
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
  }

  it should "only ask the supervisor to create a project when below the threshold" in isolatedDbTest {
    //add two unassigned projects to the pool
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    saveProjectAndOps(newProjectName2, freshOpRecord(newProjectName2), BillingProjectStatus.Unassigned) shouldEqual newProjectName2
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 2

    //after assigning a project, we'll have 1 left, so a threshold of 1 means we shouldn't create another
    val (gpAlloc, probe, _) = gpAllocService(dbRef, 1)

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
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) }
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0

    //here we have no minimum free projects. it's okay for us to just run out
    //this is completely unrealistic but means we have to jump through fewer hoops in the test
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
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 1
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) }
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0

    //here we have no minimum free projects. it's okay for us to just run out
    //this is completely unrealistic but means we have to jump through fewer hoops in the test
    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0)

    val releaseExc = gpAlloc.releaseGoogleProject(badUserInfo.userEmail, newProjectName).failed.futureValue
    releaseExc shouldBe a [NotYourGoogleProject]
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldBe 0
    mockGoogleDAO.scrubbedProjects shouldBe 'empty
  }

  it should "not let you release a project that doesn't exist" in isolatedDbTest {
    //here we have no minimum free projects. it's okay for us to just run out
    //this is completely unrealistic but means we have to jump through fewer hoops in the test
    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0)
    val releaseExc = gpAlloc.releaseGoogleProject(userInfo.userEmail, "nonexistent").failed.futureValue
    releaseExc shouldBe a [GoogleProjectNotFound]
    mockGoogleDAO.scrubbedProjects shouldBe 'empty
  }

  it should "not let you release a project that's not assigned" in isolatedDbTest {
    //add an unassigned one
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.CreatingProject) shouldEqual newProjectName

    //here we have no minimum free projects. it's okay for us to just run out
    //this is completely unrealistic but means we have to jump through fewer hoops in the test
    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0)
    val releaseExc = gpAlloc.releaseGoogleProject(userInfo.userEmail, newProjectName).failed.futureValue
    releaseExc shouldBe a [GoogleProjectNotFound]
    mockGoogleDAO.scrubbedProjects shouldBe 'empty
  }

  it should "clean up abandoned projects" in isolatedDbTest {
    //add some projects, one abandoned, one not
    dbFutureValue { _.billingProjectQuery += assignedBillingProjectRecord(newProjectName, userInfo.userEmail, 1 hour) }
    dbFutureValue { _.billingProjectQuery += assignedBillingProjectRecord(newProjectName2, userInfo.userEmail, 1 minute) }

    //here we have no minimum free projects. it's okay for us to just run out
    //this is completely unrealistic but means we have to jump through fewer hoops in the test
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

  it should "return statistics" in isolatedDbTest {
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    saveProjectAndOps(newProjectName2, freshOpRecord(newProjectName2), BillingProjectStatus.Assigned) shouldEqual newProjectName2
    saveProjectAndOps(newProjectName3, freshOpRecord(newProjectName3), BillingProjectStatus.Assigned) shouldEqual newProjectName3

    val (gpAlloc, _, _) = gpAllocService(dbRef, 1)
    val stats = gpAlloc.dumpStats().futureValue
    stats shouldEqual Map(BillingProjectStatus.Unassigned -> 1, BillingProjectStatus.Assigned -> 2)
  }

  it should "force cleanup of all unassigned projects" in isolatedDbTest {
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    saveProjectAndOps(newProjectName2, freshOpRecord(newProjectName2), BillingProjectStatus.Unassigned) shouldEqual newProjectName2
    saveProjectAndOps(newProjectName3, freshOpRecord(newProjectName3), BillingProjectStatus.Assigned) shouldEqual newProjectName3

    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 1)
    gpAlloc.forceCleanupAll().futureValue

    eventually {
      mockGoogleDAO.scrubbedProjects shouldBe Set(newProjectName, newProjectName2)
    }
  }

  it should "delete projects" in isolatedDbTest {
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    saveProjectAndOps(newProjectName2, freshOpRecord(newProjectName2), BillingProjectStatus.Unassigned) shouldEqual newProjectName2
    saveProjectAndOps(newProjectName3, freshOpRecord(newProjectName3), BillingProjectStatus.Assigned) shouldEqual newProjectName3

    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 1)

    //delete an unassigned project
    gpAlloc.nukeProject(newProjectName).futureValue
    mockGoogleDAO.deletedProjects shouldBe Set(newProjectName)
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldBe None

    //respect the ?delete queryparam
    gpAlloc.nukeProject(newProjectName2, deleteInGoogle = false).futureValue
    mockGoogleDAO.deletedProjects shouldNot contain(newProjectName2)
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName2) } shouldBe None

    //delete an assigned project
    gpAlloc.nukeProject(newProjectName3).futureValue
    mockGoogleDAO.deletedProjects should contain(newProjectName3)
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName3) } shouldBe None
  }

  it should "delete all projects" in isolatedDbTest {
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    saveProjectAndOps(newProjectName2, freshOpRecord(newProjectName2), BillingProjectStatus.Unassigned) shouldEqual newProjectName2
    saveProjectAndOps(newProjectName3, freshOpRecord(newProjectName3), BillingProjectStatus.Assigned) shouldEqual newProjectName3

    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 1)

    gpAlloc.nukeAllProjects().futureValue
    eventually {
      mockGoogleDAO.deletedProjects shouldBe Set(newProjectName, newProjectName2, newProjectName3)
    }
    dbFutureValue { _.billingProjectQuery.listEverything() } shouldEqual Seq()

  }

  class TooManyPetsGoogleDAO(exemptProjects: Set[String] = Set.empty) extends MockGoogleDAO {
    override def overPetLimit(projectName: String): Future[Boolean] = {
      if (exemptProjects.contains(projectName)) {
        Future.successful(false)
      } else {
        Future.successful(true)
      }
    }
  }

  it should "delete projects that have too many pets when they are released" in isolatedDbTest {
    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0, googleDAO = new TooManyPetsGoogleDAO)
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) }

    gpAlloc.releaseGoogleProject(userInfo.userEmail, newProjectName).futureValue
    eventually {
      mockGoogleDAO.deletedProjects shouldBe Set(newProjectName)
    }
    dbFutureValue { _.billingProjectQuery.listEverything() } shouldEqual Seq()
  }

  it should "delete projects with too many pets when they are forcefully cleaned up" in isolatedDbTest {
    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0, googleDAO = new TooManyPetsGoogleDAO(Set(newProjectName2)))
    saveProjectAndOps(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) shouldEqual newProjectName
    saveProjectAndOps(newProjectName2, freshOpRecord(newProjectName2), BillingProjectStatus.Unassigned) shouldEqual newProjectName2

    gpAlloc.forceCleanupAll()
    eventually {
      mockGoogleDAO.deletedProjects shouldBe Set(newProjectName)
      mockGoogleDAO.scrubbedProjects shouldBe Set(newProjectName2)
    }
  }

  class NonExistentProject() extends MockGoogleDAO {
    override def overPetLimit(projectName: String): Future[Boolean] = {
      Future.failed(new GoogleJsonResponseException(new Builder(404, "project not found", new HttpHeaders()), null))
    }
  }

  it should "tolerate releasing a project that has been deleted in Google" in isolatedDbTest {
    val nonExistentProjectName = "does-not-exist"
    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0, googleDAO = new NonExistentProject())
    saveProjectAndOps(nonExistentProjectName, freshOpRecord(nonExistentProjectName), BillingProjectStatus.Unassigned) shouldEqual nonExistentProjectName
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(userInfo.userEmail.value) }

    gpAlloc.releaseGoogleProject(userInfo.userEmail, nonExistentProjectName).futureValue
    eventually {
      mockGoogleDAO.deletedProjects shouldBe Set(nonExistentProjectName)
    }
  }

  it should "tolerate forcefully cleaning up a project that has been deleted in Google" in isolatedDbTest {
    val nonExistentProjectName = "does-not-exist"
    val (gpAlloc, _, mockGoogleDAO) = gpAllocService(dbRef, 0, googleDAO = new NonExistentProject())
    saveProjectAndOps(nonExistentProjectName, freshOpRecord(nonExistentProjectName), BillingProjectStatus.Unassigned) shouldEqual nonExistentProjectName

    gpAlloc.forceCleanupAll()
    eventually {
      mockGoogleDAO.deletedProjects shouldBe Set(nonExistentProjectName)
    }
  }
}
