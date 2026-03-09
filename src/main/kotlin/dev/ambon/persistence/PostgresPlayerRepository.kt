package dev.ambon.persistence

import dev.ambon.metrics.GameMetrics
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert

class PostgresPlayerRepository(
    private val database: Database,
    private val metrics: GameMetrics = GameMetrics.noop(),
) : PlayerRepository {
    override suspend fun findByName(name: String): PlayerRecord? =
        metrics.timedLoad {
            newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.nameLower eq name.trim().lowercase() }
                    .firstOrNull()
                    ?.let { PlayersTable.readRecord(it) }
            }
        }

    override suspend fun findById(id: PlayerId): PlayerRecord? =
        metrics.timedLoad {
            newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.id eq id.value }
                    .firstOrNull()
                    ?.let { PlayersTable.readRecord(it) }
            }
        }

    override suspend fun create(request: PlayerCreationRequest): PlayerRecord {
        val trimmed = request.name.trim()
        try {
            return newSuspendedTransaction(Dispatchers.IO, database) {
                val result =
                    PlayersTable.insert {
                        it[PlayersTable.name] = trimmed
                        it[nameLower] = trimmed.lowercase()
                        it[roomId] = request.startRoomId.value
                        it[createdAtEpochMs] = request.nowEpochMs
                        it[lastSeenEpochMs] = request.nowEpochMs
                        it[PlayersTable.passwordHash] = request.passwordHash
                        it[PlayersTable.ansiEnabled] = request.ansiEnabled
                        it[PlayersTable.race] = request.race
                        it[PlayersTable.playerClass] = request.playerClass
                        it[PlayersTable.statsJson] = jsonMapper.writeValueAsString(request.stats)
                    }

                request.toNewPlayerRecord(PlayerId(result[PlayersTable.id]))
            }
        } catch (e: Exception) {
            // Unique-index violation on name_lower → treat as duplicate name
            if (e.message?.contains("idx_players_name_lower", ignoreCase = true) == true) {
                throw PersistenceException("Name already taken: '$trimmed'", e)
            }
            throw e
        }
    }

    override suspend fun save(record: PlayerRecord) {
        metrics.timedSave {
            newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable.upsert(PlayersTable.id) {
                    PlayersTable.writeRecord(it, record)
                }
            }
        }
    }
}
