package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import dev.ambon.metrics.GameMetrics
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
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
                    ?.toPlayerRecord()
            }
        }

    override suspend fun findById(id: PlayerId): PlayerRecord? =
        metrics.timedLoad {
            newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.id eq id.value }
                    .firstOrNull()
                    ?.toPlayerRecord()
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
                        it[PlayersTable.strength] = request.strength
                        it[PlayersTable.dexterity] = request.dexterity
                        it[PlayersTable.constitution] = request.constitution
                        it[PlayersTable.intelligence] = request.intelligence
                        it[PlayersTable.wisdom] = request.wisdom
                        it[PlayersTable.charisma] = request.charisma
                    }

                request.toNewPlayerRecord(PlayerId(result[PlayersTable.id]))
            }
        } catch (e: Exception) {
            // Unique-index violation on name_lower → treat as duplicate name
            if (e.message?.contains("idx_players_name_lower", ignoreCase = true) == true) {
                throw PlayerPersistenceException("Name already taken: '$trimmed'", e)
            }
            throw e
        }
    }

    override suspend fun save(record: PlayerRecord) {
        metrics.timedSave {
            newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable.upsert(PlayersTable.id) {
                    it[id] = record.id.value
                    it[name] = record.name
                    it[nameLower] = record.name.lowercase()
                    it[roomId] = record.roomId.value
                    it[strength] = record.strength
                    it[dexterity] = record.dexterity
                    it[constitution] = record.constitution
                    it[intelligence] = record.intelligence
                    it[wisdom] = record.wisdom
                    it[charisma] = record.charisma
                    it[race] = record.race
                    it[playerClass] = record.playerClass
                    it[level] = record.level
                    it[xpTotal] = record.xpTotal
                    it[createdAtEpochMs] = record.createdAtEpochMs
                    it[lastSeenEpochMs] = record.lastSeenEpochMs
                    it[passwordHash] = record.passwordHash
                    it[ansiEnabled] = record.ansiEnabled
                    it[isStaff] = record.isStaff
                    it[mana] = record.mana
                    it[maxMana] = record.maxMana
                    it[gold] = record.gold
                }
            }
        }
    }

    private fun ResultRow.toPlayerRecord(): PlayerRecord {
        val rawCon = this[PlayersTable.constitution]
        return PlayerRecord(
            id = PlayerId(this[PlayersTable.id]),
            name = this[PlayersTable.name],
            roomId = RoomId(this[PlayersTable.roomId]),
            strength = this[PlayersTable.strength],
            dexterity = this[PlayersTable.dexterity],
            constitution = if (rawCon == 0) 10 else rawCon,
            intelligence = this[PlayersTable.intelligence],
            wisdom = this[PlayersTable.wisdom],
            charisma = this[PlayersTable.charisma],
            race = this[PlayersTable.race],
            playerClass = this[PlayersTable.playerClass],
            level = this[PlayersTable.level],
            xpTotal = this[PlayersTable.xpTotal],
            createdAtEpochMs = this[PlayersTable.createdAtEpochMs],
            lastSeenEpochMs = this[PlayersTable.lastSeenEpochMs],
            passwordHash = this[PlayersTable.passwordHash],
            ansiEnabled = this[PlayersTable.ansiEnabled],
            isStaff = this[PlayersTable.isStaff],
            mana = this[PlayersTable.mana],
            maxMana = this[PlayersTable.maxMana],
            gold = this[PlayersTable.gold],
        )
    }
}
