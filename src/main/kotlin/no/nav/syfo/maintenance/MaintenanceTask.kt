package no.nav.syfo.maintenance

import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.task.ScheduledLeaderTask
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import kotlin.time.Duration

class MaintenanceTask(
    private val narmestelederService: NarmestelederService,
    private val deleteOldSykmeldinger: DeleteOldSykmeldinger,
    private val env: OtherEnvironmentProperties,
) : ScheduledLeaderTask(
    name = MaintenanceTask::class.qualifiedName!!,
    interval = Duration.parse(env.maintenanceTaskDelay),
) {
    override suspend fun execute() {
        narmestelederService.updateStatusOnExpiredBehovs(env.daysAfterTomToExpireBehovs)
        deleteOldSykmeldinger.execute()
    }
}
