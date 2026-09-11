package no.nav.syfo.sykmelding.retention.domain

import java.time.LocalDate

object SykmeldingRetentionPolicy {
    fun cutoff(today: LocalDate): LocalDate = today.minusYears(1)

    fun shouldRetain(tom: LocalDate, today: LocalDate): Boolean = tom >= cutoff(today)
}
