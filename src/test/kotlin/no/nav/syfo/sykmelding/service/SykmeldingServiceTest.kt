package no.nav.syfo.sykmelding.service

import defaultSendtSykmeldingMessage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.TestDB
import no.nav.syfo.findAll
import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.kafka.SykmeldingRecord
import no.nav.syfo.sykmelding.model.SykmeldingsperiodeAGDTO
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class SykmeldingServiceTest :
    DescribeSpec({
        val testDb = TestDB.database
        val sykmeldingDb = SykmeldingDb(testDb)
        val service = SykmeldingService(sykmeldingDb)

        beforeEach {
            TestDB.clearSendtSykmeldingData()
        }

        describe("processBatch - basic insert behavior") {

            it("should insert sykmelding when it has employer and valid period") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe message.kafkaMetadata.fnr
                stored.first().orgnummer shouldBe message.event.arbeidsgiver?.orgnummer
            }

            it("should NOT insert sykmelding when it has no employer") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                ).copy(
                    event = defaultSendtSykmeldingMessage().event.copy(arbeidsgiver = null)
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                sykmeldingDb.findAll().size shouldBe 0
            }

            it("should NOT insert sykmelding when period is too old (1 year)") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(
                            fom = today.minusDays(30),
                            tom = today.minusYears(2)
                        )
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                sykmeldingDb.findAll().size shouldBe 0
            }

            it("should insert sykmelding when period ended within one year") {
                val fixedToday = LocalDate.parse("2026-03-01")
                val serviceWithFixedClock = SykmeldingService(
                    sykmeldingDb,
                    Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC),
                )
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(
                            fom = fixedToday.minusDays(20).minusYears(1),
                            tom = fixedToday.minusYears(1)
                        )
                    )
                )

                serviceWithFixedClock.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                sykmeldingDb.findAll().size shouldBe 1
            }

            it("should use the latest period (max tom) for fom and tom") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val earlierPeriod = SykmeldingsperiodeAGDTO(
                    fom = today.minusDays(30),
                    tom = today.minusDays(20)
                )
                val latestPeriod = SykmeldingsperiodeAGDTO(
                    fom = today.minusDays(5),
                    tom = today.plusDays(5)
                )
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(earlierPeriod, latestPeriod)
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fom shouldBe latestPeriod.fom
                stored.first().tom shouldBe latestPeriod.tom
            }

            it("should insert sykmelding with syketilfelleStartDato from message") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()
                val message = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = message)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().syketilfelleStartDato shouldNotBe null
            }

            it("should do nothing when batch is empty") {
                service.processBatch(emptyList())
                sykmeldingDb.findAll().size shouldBe 0
            }

            it("Should respect unique constraint on fnr+orgnummer") {
                val today = LocalDate.now()
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val fnr = "12345678901"
                val orgnummer = "999999999"

                val message1 = defaultSendtSykmeldingMessage(
                    sykmeldingId = id1.toString(),
                    fnr = fnr,
                    orgnummer = orgnummer,
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20))
                    )
                )

                val message2 = defaultSendtSykmeldingMessage(
                    sykmeldingId = id2.toString(),
                    fnr = fnr,
                    orgnummer = orgnummer,
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today)
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = id1, message = message1),
                        SykmeldingRecord(offset = 1, sykmeldingId = id2, message = message2)
                    )
                )

                // Only the second message should be inserted (same fnr+orgnummer, more recent tom date)
                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().sykmeldingId shouldBe id2
            }
        }

        describe("processBatch - multiple records") {

            it("should insert multiple sykmeldinger in one batch") {
                val today = LocalDate.now()
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val id3 = UUID.randomUUID()

                val records = listOf(
                    SykmeldingRecord(
                        offset = 0,
                        sykmeldingId = id1,
                        message = defaultSendtSykmeldingMessage(
                            sykmeldingId = id1.toString(),
                            fnr = "11111111111",
                            sykmeldingsperioder = listOf(
                                SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today)
                            )
                        )
                    ),
                    SykmeldingRecord(
                        offset = 1,
                        sykmeldingId = id2,
                        message = defaultSendtSykmeldingMessage(
                            sykmeldingId = id2.toString(),
                            fnr = "22222222222",
                            sykmeldingsperioder = listOf(
                                SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                            )
                        )
                    ),
                    SykmeldingRecord(
                        offset = 2,
                        sykmeldingId = id3,
                        message = defaultSendtSykmeldingMessage(
                            sykmeldingId = id3.toString(),
                            fnr = "33333333333",
                            sykmeldingsperioder = listOf(
                                SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                            )
                        )
                    )
                )

                service.processBatch(records)

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 3
                stored.find { it.sykmeldingId == id1 }?.fnr shouldBe "11111111111"
                stored.find { it.sykmeldingId == id2 }?.fnr shouldBe "22222222222"
                stored.find { it.sykmeldingId == id3 }?.fnr shouldBe "33333333333"
            }
        }

        describe("processBatch - final state deduplication") {

            it("should use highest offset when same sykmeldingId appears multiple times") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                val firstMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "11111111111",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today)
                    )
                )

                val secondMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "22222222222",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                    )
                )

                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = firstMessage),
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = secondMessage)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe "22222222222"
                stored.first().tom shouldBe today.plusDays(10)
            }

            it("should use highest offset regardless of order in list") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                val firstMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "11111111111",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today)
                    )
                )

                val secondMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "22222222222",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today, tom = today.plusDays(10))
                    )
                )

                // Insert in reverse order (higher offset first in list)
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = secondMessage),
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = firstMessage)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe "22222222222"
            }
        }

        describe("processBatch - tombstones (revokes)") {

            it("should revoke sykmelding when tombstone is received") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                // First insert
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = sykmeldingId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = sykmeldingId.toString(),
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                                )
                            )
                        )
                    )
                )

                // Then revoke in separate batch
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = null)
                    )
                )

                val stored = sykmeldingDb.findBySykmeldingId(sykmeldingId)
                stored shouldNotBe null
                stored!!.revokedDate shouldBe LocalDate.now()
            }

            it("should skip insert when tombstone is final state in same batch") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                val insertMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                    )
                )

                // Insert and tombstone in same batch - tombstone has higher offset
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = insertMessage),
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = null)
                    )
                )

                // Tombstone is final state, so nothing should be inserted
                // (revoke on non-existent record does nothing)
                sykmeldingDb.findAll().size shouldBe 0
            }

            it("should insert when insert is final state after tombstone") {
                val today = LocalDate.now()
                val sykmeldingId = UUID.randomUUID()

                val insertMessage = defaultSendtSykmeldingMessage(
                    sykmeldingId = sykmeldingId.toString(),
                    fnr = "12345678901",
                    sykmeldingsperioder = listOf(
                        SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                    )
                )

                // Tombstone then insert - insert has higher offset
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 0, sykmeldingId = sykmeldingId, message = null),
                        SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = insertMessage)
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 1
                stored.first().fnr shouldBe "12345678901"
                stored.first().revokedDate shouldBe null
            }

            it("should handle mixed inserts and revokes for different sykmeldingIds") {
                val today = LocalDate.now()
                val revokeId = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                val id3 = UUID.randomUUID()

                val record1 = SykmeldingRecord(
                    offset = 0,
                    sykmeldingId = revokeId,
                    message = defaultSendtSykmeldingMessage(
                        sykmeldingId = revokeId.toString(),
                        fnr = "11111111111",
                        sykmeldingsperioder = listOf(
                            SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                        )
                    )
                )
                val record2 = SykmeldingRecord(
                    offset = 1,
                    sykmeldingId = id2,
                    message = defaultSendtSykmeldingMessage(
                        sykmeldingId = id2.toString(),
                        fnr = "22222222222",
                        sykmeldingsperioder = listOf(
                            SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                        )
                    )
                )
                val record3 = SykmeldingRecord(
                    offset = 2,
                    sykmeldingId = id3,
                    message = defaultSendtSykmeldingMessage(
                        sykmeldingId = id3.toString(),
                        fnr = "33333333333",
                        sykmeldingsperioder = listOf(
                            SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today.minusDays(5))
                        )
                    )
                )

                // First insert all
                service.processBatch(
                    listOf(
                        record1,
                        record2,
                        record3
                    )
                )

                val unchangedId2Entity = sykmeldingDb.findBySykmeldingId(id2)
                // Now batch with: revoke id1, update id2, leave id3 alone
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 3, sykmeldingId = revokeId, message = null),
                        SykmeldingRecord(
                            offset = 4,
                            sykmeldingId = id2,
                            message = record2.message!!.copy()
                        )
                    )
                )

                val stored = sykmeldingDb.findAll()
                stored.size shouldBe 3

                // id1 should be revoked
                sykmeldingDb.findBySykmeldingId(revokeId)!!.revokedDate shouldBe LocalDate.now()

                // id2 should be re-created
                val recreatedId2Entity = sykmeldingDb.findBySykmeldingId(id2)
                recreatedId2Entity!!.fnr shouldBe "22222222222"
                unchangedId2Entity!!.updated shouldNotBe recreatedId2Entity.updated
                // id3 should be unchanged
                sykmeldingDb.findBySykmeldingId(id3)!!.fnr shouldBe "33333333333"
                sykmeldingDb.findBySykmeldingId(id3)!!.revokedDate shouldBe null
            }
        }

        describe("processBatch - delete existing sykmeldinger") {

            it("should delete existing sykmeldinger for same fnr and orgnummer before insert") {
                val today = LocalDate.now()
                val existingId = UUID.randomUUID()
                val newId = UUID.randomUUID()
                val fnr = "12345678901"
                val orgnummer = "999999999"

                // First insert existing sykmelding
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = existingId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = existingId.toString(),
                                fnr = fnr,
                                orgnummer = orgnummer,
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20))
                                )
                            )
                        )
                    )
                )

                sykmeldingDb.findBySykmeldingId(existingId) shouldNotBe null

                // Insert new sykmelding for same fnr and orgnummer
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 1,
                            sykmeldingId = newId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = newId.toString(),
                                fnr = fnr,
                                orgnummer = orgnummer,
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today)
                                )
                            )
                        )
                    )
                )

                // Old sykmelding should be deleted
                sykmeldingDb.findBySykmeldingId(existingId) shouldBe null
                // New sykmelding should be inserted
                sykmeldingDb.findBySykmeldingId(newId) shouldNotBe null
                sykmeldingDb.findBySykmeldingId(newId)!!.fnr shouldBe fnr
            }

            it("should not delete sykmeldinger for different orgnummer") {
                val today = LocalDate.now()
                val existingId = UUID.randomUUID()
                val newId = UUID.randomUUID()
                val fnr = "12345678901"

                // First insert existing sykmelding for one orgnummer
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = existingId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = existingId.toString(),
                                fnr = fnr,
                                orgnummer = "111111111",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20))
                                )
                            )
                        )
                    )
                )

                // Insert new sykmelding for same fnr but different orgnummer
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 1,
                            sykmeldingId = newId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = newId.toString(),
                                fnr = fnr,
                                orgnummer = "222222222",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today)
                                )
                            )
                        )
                    )
                )

                // Existing sykmelding should still exist (different orgnummer)
                sykmeldingDb.findBySykmeldingId(existingId) shouldNotBe null
                // New sykmelding should be inserted
                sykmeldingDb.findBySykmeldingId(newId) shouldNotBe null
                sykmeldingDb.findAll().size shouldBe 2
            }

            it("should not delete sykmeldinger for different fnr") {
                val today = LocalDate.now()
                val existingId = UUID.randomUUID()
                val newId = UUID.randomUUID()
                val orgnummer = "999999999"

                // First insert existing sykmelding for one fnr
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = existingId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = existingId.toString(),
                                fnr = "11111111111",
                                orgnummer = orgnummer,
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20))
                                )
                            )
                        )
                    )
                )

                // Insert new sykmelding for different fnr but same orgnummer
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 1,
                            sykmeldingId = newId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = newId.toString(),
                                fnr = "22222222222",
                                orgnummer = orgnummer,
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today)
                                )
                            )
                        )
                    )
                )

                // Existing sykmelding should still exist (different fnr)
                sykmeldingDb.findBySykmeldingId(existingId) shouldNotBe null
                // New sykmelding should be inserted
                sykmeldingDb.findBySykmeldingId(newId) shouldNotBe null
                sykmeldingDb.findAll().size shouldBe 2
            }

            it("should handle mixed delete and insert for multiple fnr/orgnummer combinations") {
                val today = LocalDate.now()
                val existingId1 = UUID.randomUUID()
                val existingId2 = UUID.randomUUID()
                val newId1 = UUID.randomUUID()
                val newId2 = UUID.randomUUID()

                // Insert existing sykmeldinger for two different fnr/orgnummer combinations
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = existingId1,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = existingId1.toString(),
                                fnr = "11111111111",
                                orgnummer = "111111111",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(60), tom = today.minusDays(50))
                                )
                            )
                        ),
                        SykmeldingRecord(
                            offset = 1,
                            sykmeldingId = existingId2,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = existingId2.toString(),
                                fnr = "22222222222",
                                orgnummer = "222222222",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(40), tom = today.minusDays(30))
                                )
                            )
                        )
                    )
                )

                // Insert new sykmeldinger for both combinations
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 2,
                            sykmeldingId = newId1,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = newId1.toString(),
                                fnr = "11111111111",
                                orgnummer = "111111111",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(10), tom = today)
                                )
                            )
                        ),
                        SykmeldingRecord(
                            offset = 3,
                            sykmeldingId = newId2,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = newId2.toString(),
                                fnr = "22222222222",
                                orgnummer = "222222222",
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
                                )
                            )
                        )
                    )
                )

                // Old sykmeldinger should be deleted
                sykmeldingDb.findBySykmeldingId(existingId1) shouldBe null
                sykmeldingDb.findBySykmeldingId(existingId2) shouldBe null
                // New sykmeldinger should be inserted
                sykmeldingDb.findBySykmeldingId(newId1) shouldNotBe null
                sykmeldingDb.findBySykmeldingId(newId2) shouldNotBe null
                sykmeldingDb.findAll().size shouldBe 2
            }

            it("should not delete when batch contains only tombstones") {
                val today = LocalDate.now()
                val existingId = UUID.randomUUID()
                val fnr = "12345678901"
                val orgnummer = "999999999"

                // First insert existing sykmelding
                service.processBatch(
                    listOf(
                        SykmeldingRecord(
                            offset = 0,
                            sykmeldingId = existingId,
                            message = defaultSendtSykmeldingMessage(
                                sykmeldingId = existingId.toString(),
                                fnr = fnr,
                                orgnummer = orgnummer,
                                sykmeldingsperioder = listOf(
                                    SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20))
                                )
                            )
                        )
                    )
                )

                val tombstoneId = UUID.randomUUID()
                // Process batch with only tombstone (revoke)
                service.processBatch(
                    listOf(
                        SykmeldingRecord(offset = 1, sykmeldingId = tombstoneId, message = null)
                    )
                )

                // Existing sykmelding should NOT be deleted (tombstones don't trigger deleteAll logic)
                sykmeldingDb.findBySykmeldingId(existingId) shouldNotBe null
            }
        }
    })
