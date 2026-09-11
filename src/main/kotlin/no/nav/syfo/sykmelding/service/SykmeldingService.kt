package no.nav.syfo.sykmelding.service

import no.nav.syfo.sykmelding.db.ISykmeldingDb
import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.kafka.SykmeldingRecord
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.model.SykmeldingsperiodeAGDTO
import no.nav.syfo.sykmelding.retention.domain.SykmeldingRetentionPolicy
import no.nav.syfo.util.logger
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class SykmeldingService(
    private val sykmeldingDb: ISykmeldingDb,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun processBatch(records: List<SykmeldingRecord>) {
        if (records.isEmpty()) return

        // Dedupe to final state per sykmeldingId (highest offset wins)
        val finalStateByKey = records
            .groupBy { it.sykmeldingId }
            .mapValues { (_, recordsForKey) -> recordsForKey.maxBy { it.offset } }
            .values

        // Partition into inserts vs revokes based on final state
        val (revokes, inserts) = finalStateByKey.partition { it.message == null }

        val entitiesToInsert = inserts.mapNotNull { record ->
            record.message?.let { msg -> toEntityIfValid(msg) }
        }
            // When multiple sykmeldingIds map to the same fnr+orgnummer, keep only the highest tom date
            // (sykmeldinger could be sent to arbeidsgiver in the wrong order)
            .groupBy { entity -> entity.fnr to entity.orgnummer }
            .mapValues { (_, entities) -> entities.maxBy { it.tom } }
            .values
            .toList()

        sykmeldingDb.transaction {
            // We may receive sykmeldinger with the same sykmeldingId
            // when a sykmelding is sent on paper and the veileder corrects something. This will not get
            // caught by the (fnr,orgnummer) constraint if the correction is a change of employer or fnr (uncertain if this could happen)
            val deletedDuplicateSykmeldingIds = batchDeleteAllBySykmeldingIds(entitiesToInsert.map { it.sykmeldingId })

            // Upsert: inserts new rows, or updates existing rows only when the incoming tom >= existing tom.
            // This is handled by the DB via ON CONFLICT (fnr, orgnummer) with a WHERE clause.
            val upsertedRows = batchUpsertSykmeldingerIfMoreRecentTom(entitiesToInsert)

            val revokedRows = batchRevokeSykmelding(revokes.map { it.sykmeldingId }, LocalDate.now())

            logger.info(
                "Batch processed: $upsertedRows inserts/updates, $revokedRows revokes, $deletedDuplicateSykmeldingIds deleted duplicate sykmeldingId  " +
                    "(from ${records.size} total records, ${finalStateByKey.size} unique keys)"
            )
        }
    }

    private fun toEntityIfValid(message: SendtSykmeldingKafkaMessage): SendtSykmeldingEntity? {
        val arbeidsgiver = message.event.arbeidsgiver ?: return null
        val latestPeriod = message.sykmelding.sykmeldingsperioder.maxBy { it.tom }

        if (!latestPeriodIsWithinOneYear(latestPeriod)) return null

        return SendtSykmeldingEntity(
            sykmeldingId = UUID.fromString(message.event.sykmeldingId),
            fnr = message.kafkaMetadata.fnr,
            orgnummer = arbeidsgiver.orgnummer,
            fom = latestPeriod.fom,
            tom = latestPeriod.tom,
            syketilfelleStartDato = message.sykmelding.syketilfelleStartDato,
        )
    }

    private fun latestPeriodIsWithinOneYear(
        sykmeldingsperiodeAGDTO: SykmeldingsperiodeAGDTO
    ): Boolean = SykmeldingRetentionPolicy.shouldRetain(
        tom = sykmeldingsperiodeAGDTO.tom,
        today = LocalDate.now(clock),
    )

    companion object {
        private val logger = logger()
    }
}
