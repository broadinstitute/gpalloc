package org.broadinstitute.dsde.workbench.gpalloc.db

import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class BillingProjectComponentSpec extends TestComponent with FlatSpecLike with CommonTestData with ScalaFutures {
  import profile.api._
  import CommonTestData.BillingProjectRecordEquality._

  "BillingProjectComponent" should "list, save, get, update, and delete" in isolatedDbTest {
    //nothing
    dbFutureValue { _.billingProjectQuery.getCreatingProjects } shouldEqual Seq()

    //add two
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName2) } shouldEqual newProjectName2

    //look for them again
    dbFutureValue { _.billingProjectQuery.getCreatingProjects } should contain theSameElementsAs Seq(
      freshBillingProjectRecord(newProjectName),
      freshBillingProjectRecord(newProjectName2)
    )

    //look for a single
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(freshBillingProjectRecord(newProjectName))
    dbFutureValue { _.billingProjectQuery.getAssignedBillingProject(newProjectName) } shouldEqual None //not assigned

    //look for something that isn't there
    dbFutureValue { _.billingProjectQuery.getBillingProject("nonexistent") } shouldEqual None
    dbFutureValue { _.billingProjectQuery.getAssignedBillingProject("nonexistent") } shouldEqual None

    //delete one and look for it again
    dbFutureValue { _.billingProjectQuery.deleteProject(newProjectName) }
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual None

    //shouldn't have deleted the other
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName2) } shouldEqual Some(freshBillingProjectRecord(newProjectName2))
  }

  it should "update status manually" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.updateStatus(newProjectName, BillingProjectStatus.Unassigned)} shouldBe ()
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, None, BillingProjectStatus.Unassigned, None))
  }

  it should "assign a free project when one exists" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(requestingUser) } shouldEqual Some(newProjectName)
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, Some(requestingUser), BillingProjectStatus.Assigned, whenever))
    dbFutureValue { _.billingProjectQuery.getAssignedBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, Some(requestingUser), BillingProjectStatus.Assigned, whenever))
  }

  it should "return no projects when none exist" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Assigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(requestingUser) } shouldEqual None
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, None, BillingProjectStatus.Assigned, None))
  }

  it should "count the number of unassigned projects" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName2, BillingProjectStatus.Unassigned) } shouldEqual newProjectName2
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldEqual 2
  }

  it should "save a new project with its ActiveOperationRecord" in isolatedDbTest {
    val newOpRecord = freshOpRecord(newProjectName)
    saveProjectAndOps(newProjectName, newOpRecord) shouldEqual newProjectName
    dbFutureValue { _.operationQuery.getOperations(newProjectName) } shouldEqual Seq(newOpRecord)
  }

  it should "release projects that are in Assigned" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName2, BillingProjectStatus.CreatingProject) } shouldEqual newProjectName2

    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(requestingUser) } shouldEqual Some(newProjectName)
    dbFutureValue { _.billingProjectQuery.releaseProject(newProjectName) } shouldEqual 1

    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, None, BillingProjectStatus.Unassigned, None))

    //ensure the other one wasn't harmed
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName2) } shouldEqual Some(BillingProjectRecord(newProjectName2, None, BillingProjectStatus.CreatingProject, None))
  }

  it should "not release projects that aren't in Assigned" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName2) } shouldEqual newProjectName2

    dbFutureValue { _.billingProjectQuery.releaseProject(newProjectName) } shouldEqual 0

    dbFutureValue { _.billingProjectQuery.getCreatingProjects } should contain theSameElementsAs Seq(
      freshBillingProjectRecord(newProjectName),
      freshBillingProjectRecord(newProjectName2)
    )
  }

  it should "get abandoned projects" in isolatedDbTest {
    //add some projects, one abandoned, one not
    val abandonedProject = assignedBillingProjectRecord(newProjectName, userInfo.userEmail, 1 hour)
    dbFutureValue { _.billingProjectQuery += abandonedProject }
    dbFutureValue { _.billingProjectQuery += assignedBillingProjectRecord(newProjectName2, userInfo.userEmail, 1 minute) }

    dbFutureValue { _.billingProjectQuery.getAbandonedProjects(30 minutes) } should contain theSameElementsAs Seq(
      assignedBillingProjectRecord(newProjectName, userInfo.userEmail, 1 hour)
    )
  }

  def futureToFutureTry[T](f: Future[T]): Future[Try[T]] = {
    f.map(Success(_)).recover { case z => Failure(z) }
  }

  it should "handle being too racy when assigning projects" in isolatedDbTest {
    //add 50 projects
    (1 to 50) foreach { n =>
      dbFutureValue { _.billingProjectQuery.saveNew(s"$newProjectName-$n", BillingProjectStatus.Unassigned) }
    }

    //make 50 parallel requests to assign them
    //hopefully this should force a race condition
    val assignedProjectFs = (1 to 50) map { n =>
      DbSingleton.ref.inTransaction { _.billingProjectQuery.assignProjectFromPool(s"foo-$n@bar.baz") }
    } map futureToFutureTry

    //AFTER the futures have been started, wait for them
    val assignedProjects = assignedProjectFs map { f => f.futureValue }

    //partition out the successes and the fails
    val (successes, fails) = assignedProjects.partition( t => t.isSuccess )

    //if assignProjectFromPool is misbehaving, then it will return the same project at least twice
    //if the set of project names (sans duplicates) is the same length as the list, then there were no duplicates
    successes.map(_.get).toSet.size shouldEqual successes.size

    //any failures should be RacyProject exceptions, which the caller can handle
    fails.forall( f => f.isFailure && f == Failure(RacyProjectsException)) shouldBe true

    //HE note: at the time of test writing, running this test printed 26 below
    //thus proving that this code does (at least running locally on my machine!) force a real race condition
    //println(fails.length)
  }

}
