package org.broadinstitute.dsde.workbench.gpalloc.util

import java.util.{Calendar, Date}

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestActor, TestActorRef, TestKit, TestProbe}
import org.broadinstitute.dsde.workbench.gpalloc.CommonTestData
import org.broadinstitute.dsde.workbench.gpalloc.db.TestComponent
import org.scalatest.FlatSpecLike
import akka.contrib.throttle.Throttler.RateInt

import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class ThrottlerSpec extends TestKit(ActorSystem("gpalloctest")) with TestComponent with FlatSpecLike with CommonTestData { testKit =>

  ignore /*Throttler*/ should "throttle" in {
    val throttler = new Throttler(dummyActorContext, 1 msgsPer 1000.milliseconds, "test")
    var times: mutable.ArrayBuffer[Date] = mutable.ArrayBuffer.empty[Date]

    def logDate(): Future[Unit] = {
      times += Calendar.getInstance.getTime
      Future.successful(s"logged ${times.size}")
    }

    throttler.throttle(logDate)
    throttler.throttle(logDate)
    throttler.throttle(logDate)
    throttler.throttle(logDate)
    throttler.throttle(() => logDate())
    throttler.throttle(logDate)

    val last = throttler.throttle(logDate) map { _ =>
      println(times)
    }

    Await.result(last, Duration.Inf)

    /*
    Await.result(throttler.sequence(Seq(logDate _, logDate _, logDate _, logDate _, logDate _, logDate _)) map { _ =>
      println(times)
    }, Duration.Inf)
    */

    /*
    protected def retryWhen500orGoogleError[T](op: () => T)(implicit histo: Histogram): Future[T] = {
    retryExponentially(when500orGoogleError)(() => Future(blocking(op())))
  }
     */
  }

}
