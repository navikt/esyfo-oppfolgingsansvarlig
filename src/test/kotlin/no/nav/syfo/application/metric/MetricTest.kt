package no.nav.syfo.application.metric

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MetricTest :
    DescribeSpec({

        describe("bindJvmAndProcessMetrics") {
            it("registers JVM heap, non-heap, GC, thread, process and system meters in the scrape registry") {
                bindJvmAndProcessMetrics()

                val scrape = METRICS_REGISTRY.scrape()

                scrape shouldContain "jvm_memory_used_bytes"
                scrape shouldContain "area=\"heap\""
                scrape shouldContain "area=\"nonheap\""
                scrape shouldContain "jvm_gc_live_data_size_bytes"
                scrape shouldContain "jvm_threads_live_threads"
                scrape shouldContain "process_cpu_usage"
                scrape shouldContain "process_uptime_seconds"
                scrape shouldContain "system_cpu_usage"
            }

            it("is idempotent across repeated invocations") {
                bindJvmAndProcessMetrics()
                val jvmGcMeterNamesAfterFirst = METRICS_REGISTRY.meters
                    .map { it.id.name }
                    .filter { it.startsWith("jvm.gc.") }
                    .toSet()
                val jvmGcMeterCountAfterFirst = jvmGcMeterNamesAfterFirst.size

                bindJvmAndProcessMetrics()
                val jvmGcMeterNamesAfterSecond = METRICS_REGISTRY.meters
                    .map { it.id.name }
                    .filter { it.startsWith("jvm.gc.") }
                    .toSet()

                jvmGcMeterNamesAfterFirst shouldContainAll setOf(
                    "jvm.gc.live.data.size",
                    "jvm.gc.max.data.size",
                    "jvm.gc.memory.allocated",
                    "jvm.gc.memory.promoted",
                    "jvm.gc.overhead",
                )
                jvmGcMeterNamesAfterSecond shouldBe jvmGcMeterNamesAfterFirst
                jvmGcMeterNamesAfterSecond shouldHaveSize jvmGcMeterCountAfterFirst
            }
        }
    })
