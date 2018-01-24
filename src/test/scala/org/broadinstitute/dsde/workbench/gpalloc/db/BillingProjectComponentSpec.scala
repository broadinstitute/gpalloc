package org.broadinstitute.dsde.workbench.gpalloc.db

import org.scalatest.FlatSpecLike
import org.scalatest.concurrent.ScalaFutures

class BillingProjectComponentSpec extends TestComponent with FlatSpecLike with ScalaFutures {

  "BillingProjectComponent" should "list, save, get, and delete" in isolatedDbTest {

    dbFutureValue { _.billingProjectQuery.getCreatingProjects } shouldEqual Seq()
  }
}
