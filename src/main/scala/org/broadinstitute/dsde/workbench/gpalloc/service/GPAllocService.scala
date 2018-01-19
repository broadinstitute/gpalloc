package org.broadinstitute.dsde.workbench.gpalloc.service

import org.broadinstitute.dsde.workbench.gpalloc.dao.HttpGoogleBillingDAO
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

import scala.concurrent.Future

class GPAllocService(googleBillingDAO: HttpGoogleBillingDAO) {
  def newGoogleProject(userInfo: UserInfo): Future[String] = {
    Future.successful("broad-dsde-dev")
  }

  def releaseGoogleProject(userInfo: UserInfo, project: String): Future[Unit] = {
    googleBillingDAO.nukeBillingProject(userInfo, GoogleProject(project))
  }
}
