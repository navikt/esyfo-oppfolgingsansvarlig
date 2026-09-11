package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch
import no.nav.syfo.application.events.LeaderChange
import no.nav.syfo.application.events.LeaderChangeEvent
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.util.logger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

const val LEADER_STATUS_METRIC = "${METRICS_NS}_leader_status"

private val leaderStatusStates = Collections.synchronizedMap(
    WeakHashMap<MeterRegistry, AtomicReference<StateFlow<Boolean>?>>()
)

fun Application.configureLeaderMonitoring(
    leaderChangeSSEListener: LeaderChangeSSEListener,
    leaderElection: LeaderElection,
) {
    val log = logger()

    registerLeaderStatusGauge(leaderChangeSSEListener)
    monitor.subscribe(ApplicationStopPreparing) {
        clearLeaderStatusGauge(leaderChangeSSEListener)
    }

    monitor.subscribe(ServerReady) {
        log.info("Starting leader monitoring")
        val job = launch {
            var wasLeader = false
            launch(start = CoroutineStart.UNDISPATCHED) {
                leaderChangeSSEListener.isLeader.collectIndexed { index, isLeader ->
                    val event = deriveLeaderChangeEvent(index, wasLeader, isLeader)
                    wasLeader = isLeader

                    if (event == null) return@collectIndexed

                    log.info("Leader change event: {}", event::class.simpleName)
                    monitor.raise(LeaderChangeEvent, event)
                }
            }

            runCatching {
                leaderElection.isLeader()
            }.onSuccess { initialIsLeader ->
                leaderChangeSSEListener.initializeLeaderState(initialIsLeader)
                log.info("Initialized leader state from simple API: isLeader={}", initialIsLeader)
            }.onFailure { exception ->
                log.error(
                    "Failed to initialize leader state from simple API; continuing leader monitoring with SSE listener",
                    exception
                )
            }

            log.info("Before listenForLeaderChanges - Application.configureLeaderMonitoring")
            launch { leaderChangeSSEListener.listenForLeaderChanges() }
        }

        monitor.subscribe(ApplicationStopPreparing) {
            job.cancel()
        }
    }
}

internal fun deriveLeaderChange(wasLeader: Boolean, isLeader: Boolean): LeaderChange = when {
    !wasLeader && isLeader -> LeaderChange.Promoted
    wasLeader && !isLeader -> LeaderChange.Demoted
    else -> LeaderChange.Unaffected
}

internal fun deriveLeaderChangeEvent(index: Int, wasLeader: Boolean, isLeader: Boolean): LeaderChange? {
    val event = deriveLeaderChange(wasLeader, isLeader)

    return if (index == 0 && event is LeaderChange.Unaffected) {
        null
    } else {
        event
    }
}

internal fun registerLeaderStatusGauge(
    leaderChangeSSEListener: LeaderChangeSSEListener,
    registry: MeterRegistry = METRICS_REGISTRY,
): Gauge {
    val leaderStatusState = leaderStatusState(registry)
    leaderStatusState.set(leaderChangeSSEListener.isLeader)

    return Gauge.builder(LEADER_STATUS_METRIC, leaderStatusState) { state ->
        if (state.get()?.value == true) 1.0 else 0.0
    }
        .description("Whether this pod is currently the elected leader (1) or not (0)")
        .strongReference(true)
        .register(registry)
}

internal fun clearLeaderStatusGauge(
    leaderChangeSSEListener: LeaderChangeSSEListener,
    registry: MeterRegistry = METRICS_REGISTRY,
) {
    leaderStatusState(registry).compareAndSet(leaderChangeSSEListener.isLeader, null)
}

private fun leaderStatusState(registry: MeterRegistry): AtomicReference<StateFlow<Boolean>?> = synchronized(leaderStatusStates) {
    leaderStatusStates.getOrPut(registry) { AtomicReference() }
}
