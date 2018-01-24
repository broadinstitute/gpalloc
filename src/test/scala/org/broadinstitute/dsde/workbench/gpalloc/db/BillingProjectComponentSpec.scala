package org.broadinstitute.dsde.workbench.gpalloc.db

import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.ScalaFutures

class BillingProjectComponentSpec extends TestComponent with FlatSpecLike with CommonTestData {


  "BillingProjectComponent" should "list, save, get, and delete" in isolatedDbTest {
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

    //look for something that isn't there
    dbFutureValue { _.billingProjectQuery.getBillingProject("nonexistent") } shouldEqual None
  }

  it should "update status manually" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.updateStatus(newProjectName, BillingProjectStatus.EnablingServices)} shouldBe ()
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, None, BillingProjectStatus.EnablingServices.toString))
  }

  it should "assign a free project when one exists" in isolatedDbTest {
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName, BillingProjectStatus.Unassigned) } shouldEqual newProjectName
    dbFutureValue { _.billingProjectQuery.assignProjectFromPool(requestingUser) } shouldEqual Some(newProjectName)
    dbFutureValue { _.billingProjectQuery.getBillingProject(newProjectName) } shouldEqual Some(BillingProjectRecord(newProjectName, Some(requestingUser), BillingProjectStatus.Unassigned.toString))
  }
}
