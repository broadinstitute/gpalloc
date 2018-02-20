package org.broadinstitute.dsde.workbench.gpalloc.api

import akka.actor.ActorSystem
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.stream.Materializer
import akka.stream.scaladsl._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.gpalloc.config.SwaggerConfig
import org.broadinstitute.dsde.workbench.gpalloc.errorReportSource
import org.broadinstitute.dsde.workbench.gpalloc.model.GPAllocJsonSupport._
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.gpalloc.model.GPAllocException
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.gpalloc.service._

import scala.concurrent.{ExecutionContext, Future}

abstract class GPAllocRoutes(val gpAllocService: GPAllocService, val swaggerConfig: SwaggerConfig)
                            (implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext)
  extends LazyLogging
    with UserInfoDirectives
    with SwaggerRoutes
{

  lazy val unauthedRoutes: Route =
    path("ping") {
      pathEndOrSingleSlash {
        get {
          complete {
            StatusCodes.OK
          }
        }
      }
    }

  lazy val gpAllocRoutes: Route =
    requireUserInfo { userInfo =>
      pathPrefix("api") {
        path("googleproject") {
          get {
            complete {
              gpAllocService.requestGoogleProject(userInfo).map { newProject =>
                StatusCodes.OK -> newProject
              }
            }
          }
        } ~
        path("googleproject" / Segment) { project =>
          delete {
            complete {
              gpAllocService.releaseGoogleProject(userInfo.userEmail, project).map { _ =>
                StatusCodes.Accepted
              }
            }
          }
        } ~
        path("admin" / "dump") {
          get {
            complete {
              gpAllocService.dumpState().map { state =>
                StatusCodes.OK -> state
              }
            }
          }
        }
      }
    }



  def route: server.Route = (logRequestResult & handleExceptions(myExceptionHandler)) {
    unauthedRoutes ~ gpAllocRoutes ~ swaggerRoutes
  }

  private val myExceptionHandler = {
    ExceptionHandler {
      case gpAllocException: GPAllocException =>
        complete(gpAllocException.statusCode, gpAllocException.toErrorReport)
      case withErrorReport: WorkbenchExceptionWithErrorReport =>
        complete(withErrorReport.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError), withErrorReport.errorReport)
      case e: Throwable =>
        complete(StatusCodes.InternalServerError, ErrorReport(e))
    }
  }

  // basis for logRequestResult lifted from http://stackoverflow.com/questions/32475471/how-does-one-log-akka-http-client-requests
  private def logRequestResult: Directive0 = {
    def entityAsString(entity: HttpEntity): Future[String] = {
      entity.dataBytes
        .map(_.decodeString(entity.contentType.charsetOption.getOrElse(HttpCharsets.`UTF-8`).value))
        .runWith(Sink.head)
    }

    def myLoggingFunction(logger: LoggingAdapter)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val logLevel: LogLevel = resp.status.intValue / 100 match {
            case 5 => Logging.ErrorLevel
            case _ => Logging.DebugLevel
          }
          entityAsString(resp.entity).map(data => LogEntry(s"${req.method} ${req.uri}: ${resp.status} entity: $data", logLevel))
        case other =>
          Future.successful(LogEntry(s"$other", Logging.DebugLevel)) // I don't really know when this case happens
      }
      entry.map(_.logTo(logger))
    }

    DebuggingDirectives.logRequestResult(LoggingMagnet(log => myLoggingFunction(log)))
  }

  def statusCodeCreated[T](response: T): (StatusCode, T) = (StatusCodes.Created, response)

}
