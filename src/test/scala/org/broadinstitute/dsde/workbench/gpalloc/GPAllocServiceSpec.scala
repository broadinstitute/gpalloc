package org.broadinstitute.dsde.workbench.gpalloc

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestKit}
import org.broadinstitute.dsde.workbench.gpalloc.dao.MockGoogleDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.{DbReference, DbSingleton, TestComponent}
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.broadinstitute.dsde.workbench.gpalloc.monitor.ProjectCreationSupervisor.CreateProject
import org.broadinstitute.dsde.workbench.gpalloc.service.GPAllocService
import org.broadinstitute.dsde.workbench.util.NoopActor
import org.scalatest.FlatSpecLike

import scala.concurrent.duration._

class GPAllocServiceSpec  extends TestKit(ActorSystem("leonardotest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>

  def gpAllocService(dbRef: DbReference): GPAllocService = {
    val mockGoogleDAO = new MockGoogleDAO(false)
    val noopActor = TestActorRef[NoopActor](NoopActor.props)
    testKit watch noopActor
    new GPAllocService(dbRef, noopActor, mockGoogleDAO, 0)
  }

  "GPAllocService" should "request an existing google project" in isolatedDbTest {
    //add an unassigned project to find
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, freshOpRecord(newProjectName), BillingProjectStatus.Unassigned) } shouldEqual newProjectName

    val gpAlloc = gpAllocService(dbRef)

    val assignedProject = gpAlloc.requestGoogleProject(userInfo).futureValue
    assignedProject shouldEqual newProjectName

    expectMsgClass(3 seconds, classOf[CreateProject])
  }
}

