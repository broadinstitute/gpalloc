package org.broadinstitute.dsde.workbench.gpalloc.db

import akka.actor.{Actor, ActorContext, ActorSystem, Props}
import akka.testkit.TestActorRef
import org.broadinstitute.dsde.workbench.gpalloc.TestExecutionContext
import org.broadinstitute.dsde.workbench.gpalloc.util.Throttler
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import slick.dbio.DBIO
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

trait TestComponent extends Matchers with ScalaFutures
  with GPAllocComponent {

  override val profile: JdbcProfile = DbSingleton.ref.dataAccess.profile
  override implicit val executionContext: ExecutionContext = TestExecutionContext.testExecutionContext
  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(10, Seconds)))

  def dbFutureValue[T](f: (DataAccess) => DBIO[T]): T = DbSingleton.ref.inTransaction(f).futureValue
  def dbFailure[T](f: (DataAccess) => DBIO[T]): Throwable = DbSingleton.ref.inTransaction(f).failed.futureValue

  // clean up after tests
  def isolatedDbTest[T](testCode: => T): T = {
    try {
      // TODO: why is cleaning up at the end of tests not enough?
      dbFutureValue { _ => DbSingleton.ref.dataAccess.truncateAll() }
      testCode
    } catch {
      case t: Throwable => t.printStackTrace(); throw t
    } finally {
      dbFutureValue { _ => DbSingleton.ref.dataAccess.truncateAll() }
    }
  }

  //Just give me an ActorContext to make Throttlers with.
  def dummyActorContext(implicit actorSystem: ActorSystem): ActorContext = {

    object DummyActor {
      def props(): Props = Props(new DummyActor())
    }
    class DummyActor() extends Actor {
      override def receive: Receive = {
        case _ =>
      }
    }

    TestActorRef[DummyActor](DummyActor.props()).underlyingActor.context
  }
}
