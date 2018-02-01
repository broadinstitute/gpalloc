package org.broadinstitute.dsde.workbench.gpalloc.db

import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.ScalaFutures

class BillingProjectComponentSpec extends TestComponent with FlatSpecLike with CommonTestData {

  import profile._


  "BillingProjectComponent" should "list, save, get, and update" in isolatedDbTest {
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
  }

  it should "update status manually" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.updateStatus(newProjectName, BillingProjectStatus.EnablingServices)} shouldBe ()
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, None, BillingProjectStatus.EnablingServices))
  }

  it should "assign a free project when one exists" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(requestingUser) } shouldEqual Some(newProjectName)
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, Some(requestingUser), BillingProjectStatus.Assigned))
    dbFutureValue { _.billingProjectQuery.getAssignedBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, Some(requestingUser), BillingProjectStatus.Assigned))
  }

  it should "return no projects when none exist" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Assigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(requestingUser) } shouldEqual None
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, None, BillingProjectStatus.Assigned))
  }

  it should "count the number of unassigned projects" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName2, BillingProjectStatus.Unassigned) } shouldEqual newProjectName2
    dbFutureValue { _.billingProjectQuery.countUnassignedProjects } shouldEqual 2
  }

  it should "save a new project with its ActiveOperationRecord" in isolatedDbTest {
    val newOpRecord = freshOpRecord(newProjectName)
    dbFutureValue { _.billingProjectQuery.saveNewProject(newProjectName, newOpRecord) } shouldEqual newProjectName
    dbFutureValue { _.operationQuery.getOperations(newProjectName) } shouldEqual Seq(newOpRecord)
  }

  it should "release projects that are in Assigned" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName2, BillingProjectStatus.CreatingProject) } shouldEqual newProjectName2

    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(requestingUser) } shouldEqual Some(newProjectName)
    dbFutureValue { _.billingProjectQuery.releaseProject(newProjectName) } shouldEqual 1

    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, None, BillingProjectStatus.Unassigned))

    //ensure the other one wasn't harmed
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName2) } shouldEqual Some(BillingProjectRecord(newProjectName2, None, BillingProjectStatus.CreatingProject))
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
}
