package examples

import java.io.File

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.json.jackson2.JacksonFactory
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.ActiveOperationRecord
import org.broadinstitute.dsde.workbench.gpalloc.model.BillingProjectStatus

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

//this runs from within intellij.
//right now i can't be bothered figuring out how to get it to run from a terminal
object TestGoogle extends App {

  private def startup(): Unit = {
    val config = ConfigFactory.parseFile(new File("gpalloc.conf"))

    implicit val system = ActorSystem("gpalloc")
    implicit val materializer = ActorMaterializer()
    import system.dispatcher
    import scala.concurrent.ExecutionContext.Implicits.global

    val gcsConfig = config.getConfig("gcs")
    val jsonFactory = JacksonFactory.getDefaultInstance

    val gDAO = new HttpGoogleBillingDAO(
      "gpalloc", //appName
      gcsConfig.getString("pathToBillingPem"), //serviceAccountPemFile
      gcsConfig.getString("billingPemEmail"), //billingPemEmail -- setServiceAccountId
      gcsConfig.getString("billingEmail")) //billingEmail -- setServiceAccountUser

    val projectName = "gpalloc-test-project"

    //testEnableCloudServices(gDAO, projectName, gcsConfig.getString("billingAccount"))
    //testPollOp(gDAO, projectName, ActiveOperationRecord("gpalloc-test-project",BillingProjectStatus.EnablingServices,"operations/tmo-acf.c8c99528-2900-46cb-a676-07da63ac5da1",false,None))
    //testBucketAccess(gDAO,  projectName)
  }

  def testProjectCreation(gDAO: HttpGoogleBillingDAO, projectName: String, billingAccount: String)(implicit ec: ExecutionContext): Unit = {
    gDAO.createProject(projectName, billingAccount).onComplete {
      case Success(recs) => println(recs)
      case Failure(e: TokenResponseException) => println(e.getDetails)
      case Failure(e) => println(e.getMessage)
    }
  }

  def testEnableCloudServices(gDAO: HttpGoogleBillingDAO, projectName: String, billingAccount: String)(implicit ec: ExecutionContext): Unit = {
    gDAO.enableCloudServices(projectName, billingAccount).onComplete {
      case Success(recs) => println(recs)
      case Failure(e: TokenResponseException) => println(e.getDetails)
      case Failure(e) => println(e.getMessage)
    }
  }

  def testPollOp(gDAO: HttpGoogleBillingDAO, rec: ActiveOperationRecord)(implicit ec: ExecutionContext): Unit = {
    gDAO.pollOperation(rec).onComplete {
      case Success(recs) => println(recs)
      case Failure(e) => println(e.toString)
    }
  }

  def testBucketAccess(gDAO: HttpGoogleBillingDAO, projectName: String)(implicit ec: ExecutionContext): Unit = {
    gDAO.setupProjectBucketAccess(projectName).onComplete {
      case Success(recs) => println(recs)
      case Failure(e) => println(e.toString)
    }
  }

  startup()
}
