package no.nav.syfo.plugins

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import no.nav.syfo.application.environment.OtherEnvironmentProperties

class ConfigureTasksTest :
    DescribeSpec({
        describe("backgroundTasksToStart") {
            it("starts maintenance but not Dialogporten tasks when Dialogporten is disabled") {
                backgroundTasksToStart(
                    OtherEnvironmentProperties.createForLocal().copy(
                        isDialogportenBackgroundTaskEnabled = false,
                        maintenanceTaskEnabled = true,
                    ),
                ) shouldContainExactly setOf(BackgroundTask.Maintenance)
            }

            it("does not start maintenance when maintenance is disabled") {
                backgroundTasksToStart(
                    OtherEnvironmentProperties.createForLocal().copy(
                        maintenanceTaskEnabled = false,
                    ),
                ) shouldContainExactly setOf(
                    BackgroundTask.SendDialog,
                    BackgroundTask.PersonEnrichment,
                )
            }
        }
    })
