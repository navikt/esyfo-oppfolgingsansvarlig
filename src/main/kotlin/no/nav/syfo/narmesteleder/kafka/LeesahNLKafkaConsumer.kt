package no.nav.syfo.narmesteleder.kafka

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.service.BehovSource
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

const val TEAMSYKMELDING_NL_LEESAH_TOPIC = "teamsykmelding.syfo-narmesteleder-leesah"

class LeesahNLKafkaConsumer(
    private val kafkaConsumer: KafkaConsumer<String, String>,
    private val jacksonMapper: ObjectMapper,
    private val handler: NlBehovLeesahHandler,
    private val scope: CoroutineScope,
) : KafkaListener {
    private lateinit var job: Job
    private val processed = mutableMapOf<TopicPartition, Long>()
    var commitOnAllErrors = false

    override fun listen() {
        logger.info("Starting leesah consumer")
        job = scope.launch(Dispatchers.IO + CoroutineName("leesah-consumer")) {
            while (isActive) {
                try {
                    kafkaConsumer.subscribe(listOf(TEAMSYKMELDING_NL_LEESAH_TOPIC))
                    start()
                } catch (_: WakeupException) {
                } catch (e: Exception) {
                    logger.error(
                        "Error running kafka consumer. Waiting $DELAY_ON_ERROR_SECONDS seconds for retry.",
                        e
                    )
                    kafkaConsumer.unsubscribe()
                    delay(DELAY_ON_ERROR_SECONDS.seconds)
                }
            }
            kafkaConsumer.close()
            logger.info("Exited Leesah consumer loop")
        }
    }

    private suspend fun start() = coroutineScope {
        while (isActive) {
            kafkaConsumer.poll(Duration.ofSeconds(POLL_DURATION_SECONDS))
                .forEach { record: ConsumerRecord<String, String?> ->
                    logger.info("Received record with key: ${record.key()}")
                    processRecord(record)
                }
            commitProcessedSync()
        }
    }

    private suspend fun processRecord(record: ConsumerRecord<String, String?>) {
        runCatching {
            record.value()?.let {
                val nlKafkaMessage =
                    jacksonMapper.readValue<NarmestelederLeesahKafkaMessage>(it)

                logger.info("Processing NL message with id: ${nlKafkaMessage.narmesteLederId}")
                if (nlKafkaMessage.aktivTom == null) {
                    handler.updateStatusForRequirement(nlKafkaMessage)
                } else {
                    handler.handleByLeesahStatus(
                        nlKafkaMessage.toNlBehovWrite(),
                        nlKafkaMessage.status,
                        behovSource = BehovSource(
                            nlKafkaMessage.narmesteLederId.toString(),
                            source = TEAMSYKMELDING_NL_LEESAH_TOPIC
                        )
                    )
                }
            } ?: logger.info("Received record with empty value: ${record.key()}")
            addToProcessed(record)
        }.getOrElse {
            handleProccessingError(record, it)
        }
    }

    private fun commitProcessedSync() {
        if (processed.isEmpty()) return

        val toCommit = processed.mapValues { (_, off) -> OffsetAndMetadata(off + 1) }
        if (toCommit.isNotEmpty()) kafkaConsumer.commitSync(toCommit)

        processed.clear()
        logger.info("Committed offsets for partitions")
    }

    override suspend fun stop() {
        if (!::job.isInitialized) error("Consumer not started!")

        logger.info("Preparing shutdown")
        logger.info("Stopping consuming topic $TEAMSYKMELDING_NL_LEESAH_TOPIC")

        job.cancel()
        kafkaConsumer.wakeup()
    }

    private fun addToProcessed(record: ConsumerRecord<String, String?>) {
        processed[TopicPartition(record.topic(), record.partition())] = record.offset()
    }

    private fun handleProccessingError(
        record: ConsumerRecord<String, String?>,
        error: Throwable
    ) {
        when (error) {
            is JsonMappingException -> {
                logger.error(
                    "Error while deserializing record with key ${record.key()} and offset ${record.offset()}. "
                )
            }

            else -> {
                logger.error(
                    "Error while processing record with key ${record.key()} " +
                        "and offset ${record.offset()}. Will NOT ack, message will be retried.",
                    error
                )
            }
        }
        if (commitOnAllErrors) {
            logger.info("commitOnAllErrors is enabled, committing offset despite the error.")
            addToProcessed(record)
        }
        throw error
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LeesahNLKafkaConsumer::class.java)
        private const val DELAY_ON_ERROR_SECONDS = 60L
        private const val POLL_DURATION_SECONDS = 1L
    }
}
