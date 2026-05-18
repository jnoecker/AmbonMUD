package dev.ambon.persistence

import dev.ambon.metrics.GameMetrics
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

class PostgresPlayerRepository(
    private val database: Database,
    private val metrics: GameMetrics = GameMetrics.noop(),
) : PlayerRepository {
    override suspend fun findByName(name: String): PlayerRecord? =
        metrics.timedLoad {
            database.dbQuery {
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.nameLower eq name.trim().lowercase() }
                    .firstOrNull()
                    ?.let { PlayersTable.readRecord(it) }
            }
        }

    override suspend fun findById(id: PlayerId): PlayerRecord? =
        metrics.timedLoad {
            database.dbQuery {
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.id eq id.value }
                    .firstOrNull()
                    ?.let { PlayersTable.readRecord(it) }
            }
        }

    override suspend fun findByAuthTokenHash(hash: String): PlayerRecord? {
        if (hash.isBlank()) return null
        return metrics.timedLoad {
            database.dbQuery {
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.authTokenHash eq hash }
                    .firstOrNull()
                    ?.let { PlayersTable.readRecord(it) }
            }
        }
    }

    override suspend fun create(request: PlayerCreationRequest): PlayerRecord {
        val trimmed = request.name.trim()
        // Build the full prospective record with a placeholder id, then write every
        // column in a single insert so starter equipment / gold / gender land on
        // disk atomically — if the caller never finishes binding the session, the
        // row still has the right state for the next login.
        val prospective = request.toNewPlayerRecord(PlayerId(0))
        try {
            return database.dbQuery {
                val result =
                    PlayersTable.insert {
                        PlayersTable.writeRecord(it, prospective, includeId = false)
                        it[nameLower] = trimmed.lowercase()
                    }
                prospective.copy(id = PlayerId(result[PlayersTable.id]))
            }
        } catch (e: ExposedSQLException) {
            // SQL state 23505 = unique_violation (works across Postgres versions)
            val sqlState = e.sqlState
            if (sqlState == "23505") {
                throw PersistenceException("Name already taken: '$trimmed'", e)
            }
            throw e
        }
    }

    override suspend fun save(record: PlayerRecord) {
        metrics.timedSave {
            database.dbQuery {
                PlayersTable.upsert(PlayersTable.id) {
                    PlayersTable.writeRecord(it, record)
                }
            }
        }
    }

    override suspend fun findAll(): List<PlayerRecord> =
        database.dbQuery {
            PlayersTable.selectAll().map { PlayersTable.readRecord(it) }
        }
}
