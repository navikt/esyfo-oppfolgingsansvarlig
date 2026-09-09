package no.nav.syfo.sykmelding.retention.application

import java.time.LocalDate

interface SykmeldingRetentionRepository {
    suspend fun deleteBefore(cutoff: LocalDate, batchSize: Int): Int
}
