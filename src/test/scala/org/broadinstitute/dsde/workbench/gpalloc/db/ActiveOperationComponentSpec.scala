package org.broadinstitute.dsde.workbench.gpalloc.db

import com.mysql.cj.jdbc.exceptions.MySQLTimeoutException
import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus
import org.scalatest.FlatSpecLike

class ActiveOperationComponentSpec extends TestComponent with FlatSpecLike with CommonTestData {

  "ActiveOperationComponent" should "list, save, and update" in isolatedDbTest {
    //save associated bproj to stop FK violation
    dbFutureValue { _.billingProjectQuery.saveNew(newProjectName) } shouldEqual newProjectName

    val newOps = Seq(
      freshOpRecord(newProjectName),
      freshOpRecord(newProjectName),
      freshOpRecord(newProjectName).copy(operationType = BillingProjectStatus.EnablingServices.toString) )
    dbFutureValue { _.operationQuery.saveNewOperations(newOps) } shouldEqual newOps

    //look for them again
    dbFutureValue { _.operationQuery.getOperations(newProjectName)} should contain theSameElementsAs newOps

    //look by type
    val opMap = dbFutureValue { _.operationQuery.getActiveOperationsByType(newProjectName) }
    opMap.keySet should contain theSameElementsAs Seq(BillingProjectStatus.CreatingProject.toString, BillingProjectStatus.EnablingServices.toString)

    //scalatest doesn't have a clean way to check containment of map values
    opMap(BillingProjectStatus.CreatingProject.toString) should contain theSameElementsAs newOps.take(2)
    opMap(BillingProjectStatus.EnablingServices.toString) should contain theSameElementsAs Seq(newOps.last)

    //FK violate if no related bproj
    dbFailure { _.operationQuery.saveNewOperations(Seq(freshOpRecord(newProjectName2))) } shouldBe a [java.sql.BatchUpdateException]

    //update
    val updatedOp = newOps.last.copy(operationType = BillingProjectStatus.Unassigned.toString)
    dbFutureValue { _.operationQuery.updateOperations(Seq(updatedOp)) }
    dbFutureValue { _.operationQuery.getOperations(newProjectName)} should contain theSameElementsAs newOps.take(2) ++ Seq(updatedOp)
  }
}
