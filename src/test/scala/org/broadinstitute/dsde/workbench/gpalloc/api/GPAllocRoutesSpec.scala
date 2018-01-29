package org.broadinstitute.dsde.workbench.gpalloc.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{FlatSpec, Matchers}

class GPAllocRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest {

  /*
  class TestGPAllocRoutes() extends GPAllocRoutes

  "gpallocRoutes" should "200 on ping" in {
    val gpallocRoutes = new TestGPAllocRoutes()

    Get("/ping") ~> gpallocRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }
  */
}
