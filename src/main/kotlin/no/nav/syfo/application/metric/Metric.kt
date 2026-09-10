package no.nav.syfo.application.metric

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmCompilationMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics
import io.micrometer.core.instrument.binder.jvm.JvmInfoMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.atomic.AtomicBoolean

const val METRICS_NS = "syfo-narmesteleder"

val METRICS_REGISTRY = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

private val jvmAndProcessMetricsBound = AtomicBoolean(false)

fun bindJvmAndProcessMetrics() {
    if (!jvmAndProcessMetricsBound.compareAndSet(false, true)) return

    JvmMemoryMetrics().bindTo(METRICS_REGISTRY)
    JvmGcMetrics().bindTo(METRICS_REGISTRY)
    JvmHeapPressureMetrics().bindTo(METRICS_REGISTRY)
    JvmThreadMetrics().bindTo(METRICS_REGISTRY)
    ClassLoaderMetrics().bindTo(METRICS_REGISTRY)
    JvmCompilationMetrics().bindTo(METRICS_REGISTRY)
    JvmInfoMetrics().bindTo(METRICS_REGISTRY)
    ProcessorMetrics().bindTo(METRICS_REGISTRY)
    UptimeMetrics().bindTo(METRICS_REGISTRY)
    FileDescriptorMetrics().bindTo(METRICS_REGISTRY)
}
