package org.broadinstitute.dsde.workbench.gpalloc.util

import akka.actor.{Actor, ActorSystem, Props}
import akka.contrib.throttle.Throttler.{Rate, SetTarget}
import akka.contrib.throttle.TimerBasedThrottler
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class Throttler(system: ActorSystem, throttleRate: Rate, name: String) {

  val throttleWorker = system.actorOf(ThrottleWorker.props(), s"throttleWorker-$name")

  //yes this is deprecated. no i'm not going to move to akka streams
  val throttler = system.actorOf(Props(classOf[TimerBasedThrottler], throttleRate), s"throttler-$name")
  throttler ! SetTarget(Some(throttleWorker))

  case class Work[T](op: () => Future[T])

  implicit val timeout = Timeout(20 seconds)

  object ThrottleWorker {
    def props(): Props = {
      Props(new ThrottleWorker())
    }
  }

  class ThrottleWorker extends Actor {
    import context._

    override def receive: PartialFunction[Any, Unit] = {
      case Work(op) => doWork(op) pipeTo sender
    }

    def doWork[T](op: () => Future[T])(implicit ec: ExecutionContext): Future[Any] = {
      op()
    }
  }

  def throttle[T](op: () => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    (throttler ? Work(op)).asInstanceOf[Future[T]]
  }

  def sequence[T]( ops: Seq[ () => Future[T] ] )(implicit ec: ExecutionContext): Future[Seq[T]] = {
    Future.traverse(ops)( o => throttle(o)(ec))
  }

}
