package org.broadinstitute.dsde.workbench.gpalloc.util

import akka.actor.{Actor, ActorContext, ActorRefFactory, Props}
import akka.contrib.throttle.Throttler._
import akka.contrib.throttle.TimerBasedThrottler
import akka.pattern._
import akka.util.Timeout

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

//Every call to throttle() costs one ticket. Every ticketRefreshTime, the number of tickets is reset to
//tickets. If no tickets are available when throttle() is called, the throttler will wait until more are available.
class Throttler(actorFactory: ActorRefFactory, tickets: Int, ticketRefreshTime: FiniteDuration, name: String) extends Sequentially {

  val throttleWorker = actorFactory.actorOf(ThrottleWorker.props(), s"throttleWorker-$name")

  //yes this is deprecated. no i'm not going to move to akka streams
  val throttler = actorFactory.actorOf(Props(classOf[TimerBasedThrottler], tickets msgsPer ticketRefreshTime), s"throttler-$name")
  throttler ! SetTarget(Some(throttleWorker))

  case class Work[T](op: () => Future[T])

  //This timeout has to be loooong; lots of poll operations may back up the throttle queue for quite a while.
  implicit val timeout = Timeout(5 minutes)

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

    def doWork[T](op: () => Future[T])(implicit ec: ExecutionContext): Future[T] = {
      op()
    }
  }

  def throttle[T](op: () => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    (throttler ? Work(op)).asInstanceOf[Future[T]]
  }

  //Chew up more than one ticket.
  def throttle[T](tickets: Int)(op: () => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    sequentially(1 to (tickets-1))(_ => Future.successful(())) flatMap { _ =>
      throttle(op)
    }
  }

  def sequence[T]( ops: Seq[ () => Future[T] ] )(implicit ec: ExecutionContext): Future[Seq[T]] = {
    Future.traverse(ops)( o => throttle(o)(ec))
  }

}
