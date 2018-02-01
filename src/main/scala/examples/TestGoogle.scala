package examples

import java.io.File

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.google.api.client.json.jackson2.JacksonFactory
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO

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

    val googleBillingDAO = new HttpGoogleBillingDAO(
      "gpalloc", //appName
      gcsConfig.getString("pathToBillingPem"), //serviceAccountPemFile
      gcsConfig.getString("billingPemEmail"), //billingPemEmail -- setServiceAccountId
      gcsConfig.getString("billingEmail")) //billingEmail -- setServiceAccountUser

    test(googleBillingDAO, gcsConfig.getString("billingAccount"))
  }

  def test(gDAO: HttpGoogleBillingDAO, billingAccount: String): Unit = {
    gDAO.createProject("gpalloc-test-project", billingAccount)
  }

  startup()
}
