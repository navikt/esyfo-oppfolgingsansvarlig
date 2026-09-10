package no.nav.syfo.application.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import no.nav.syfo.application.environment.getEnvVar
import no.nav.syfo.application.metric.METRICS_REGISTRY
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
import java.sql.Connection

data class DatabaseConfig(
    val jdbcUrl: String,
    val password: String,
    val username: String,
    val poolSize: Int = 4,
    val flywayRepairEnabled: Boolean = getEnvVar("FLYWAY_REPAIR_ENABLED", "false").toBoolean(),
)

class Database(
    private val config: DatabaseConfig
) : DatabaseInterface {
    override val connection: Connection
        get() = dataSource.connection

    val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.poolSize
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            metricRegistry = METRICS_REGISTRY
            validate()
        }
    )

    init {
        runFlywayMigrations()
    }

    private fun runFlywayMigrations() = Flyway.configure().run {
        getConfigurationExtension(PostgreSQLConfigurationExtension::class.java).isTransactionalLock = false
        dataSource(
            config.jdbcUrl,
            config.username,
            config.password,
        )

//        cleanDisabled(false)
//        load().clean()
        val flyway = load()
        if (config.flywayRepairEnabled) {
            val failedVersions = flyway.info().all()
                .filter { it.state == MigrationState.FAILED }
                .map { it.version.toString() }

            require(failedVersions.all { it in REPAIRABLE_FLYWAY_VERSIONS }) {
                "FLYWAY_REPAIR_ENABLED can only repair failed migrations: ${REPAIRABLE_FLYWAY_VERSIONS.joinToString()}"
            }

            if (failedVersions.isNotEmpty()) {
                flyway.repair()
            }
        }
        flyway.migrate().migrationsExecuted
    }

    private companion object {
        // These drop index if exists before trying to create index, so they should be repairable.
        val REPAIRABLE_FLYWAY_VERSIONS = setOf("25", "26", "27")
    }
}

interface DatabaseInterface {
    val connection: Connection
}
