package org.broadinstitute.dsde.workbench.gpalloc.dao

import akka.actor.ActorSystem
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import org.broadinstitute.dsde.workbench.google.GoogleUtilities
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchException}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import com.google.api.services.cloudbilling.Cloudbilling

import scala.concurrent.{ExecutionContext, Future}

class HttpGoogleBillingDAO(appName: String)
                           (implicit val system: ActorSystem, val executionContext: ExecutionContext) extends GoogleUtilities {

  protected val workbenchMetricBaseName = "billing"

  lazy val httpTransport = GoogleNetHttpTransport.newTrustedTransport
  lazy val jsonFactory = JacksonFactory.getDefaultInstance

  private def billing(accessToken: String): Cloudbilling = {
    val credential = new GoogleCredential().setAccessToken(accessToken)
    new Cloudbilling.Builder(httpTransport, jsonFactory, credential).setApplicationName(appName).build()
  }

  //Transfer project ownership from ths gpalloc SA to the owner user
  def transferProjectOwnership(project: GoogleProject, owner: String): Future[GoogleProject] = {
    //TODO: actually do the work
    Future.successful(project)
  }

  def nukeBillingProject(userInfo: UserInfo, project: GoogleProject): Future[Unit] = {
    billing(userInfo.accessToken.value)
    //TODO: clean up all the things
    Future.successful(())
  }

}