package no.nav.syfo.maintenance

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.environment.UpdateDialogportenTaskProperties
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import kotlin.time.Duration.Companion.milliseconds

class MaintenanceTaskTest :
    DescribeSpec({
        val narmestelederService = mockk<NarmestelederService>()
        val deleteOldSykmeldinger = mockk<DeleteOldSykmeldinger>()

        val env = OtherEnvironmentProperties(
            electorPath = "elector",
            electorSSEUrl = "not.applicable",
            frontendBaseUrl = "https://frontend.test.nav.no",
            publicIngressUrl = "https://test.nav.no",
            persistLeesahNlBehov = true,
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
            isDialogportenBackgroundTaskEnabled = true,
            daysAfterTomToExpireBehovs = 16,
            maintenanceTaskDelay = "100ms",
            persistSendtSykmelding = true,
            maintenanceTaskEnabled = true,
            persistNarmestelederRegister = false,
            pdlLeesahConsumerEnabled = false,
            personEnrichmentTaskDelay = "5m",
            personEnrichmentTaskEnabled = false,
        )

        fun createTask() = MaintenanceTask(
            narmestelederService = narmestelederService,
            deleteOldSykmeldinger = deleteOldSykmeldinger,
            env = env,
        )

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }

        describe("MaintenanceTask") {
            context("execute") {
                it("should call updateStatusOnExpiredBehovs") {
                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } just Runs
                    coEvery { deleteOldSykmeldinger.execute() } just Runs

                    val task = createTask()

                    val job = launch {
                        task.runTask()
                    }

                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    coVerify(atLeast = 1) {
                        narmestelederService.updateStatusOnExpiredBehovs(
                            env.daysAfterTomToExpireBehovs,
                        )
                    }
                }

                it("should use correct daysAfterTomToExpireBehovs value") {
                    val customDays = 14L
                    val customEnv = env.copy(daysAfterTomToExpireBehovs = customDays)
                    val task = MaintenanceTask(
                        narmestelederService = narmestelederService,
                        deleteOldSykmeldinger = deleteOldSykmeldinger,
                        env = customEnv,
                    )

                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } just Runs
                    coEvery { deleteOldSykmeldinger.execute() } just Runs

                    val job = launch {
                        task.runTask()
                    }

                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    coVerify(atLeast = 1) {
                        narmestelederService.updateStatusOnExpiredBehovs(eq(customDays))
                    }
                }

                it("expires behov before deleting old sykmeldinger") {
                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } just Runs
                    coEvery { deleteOldSykmeldinger.execute() } just Runs

                    createTask().execute()

                    coVerify(ordering = io.mockk.Ordering.ORDERED) {
                        narmestelederService.updateStatusOnExpiredBehovs(
                            env.daysAfterTomToExpireBehovs,
                        )
                        deleteOldSykmeldinger.execute()
                    }
                }

                it("does not delete sykmeldinger when behov expiration fails") {
                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } throws IllegalStateException("failure")

                    io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                        createTask().execute()
                    }

                    coVerify(exactly = 0) { deleteOldSykmeldinger.execute() }
                }
            }
        }
    })
