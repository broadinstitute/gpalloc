package org.broadinstitute.dsde.workbench.gpalloc.monitor

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Terminated}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.dao.{GoogleDAO, MockGoogleDAO}
import org.broadinstitute.dsde.workbench.gpalloc.db.{BillingProjectRecord, TestComponent}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus._
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationMonitor._
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.CreateProject
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time._

import scala.concurrent.Future
import scala.concurrent.duration._

class ProjectMonitoringSpec extends TestKit(ActorSystem("gpalloctest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>

  def withSupervisor[T](gDAO: GoogleDAO)(op: ActorRef => T): T = {
    val supervisor = system.actorOf(TestProjectCreationSupervisor.props("testBillingAccount", dbRef, gDAO, 10 millis, this), "testProjectCreationSupervisor")
    val result = op(supervisor)
    supervisor ! PoisonPill
    result
  }

  def findMonitorActor(projectName: String): Future[ActorRef] = {
    system.actorSelection(s"/user/bpmon-$newProjectName").resolveOne(100 milliseconds)
  }

  "ProjectCreationSupervisor" should "create and monitor new projects" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO()

    withSupervisor(mockGoogleDAO) { supervisor =>
      supervisor ! CreateProject(newProjectName)

      //we're now racing against the project monitor actor, so everything from here on is eventually
      eventually {
        findMonitorActor(newProjectName).futureValue
      }

      //did the monitor actor call the right things in google?
      val longer = Timeout(Span(500, Milliseconds)) //150ms isn't enough to progress through the actor states
      eventually(longer) {
        mockGoogleDAO.createdProjects should contain(newProjectName)
      }
      eventually(longer) {
        mockGoogleDAO.enabledProjects should contain(newProjectName)
      }
      eventually(longer) {
        mockGoogleDAO.bucketedProjects should contain(newProjectName)
      }
      eventually(longer) {
        mockGoogleDAO.polledOpIds.size shouldEqual (1 + mockGoogleDAO.servicesToEnable.length)
      } //+1 for the create op

      //TestProjectCreationSupervisor registers its children with TestKit, so when the child is done it should self-terminate
      expectMsgClass(1 second, classOf[Terminated])

      eventually {
        dbFutureValue {
          _.billingProjectQuery.getBillingProject(newProjectName)
        }
      }
    }
  }

  "ProjectCreationMonitor" should "createNewProject" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO()
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
    creatingOps.head.operationType shouldBe CreatingProject

    val opMap = dbFutureValue { _.operationQuery.getActiveOperationsByType(newProjectName) }
    opMap.size shouldBe 1
    opMap(CreatingProject) should contain theSameElementsAs creatingOps
  }

  it should "enableServices" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO()
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
    opMap.size shouldBe 1 //keys: enabling (creating are all done, so don't show up in get ACTIVE ops)

    //enabling more services
    opMap(EnablingServices).length shouldBe mockGoogleDAO.servicesToEnable.size
    opMap(EnablingServices).foreach { _.billingProjectName shouldBe newProjectName }
    opMap(EnablingServices).foreach { _.done shouldBe false }
  }

  it should "completeSetup" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO()
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

  it should "poll for active operations" in isolatedDbTest {
    val mockGoogleDAO = new MockGoogleDAO()
    val monitor = TestActorRef[ProjectCreationMonitor](ProjectCreationMonitor.props(newProjectName, testBillingAccount, dbRef, mockGoogleDAO, 10 millis)).underlyingActor

    //pretend we've already created the project
    val createdOp = freshOpRecord(newProjectName).copy(done=true)
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, createdOp) }
    val enablingOps = mockGoogleDAO.servicesToEnable map { _ => freshOpRecord(newProjectName).copy(done=false, operationType = EnablingServices) }
    dbFutureValue { _.operationQuery.saveNewOperations(enablingOps) }

    //poll
    monitor.pollForStatus(EnablingServices).futureValue shouldBe CompleteSetup

    //did we poll the right things -- i.e. only the active ops?
    mockGoogleDAO.polledOpIds should contain theSameElementsAs enablingOps.map{ _.operationId }
  }

  it should "behave when google says the operation errored" in isolatedDbTest {
    val errorGoogleDAO = new MockGoogleDAO(operationsReturnError = true)

    val monitor = TestActorRef[ProjectCreationMonitor](ProjectCreationMonitor.props(newProjectName, testBillingAccount, dbRef, errorGoogleDAO, 10 millis)).underlyingActor

    //pretend we've already created the project but not polled it yet
    val createdOp = freshOpRecord(newProjectName)
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, createdOp) }

    val pollResult = monitor.pollForStatus(CreatingProject).futureValue
    pollResult shouldBe a [Fail]

    val failedOps = pollResult.asInstanceOf[Fail].failedOps
    failedOps.size shouldBe 1
    failedOps foreach { _.billingProjectName shouldBe newProjectName }
  }

  it should "behave when google fails catastrophically" in isolatedDbTest {
    val errorGoogleDAO = new MockGoogleDAO(pollException = true)

    withSupervisor(errorGoogleDAO) { supervisor =>

      //pretend we've already created the project but not polled it yet
      val createdOp = freshOpRecord(newProjectName)
      dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, createdOp) }

      supervisor ! CreateProject(newProjectName)

      expectMsgClass(1 second, classOf[Terminated])
    }
  }

}
