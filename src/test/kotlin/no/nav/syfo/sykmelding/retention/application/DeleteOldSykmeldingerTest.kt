package no.nav.syfo.sykmelding.retention.application

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DeleteOldSykmeldingerTest :
    DescribeSpec({
        val clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC)

        it("deletes batches until the repository has no old sykmeldinger") {
            val calls = mutableListOf<Pair<LocalDate, Int>>()
            val metrics = mockk<SykmeldingRetentionMetrics>(relaxed = true)
            val repository = object : SykmeldingRetentionRepository {
                private val results = ArrayDeque(listOf(2, 2, 0))

                override suspend fun deleteBefore(cutoff: LocalDate, batchSize: Int): Int {
                    calls += cutoff to batchSize
                    return results.removeFirst()
                }
            }

            DeleteOldSykmeldinger(repository, clock, metrics, batchSize = 2).execute()

            calls shouldBe List(3) { LocalDate.parse("2025-03-01") to 2 }
            verify(exactly = 1) { metrics.countDeleted(4) }
        }

        it("counts zero when no old sykmeldinger are deleted") {
            val metrics = mockk<SykmeldingRetentionMetrics>(relaxed = true)
            val repository = mockk<SykmeldingRetentionRepository>()
            coEvery { repository.deleteBefore(any(), any()) } returns 0

            DeleteOldSykmeldinger(repository, clock, metrics).execute()

            verify(exactly = 1) { metrics.countDeleted(0) }
        }
    })
