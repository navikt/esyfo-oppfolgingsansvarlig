package no.nav.syfo.sykmelding.retention.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.util.UUID

class ExposedSykmeldingRetentionRepositoryTest :
    DescribeSpec({
        val repository = ExposedSykmeldingRetentionRepository(TestDB.exposedDatabase)
        val cutoff = LocalDate.parse("2025-03-01")

        beforeTest {
            TestDB.clearSendtSykmeldingData()
        }

        fun insert(tom: LocalDate, suffix: Int) {
            transaction(TestDB.exposedDatabase) {
                SendtSykmeldingTable.insert {
                    it[sykmeldingId] = UUID.randomUUID()
                    it[orgnummer] = "12345678$suffix"
                    it[fnr] = "1234567890$suffix"
                    it[fom] = tom.minusDays(10)
                    it[SendtSykmeldingTable.tom] = tom
                }
            }
        }

        it("deletes only rows strictly before the cutoff") {
            insert(cutoff.minusDays(1), 1)
            insert(cutoff, 2)
            insert(cutoff.plusDays(1), 3)

            repository.deleteBefore(cutoff, batchSize = 10) shouldBe 1

            transaction(TestDB.exposedDatabase) {
                SendtSykmeldingTable.selectAll().count() shouldBe 2
            }
        }

        it("deletes bounded batches until all qualifying rows have been removed") {
            insert(cutoff.minusDays(3), 1)
            insert(cutoff.minusDays(2), 2)
            insert(cutoff.minusDays(1), 3)

            repository.deleteBefore(cutoff, batchSize = 2) shouldBe 2
            repository.deleteBefore(cutoff, batchSize = 2) shouldBe 1
            repository.deleteBefore(cutoff, batchSize = 2) shouldBe 0
        }
    })
