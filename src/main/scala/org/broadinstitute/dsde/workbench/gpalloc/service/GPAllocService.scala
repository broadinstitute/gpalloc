package org.broadinstitute.dsde.workbench.gpalloc.service

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.gpalloc.db.DbReference
import org.broadinstitute.dsde.workbench.gpalloc.model.GPAllocException
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

import scala.concurrent.{ExecutionContext, Future}

case class NoGoogleProjectAvailable()
  extends GPAllocException(s"Sorry, no free google projects. Make your own", StatusCodes.NotFound)

class GPAllocService(protected val dbRef: DbReference, googleBillingDAO: HttpGoogleBillingDAO)
                    (implicit val executionContext: ExecutionContext) {

  def requestGoogleProject(userInfo: UserInfo): Future[GoogleProject] = {
    dbRef.inTransaction { dataAccess => dataAccess.billingProjectQuery.assignPooledBillingProject(userInfo.userEmail.value) } flatMap {
      case Some(project) =>
        googleBillingDAO.transferProjectOwnership(GoogleProject(project.billingProjectName), userInfo.userEmail.value)
      case None =>
        createNewGoogleProject() //Create one for the next person who asks (but don't wait on the future)
        throw NoGoogleProjectAvailable()
    }
  }

  def releaseGoogleProject(userInfo: UserInfo, project: String): Future[Unit] = {
    for {
      _ <- googleBillingDAO.nukeBillingProject(userInfo, GoogleProject(project))
      _ <- dbRef.inTransaction { dataAccess => dataAccess.billingProjectQuery.reclaimProject(project) }
    } yield {
      ()
    }

  }

  def createNewGoogleProject(): Future[Unit] = {
    //TODO: orch.makeNewProject, map billingProjectQuery.saveNew
    Future.successful(())
  }
}
