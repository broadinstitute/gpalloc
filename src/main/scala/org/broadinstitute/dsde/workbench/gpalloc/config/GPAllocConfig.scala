package org.broadinstitute.dsde.workbench.gpalloc.config


import scala.concurrent.duration.FiniteDuration

case class GPAllocConfig(
                          projectMonitorPollInterval: FiniteDuration,
                          abandonmentTime: FiniteDuration,
                          abandonmentSweepInterval: FiniteDuration,
                          minimumFreeProjects: Int,
                          projectsThrottle: Int,
                          projectsThrottlePerDuration: FiniteDuration,
                          opsThrottle: Int,
                          opsThrottlePerDuration: FiniteDuration,
                          projectPrefix: String
                        )
