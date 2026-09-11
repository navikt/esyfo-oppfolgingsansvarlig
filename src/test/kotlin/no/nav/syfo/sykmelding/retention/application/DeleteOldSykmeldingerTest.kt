package no.nav.syfo.sykmelding.retention.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DeleteOldSykmeldingerTest :
    DescribeSpec({
        val clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC)
        val logOutput = ByteArrayOutputStream()
        val logger = LoggerFactory.getLogger(DeleteOldSykmeldinger::class.java) as Logger
        val originalLevel = logger.level
        val originalAdditive = logger.isAdditive
        val encoder = LogstashEncoder().apply {
            context = logger.loggerContext
            start()
        }
        val appender = OutputStreamAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            this.encoder = encoder
            setOutputStream(logOutput)
            start()
        }

        beforeSpec {
            logger.level = Level.INFO
            logger.isAdditive = false
            logger.addAppender(appender)
        }

        afterSpec {
            logger.detachAppender(appender)
            logger.level = originalLevel
            logger.isAdditive = originalAdditive
            appender.stop()
            encoder.stop()
        }

        beforeTest {
            logOutput.reset()
        }

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
            assertCompletedCleanupLog(logOutput, deletedCount = 4)
        }

        it("counts and logs zero when no old sykmeldinger are deleted") {
            val metrics = mockk<SykmeldingRetentionMetrics>(relaxed = true)
            val repository = mockk<SykmeldingRetentionRepository>()
            coEvery { repository.deleteBefore(any(), any()) } returns 0

            DeleteOldSykmeldinger(repository, clock, metrics).execute()

            verify(exactly = 1) { metrics.countDeleted(0) }
            assertCompletedCleanupLog(logOutput, deletedCount = 0)
        }
    })

private fun assertCompletedCleanupLog(logOutput: ByteArrayOutputStream, deletedCount: Int) {
    val logLines = logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
    logLines shouldHaveSize 1

    val logRecord = jacksonObjectMapper().readTree(logLines.single())
    logRecord["level"].asText() shouldBe "INFO"
    logRecord["logger_name"].asText() shouldBe DeleteOldSykmeldinger::class.java.name
    logRecord["message"].asText() shouldBe "Sykmelding retention cleanup completed"
    logRecord["event_type"].asText() shouldBe "sykmelding_retention_cleanup_completed"
    logRecord["deleted_count"].asInt() shouldBe deletedCount
}
