package no.nav.syfo.application.database

import com.zaxxer.hikari.HikariConfig
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.metric.METRICS_REGISTRY

class DatabaseMetricRegistryTest :
    DescribeSpec({
        it("accepts the shared meter registry through Hikari's Object-typed metricRegistry setter") {
            val metricRegistry: Any = METRICS_REGISTRY
            val hikariConfig = HikariConfig()

            hikariConfig.metricRegistry = metricRegistry

            hikariConfig.metricRegistry shouldBe METRICS_REGISTRY
        }
    })
