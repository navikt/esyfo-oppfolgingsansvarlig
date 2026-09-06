package no.nav.syfo.narmesteleder.db

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.util.logger
import java.sql.Timestamp
import java.time.Instant
import java.util.*

interface INarmestelederDb {
    suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): NarmestelederBehovEntity
    suspend fun updateNlBehov(nlBehov: NarmestelederBehovEntity)
    suspend fun findBehovById(id: UUID): NarmestelederBehovEntity?
    suspend fun findBehovByParameters(
        sykmeldtFnr: String,
        orgnummer: String,
        behovStatus: List<BehovStatus>
    ): List<NarmestelederBehovEntity>

    suspend fun getNlBehovByStatus(status: BehovStatus, limit: Int = 100) = getNlBehovByStatus(listOf(status), limit)
    suspend fun findBehovByParameters(
        orgNumber: String,
        createdAfter: Instant,
        status: List<BehovStatus>,
        limit: Int
    ): List<NarmestelederBehovEntity>

    suspend fun countBehovByParameters(
        orgNumber: String,
        createdAfter: Instant,
        status: List<BehovStatus>,
    ): Long

    suspend fun setBehovStatusForSykmeldingWithTomBeforeAndStatus(
        tomBefore: Instant,
        newStatus: BehovStatus,
        fromStatus: List<BehovStatus>,
        limit: Int = 500
    ): Int

    suspend fun getNlBehovByStatus(status: List<BehovStatus>, limit: Int = 100): List<NarmestelederBehovEntity>

    suspend fun getNlBehovForExpireInDialogporten(limit: Int = 100, status: List<BehovStatus>): List<NarmestelederBehovEntity>
    suspend fun getNlBehovForExpireInDialogporten(limit: Int = 100, status: BehovStatus) = getNlBehovForExpireInDialogporten(limit, listOf(status))
}

class NarmestelederDb(
    private val database: DatabaseInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : INarmestelederDb {
    override suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): NarmestelederBehovEntity = withContext(dispatcher) {
        return@withContext database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO nl_behov(orgnummer,
                                         hovedenhet_orgnummer,
                                         sykemeldt_fnr,
                                         narmeste_leder_fnr,
                                         behov_reason,
                                         behov_status,
                                         avbrutt_narmesteleder_id,
                                         fornavn,
                                         mellomnavn,
                                         etternavn)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING *;
                    """.trimIndent()
                ).use { preparedStatement ->
                    preparedStatement.setString(1, nlBehov.orgnummer)
                    preparedStatement.setString(2, nlBehov.hovedenhetOrgnummer)
                    preparedStatement.setString(3, nlBehov.sykmeldtFnr)
                    preparedStatement.setString(4, nlBehov.narmestelederFnr)
                    preparedStatement.setString(5, nlBehov.behovReason.name)
                    preparedStatement.setObject(6, nlBehov.behovStatus, java.sql.Types.OTHER)
                    preparedStatement.setObject(7, nlBehov.avbruttNarmesteLederId)
                    preparedStatement.setString(8, nlBehov.fornavn)
                    preparedStatement.setString(9, nlBehov.mellomnavn)
                    preparedStatement.setString(10, nlBehov.etternavn)
                    preparedStatement.execute()

                    runCatching {
                        if (preparedStatement.resultSet.next()) {
                            preparedStatement.resultSet.toNarmestelederBehovEntity()
                        } else {
                            throw NarmestelederBehovEntityInsertException("Could not get the inserted document.")
                        }
                    }.getOrElse {
                        connection.rollback()
                        throw it
                    }
                }.also {
                    connection.commit()
                }
        }
    }

    override suspend fun updateNlBehov(nlBehov: NarmestelederBehovEntity): Unit = withContext(dispatcher) {
        database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE nl_behov
                    SET orgnummer            = ?,
                        hovedenhet_orgnummer = ?,
                        sykemeldt_fnr        = ?,
                        narmeste_leder_fnr   = ?,
                        behov_status         = ?,
                        dialog_id            = ?,
                        fornavn              = ?,
                        mellomnavn           = ?,
                        etternavn            = ?,
                        dialog_delete_performed = ?,
                        expired_in_dialogporten = ?
                        
                        WHERE id = ?;
                    """.trimIndent()
                ).use { preparedStatement ->
                    with(nlBehov) {
                        preparedStatement.setString(1, orgnummer)
                        preparedStatement.setString(2, hovedenhetOrgnummer)
                        preparedStatement.setString(3, sykmeldtFnr)
                        preparedStatement.setString(4, narmestelederFnr)
                        preparedStatement.setObject(5, behovStatus, java.sql.Types.OTHER)
                        preparedStatement.setObject(6, dialogId)
                        preparedStatement.setString(7, fornavn)
                        preparedStatement.setString(8, mellomnavn)
                        preparedStatement.setString(9, etternavn)
                        dialogDeletePerformed?.let {
                            preparedStatement.setTimestamp(10, Timestamp.from(dialogDeletePerformed))
                        } ?: preparedStatement.setObject(10, null)
                        expiredInDialogporten?.let {
                            preparedStatement.setTimestamp(11, Timestamp.from(expiredInDialogporten))
                        } ?: preparedStatement.setObject(11, null)
                        preparedStatement.setObject(12, id)
                    }
                    preparedStatement.executeUpdate()
                }.also {
                    connection.commit()
                }
        }
    }

    override suspend fun findBehovById(id: UUID): NarmestelederBehovEntity? = withContext(dispatcher) {
        return@withContext database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                       SELECT * FROM nl_behov WHERE id = ?;
                    """.trimIndent()
                ).use { preparedStatement ->
                    preparedStatement.setObject(1, id)

                    preparedStatement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            resultSet.toNarmestelederBehovEntity()
                        } else {
                            null
                        }
                    }
                }
        }
    }

    override suspend fun findBehovByParameters(
        sykmeldtFnr: String,
        orgnummer: String,
        behovStatus: List<BehovStatus>
    ): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        val placeholders = behovStatus.joinToString(", ") { "?" }
        return@withContext database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                        SELECT * FROM nl_behov 
                          WHERE orgnummer = ? AND 
                              sykemeldt_fnr = ? AND
                              behov_status IN ($placeholders) 
                    """.trimIndent()
                ).use { preparedStatement ->
                    var idx = 1
                    preparedStatement.setObject(idx++, orgnummer)
                    preparedStatement.setObject(idx++, sykmeldtFnr)
                    behovStatus.forEach { status ->
                        preparedStatement.setObject(idx++, status, java.sql.Types.OTHER)
                    }

                    preparedStatement.executeQuery().use { resultSet ->
                        val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                        while (resultSet.next()) {
                            nlBehov.add(resultSet.toNarmestelederBehovEntity())
                        }
                        nlBehov
                    }
                }
        }
    }

    override suspend fun getNlBehovByStatus(status: BehovStatus, limit: Int): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        return@withContext database.connection.use { connection ->
            connection
                // Add AND created < NOW() - INTERVAL '1 minute' in where clause if we add something that triggers sending immediately after insert
                .prepareStatement(
                    """
                        SELECT *
                        FROM nl_behov
                        WHERE behov_status = ?
                        AND created < NOW() - INTERVAL '10 second'
                        ORDER BY created
                        LIMIT ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    preparedStatement.setObject(1, status, java.sql.Types.OTHER)
                    preparedStatement.setInt(2, limit)
                    val resultSet = preparedStatement.executeQuery()
                    val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                    while (resultSet.next()) {
                        nlBehov.add(resultSet.toNarmestelederBehovEntity())
                    }
                    nlBehov
                }
        }
    }

    override suspend fun findBehovByParameters(
        orgNumber: String,
        createdAfter: Instant,
        status: List<BehovStatus>,
        limit: Int
    ): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        return@withContext database.connection.use { connection ->
            val placeholders = status.joinToString(", ") { "?" }
            connection
                .prepareStatement(
                    """
                        SELECT *
                        FROM nl_behov
                        WHERE 
                            orgnummer = ?
                        AND
                            behov_status in ($placeholders) 
                        AND
                            created > ? 
                        ORDER BY created
                        LIMIT ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    var idx = 1
                    preparedStatement.setString(idx++, orgNumber)
                    status.forEach { status ->
                        preparedStatement.setObject(idx++, status, java.sql.Types.OTHER)
                    }
                    preparedStatement.setTimestamp(idx++, Timestamp.from(createdAfter))
                    preparedStatement.setInt(idx++, limit)
                    val resultSet = preparedStatement.executeQuery()
                    val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                    while (resultSet.next()) {
                        nlBehov.add(resultSet.toNarmestelederBehovEntity())
                    }
                    nlBehov
                }
        }
    }

    override suspend fun countBehovByParameters(
        orgNumber: String,
        createdAfter: Instant,
        status: List<BehovStatus>,
    ): Long = withContext(dispatcher) {
        if (status.isEmpty()) return@withContext 0L
        return@withContext database.connection.use { connection ->
            val placeholders = status.joinToString(", ") { "?" }
            connection
                .prepareStatement(
                    """
                        SELECT COUNT(*) AS total
                        FROM nl_behov
                        WHERE
                            orgnummer = ?
                        AND
                            behov_status in ($placeholders)
                        AND
                            created > ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    var idx = 1
                    preparedStatement.setString(idx++, orgNumber)
                    status.forEach { status ->
                        preparedStatement.setObject(idx++, status, java.sql.Types.OTHER)
                    }
                    preparedStatement.setTimestamp(idx, Timestamp.from(createdAfter))
                    preparedStatement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.getLong("total") else 0L
                    }
                }
        }
    }

    override suspend fun setBehovStatusForSykmeldingWithTomBeforeAndStatus(
        tomBefore: Instant,
        newStatus: BehovStatus,
        fromStatus: List<BehovStatus>,
        limit: Int
    ): Int = withContext(dispatcher) {
        if (fromStatus.isEmpty()) return@withContext 0

        database.connection.use { connection ->
            val fromStatusPlaceholders = fromStatus.joinToString(", ") { "?" }

            connection.prepareStatement(
                """
                WITH expired_behov AS (
                    SELECT eb.id 
                        FROM nl_behov eb
                        JOIN sendt_sykmelding es ON eb.sykemeldt_fnr = es.fnr AND eb.orgnummer = es.orgnummer
                        WHERE es.tom < ?
                        AND eb.behov_status IN ($fromStatusPlaceholders)
                        ORDER BY eb.created
                        LIMIT ? 
                )
                UPDATE nl_behov b
                SET behov_status = ?
                FROM expired_behov e
                WHERE b.id = e.id
                """.trimIndent()
            ).use { preparedStatement ->
                var idx = 0
                preparedStatement.setTimestamp(++idx, Timestamp.from(tomBefore))
                fromStatus.forEach { status ->
                    preparedStatement.setObject(++idx, status, java.sql.Types.OTHER)
                }
                preparedStatement.setInt(++idx, limit)
                preparedStatement.setObject(++idx, newStatus, java.sql.Types.OTHER)
                preparedStatement.executeUpdate().also {
                    connection.commit()
                }
            }
        }
    }

    override suspend fun getNlBehovByStatus(
        status: List<BehovStatus>,
        limit: Int
    ): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        if (status.isEmpty()) return@withContext emptyList()

        val placeholders = status.joinToString(", ") { "?" }

        return@withContext database.connection.use { connection ->
            connection
                // Add AND created < NOW() - INTERVAL '1 minute' in where clause if we add something that triggers sending immediately after insert
                .prepareStatement(
                    """
                        SELECT *
                        FROM nl_behov
                        WHERE behov_status IN ($placeholders)
                        AND created < NOW() - INTERVAL '10 second'
                        ORDER BY created
                        LIMIT ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    var idx = 0
                    status.forEach { s ->
                        preparedStatement.setObject(++idx, s, java.sql.Types.OTHER)
                    }
                    preparedStatement.setInt(++idx, limit)
                    val resultSet = preparedStatement.executeQuery()
                    val nlBehov = mutableListOf<NarmestelederBehovEntity>()
                    while (resultSet.next()) {
                        nlBehov.add(resultSet.toNarmestelederBehovEntity())
                    }
                    nlBehov
                }
        }
    }

    override suspend fun getNlBehovForExpireInDialogporten(
        limit: Int,
        status: List<BehovStatus>,
    ): List<NarmestelederBehovEntity> = withContext(dispatcher) {
        if (status.isEmpty()) error("At least one BehovStatus must be set")

        val placeholders = status.joinToString(", ") { "?" }
        return@withContext database.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                        SELECT *
                        FROM nl_behov
                        WHERE expired_in_dialogporten IS NULL AND dialog_id IS NOT NULL 
                        AND behov_status IN ($placeholders)
                        ORDER BY created
                        LIMIT ?
                    """.trimIndent()
                ).use { preparedStatement ->
                    var idx = 0
                    status.forEach { s ->
                        preparedStatement.setObject(++idx, s, java.sql.Types.OTHER)
                    }
                    limit.coerceIn(1, MAX_LIMIT).also {
                        if (it != limit) {
                            logger().warn("Overriding limit value. Provided limit: $limit, used limit: $it. Allowed range is: 1 - $MAX_LIMIT")
                        }
                        preparedStatement.setInt(++idx, it)
                    }
                    val resultSet = preparedStatement.executeQuery()
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toNarmestelederBehovEntity())
                        }
                    }
                }
        }
    }
    companion object {
        const val MAX_LIMIT = 500
    }
}

class NarmestelederBehovEntityInsertException(message: String) : RuntimeException(message)
