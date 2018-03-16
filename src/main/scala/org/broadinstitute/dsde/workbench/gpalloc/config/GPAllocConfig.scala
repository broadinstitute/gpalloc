package org.broadinstitute.dsde.workbench.gpalloc.config


import scala.concurrent.duration.FiniteDuration

case class GPAllocConfig(
                          projectMonitorPollInterval: FiniteDuration,
                          abandonmentTime: FiniteDuration,
                          abandonmentSweepInterval: FiniteDuration,
                          minimumFreeProjects: Int,
                          projectsPerSecondThrottle: Int,
                          opsThrottle: Int,
                          opsThrottlePerDuration: FiniteDuration,
                          enableThrottle: Int,
                          enableThrottlePerDuration: FiniteDuration,
                          projectPrefix: String
                        )
