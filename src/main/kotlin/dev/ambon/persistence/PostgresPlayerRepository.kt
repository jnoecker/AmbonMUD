package dev.ambon.persistence

import dev.ambon.domain.character.PlayerClass
import dev.ambon.domain.character.PlayerRace
import dev.ambon.domain.ids.RoomId
import dev.ambon.metrics.GameMetrics
import io.micrometer.core.instrument.Timer
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
    override suspend fun findByName(name: String): PlayerRecord? {
        val sample = Timer.start()
        try {
            return newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.nameLower eq name.trim().lowercase() }
                    .firstOrNull()
                    ?.toPlayerRecord()
            }
        } finally {
            sample.stop(metrics.playerRepoLoadTimer)
        }
    }

    override suspend fun findById(id: PlayerId): PlayerRecord? {
        val sample = Timer.start()
        try {
            return newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable
                    .selectAll()
                    .where { PlayersTable.id eq id.value }
                    .firstOrNull()
                    ?.toPlayerRecord()
            }
        } finally {
            sample.stop(metrics.playerRepoLoadTimer)
        }
    }

    override suspend fun create(
        name: String,
        startRoomId: RoomId,
        nowEpochMs: Long,
        passwordHash: String,
        ansiEnabled: Boolean,
        playerClass: PlayerClass,
        playerRace: PlayerRace,
    ): PlayerRecord {
        val trimmed = name.trim()
        try {
            return newSuspendedTransaction(Dispatchers.IO, database) {
                val result =
                    PlayersTable.insert {
                        it[PlayersTable.name] = trimmed
                        it[nameLower] = trimmed.lowercase()
                        it[roomId] = startRoomId.value
                        it[createdAtEpochMs] = nowEpochMs
                        it[lastSeenEpochMs] = nowEpochMs
                        it[PlayersTable.passwordHash] = passwordHash
                        it[PlayersTable.ansiEnabled] = ansiEnabled
                        it[PlayersTable.playerClass] = playerClass.name
                        it[PlayersTable.playerRace] = playerRace.name
                    }

                PlayerRecord(
                    id = PlayerId(result[PlayersTable.id]),
                    name = trimmed,
                    roomId = startRoomId,
                    createdAtEpochMs = nowEpochMs,
                    lastSeenEpochMs = nowEpochMs,
                    passwordHash = passwordHash,
                    ansiEnabled = ansiEnabled,
                    playerClass = playerClass,
                    playerRace = playerRace,
                )
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
        val sample = Timer.start()
        try {
            newSuspendedTransaction(Dispatchers.IO, database) {
                PlayersTable.upsert(PlayersTable.id) {
                    it[id] = record.id.value
                    it[name] = record.name
                    it[nameLower] = record.name.lowercase()
                    it[roomId] = record.roomId.value
                    it[constitution] = record.constitution
                    it[level] = record.level
                    it[xpTotal] = record.xpTotal
                    it[createdAtEpochMs] = record.createdAtEpochMs
                    it[lastSeenEpochMs] = record.lastSeenEpochMs
                    it[passwordHash] = record.passwordHash
                    it[ansiEnabled] = record.ansiEnabled
                    it[isStaff] = record.isStaff
                    it[mana] = record.mana
                    it[maxMana] = record.maxMana
                    it[playerClass] = record.playerClass.name
                    it[playerRace] = record.playerRace.name
                }
            }
            metrics.onPlayerSave()
        } catch (e: Throwable) {
            metrics.onPlayerSaveFailure()
            throw e
        } finally {
            sample.stop(metrics.playerRepoSaveTimer)
        }
    }

    private fun ResultRow.toPlayerRecord() =
        PlayerRecord(
            id = PlayerId(this[PlayersTable.id]),
            name = this[PlayersTable.name],
            roomId = RoomId(this[PlayersTable.roomId]),
            constitution = this[PlayersTable.constitution],
            level = this[PlayersTable.level],
            xpTotal = this[PlayersTable.xpTotal],
            createdAtEpochMs = this[PlayersTable.createdAtEpochMs],
            lastSeenEpochMs = this[PlayersTable.lastSeenEpochMs],
            passwordHash = this[PlayersTable.passwordHash],
            ansiEnabled = this[PlayersTable.ansiEnabled],
            isStaff = this[PlayersTable.isStaff],
            mana = this[PlayersTable.mana],
            maxMana = this[PlayersTable.maxMana],
            playerClass = PlayerClass.fromString(this[PlayersTable.playerClass]) ?: PlayerClass.WARRIOR,
            playerRace = PlayerRace.fromString(this[PlayersTable.playerRace]) ?: PlayerRace.HUMAN,
        )
}
