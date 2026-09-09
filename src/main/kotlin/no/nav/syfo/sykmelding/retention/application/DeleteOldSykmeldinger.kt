package no.nav.syfo.sykmelding.retention.application

import no.nav.syfo.sykmelding.retention.business.SykmeldingRetentionPolicy
import no.nav.syfo.util.logger
import java.time.Clock
import java.time.LocalDate

class DeleteOldSykmeldinger(
    private val repository: SykmeldingRetentionRepository,
    private val clock: Clock,
    private val metrics: SykmeldingRetentionMetrics,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    suspend fun execute() {
        val cutoff = SykmeldingRetentionPolicy.cutoff(LocalDate.now(clock))
        var deletedInBatch: Int
        var totalDeleted = 0

        do {
            deletedInBatch = repository.deleteBefore(cutoff, batchSize)
            totalDeleted += deletedInBatch
        } while (deletedInBatch > 0)

        metrics.countDeleted(totalDeleted)
        logger.atInfo()
            .addKeyValue("event_type", RETENTION_CLEANUP_COMPLETED)
            .addKeyValue("deleted_count", totalDeleted)
            .log("Sykmelding retention cleanup completed")
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 500
        private const val RETENTION_CLEANUP_COMPLETED = "sykmelding_retention_cleanup_completed"
        private val logger = logger(DeleteOldSykmeldinger::class.java.name)
    }
}
