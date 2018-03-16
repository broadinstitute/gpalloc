package org.broadinstitute.dsde.workbench.gpalloc.util

import scala.concurrent.{ExecutionContext, Future}

trait Sequentially {
  //stolen: https://gist.github.com/ryanlecompte/6313683
  def sequentially[A,T](items: Seq[A])(f: A => Future[T])(implicit executionContext: ExecutionContext): Future[Unit] = {
    items.headOption match {
      case Some(nextItem) =>
        val fut = f(nextItem)
        fut.flatMap { _ =>
          // successful, let's move on to the next!
          sequentially(items.tail)(f)
        }
      case None =>
        // nothing left to process
        Future.successful(())
    }
  }
}
