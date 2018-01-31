package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{ActorRef, ActorSystem, Terminated}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, MockGoogleDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.{BillingProjectRecord, TestComponent}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationMonitor.{EnableServices, PollForStatus, Success}
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.CreateProject
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time._

import scala.concurrent.Future
import scala.concurrent.duration._

class ProjectMonitoringSpec extends TestKit(ActorSystem("gpalloctest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>
  //150ms isn't enough to progress through the actor states
  override implicit val patienceConfig = PatienceConfig(timeout = scaled(Span(500, Milliseconds)))

  def createSupervisor(gDAO: GoogleDAO): ActorRef = {
    system.actorOf(TestProjectCreationSupervisor.props("testBillingAccount", dbRef, gDAO, 10 millis, this), "testProjectCreationSupervisor")
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
    eventually { mockGoogleDAO.polledOpIds.size shouldEqual (1 + mockGoogleDAO.servicesToEnable.length) } //+1 for the create op

    //TestProjectCreationSupervisor registers its children with TestKit, so when the child is done it should self-terminate
    expectMsgClass(1 second, classOf[Terminated])

    eventually {
      dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) }
    }
  }

  "ProjectCreationMonitor" should "createNewProject" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO(false)
    val monitor = TestActorRef[ProjectCreationMonitor](ProjectCreationMonitor.props(newProjectName, testBillingAccount, dbRef, mockGoogleDAO, 10 millis)).underlyingActor

    //tell the monitor to create its project
    monitor.createNewProject.futureValue shouldBe PollForStatus(CreatingProject)

    //project should have correct status
    val bp = dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) }
    bp shouldBe 'defined
    bp.get shouldBe BillingProjectRecord(newProjectName, None, CreatingProject)

    val creatingOps = dbFutureValue { _.operationQuery.getOperations(newProjectName) }
    creatingOps.size shouldBe 1
    creatingOps.head.billingProjectName shouldBe newProjectName
    creatingOps.head.operationType shouldBe CreatingProject.toString

    val opMap = dbFutureValue { _.operationQuery.getActiveOperationsByType(newProjectName) }
    opMap.size shouldBe 1
    opMap(CreatingProject) should contain theSameElementsAs creatingOps
  }

  it should "enableServices" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO(false)
    val monitor = TestActorRef[ProjectCreationMonitor](ProjectCreationMonitor.props(newProjectName, testBillingAccount, dbRef, mockGoogleDAO, 10 millis)).underlyingActor

    //pretend we've already created the project
    val createdOp = freshOpRecord(newProjectName).copy(done=true)
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, createdOp) }

    //tell the monitor to enable services
    monitor.enableServices.futureValue shouldBe PollForStatus(EnablingServices)

    //project should have correct status
    val bp = dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) }
    bp shouldBe 'defined
    bp.get shouldBe BillingProjectRecord(newProjectName, None, EnablingServices)

    //check it's made some ops
    val enablingOps = dbFutureValue { _.operationQuery.getOperations(newProjectName) }
    enablingOps.size shouldBe (1 + mockGoogleDAO.servicesToEnable.size) //+1 for the create op
    enablingOps.foreach { _.billingProjectName shouldBe newProjectName }

    val opMap = dbFutureValue { _.operationQuery.getActiveOperationsByType(newProjectName) }
    opMap.size shouldBe 2 //keys: creating, enabling

    //mainly because we put it there up top, but let's check nothing crazy has happened
    opMap(CreatingProject).length shouldBe 1
    opMap(CreatingProject) should contain theSameElementsAs Seq(createdOp)

    //enabling more services
    opMap(EnablingServices).length shouldBe mockGoogleDAO.servicesToEnable.size
    opMap(EnablingServices).foreach { _.billingProjectName shouldBe newProjectName }
    opMap(EnablingServices).foreach { _.done shouldBe false }
  }

  it should "completeSetup" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO(false)
    val monitor = TestActorRef[ProjectCreationMonitor](ProjectCreationMonitor.props(newProjectName, testBillingAccount, dbRef, mockGoogleDAO, 10 millis)).underlyingActor

    //pretend we've already created the project and enabled services
    val createdOp = freshOpRecord(newProjectName).copy(done=true)
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, createdOp) }
    val enablingOps = mockGoogleDAO.servicesToEnable map { _ => freshOpRecord(newProjectName).copy(done=true, operationType = EnablingServices) }
    dbFutureValue { _.operationQuery.saveNewOperations(enablingOps) }

    //completing setup should complete
    monitor.completeSetup.futureValue shouldBe Success

    //project should have correct status
    val bp = dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) }
    bp shouldBe 'defined
    bp.get shouldBe BillingProjectRecord(newProjectName, None, Unassigned)

  }

  it should "behave when polling throws an error" in isolatedDbTest {
    //TODO
  }

}
