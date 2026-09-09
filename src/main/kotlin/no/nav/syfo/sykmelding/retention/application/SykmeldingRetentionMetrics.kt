package no.nav.syfo.sykmelding.retention.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

class SykmeldingRetentionMetrics(
    meterRegistry: MeterRegistry = METRICS_REGISTRY,
) {
    private val deletedSykmeldingerCounter: Counter = Counter
        .builder(DELETED_OLD_SYKMELDINGER)
        .description("Counts deleted sent sick leave messages by retention cleanup")
        .register(meterRegistry)

    fun countDeleted(count: Int) {
        require(count >= 0) { "count must not be negative" }
        deletedSykmeldingerCounter.increment(count.toDouble())
    }

    companion object {
        const val DELETED_OLD_SYKMELDINGER = "${METRICS_NS}_deleted_old_sykmeldinger"
    }
}
