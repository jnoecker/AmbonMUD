package dev.ambon.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.RoomId
import dev.ambon.redis.RedisConnectionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

class RedisCachingPlayerRepository(
    private val delegate: PlayerRepository,
    private val redisManager: RedisConnectionManager,
    private val cacheTtlSeconds: Long,
) : PlayerRepository {
    private data class PlayerJson(
        val id: Long,
        val name: String,
        val roomId: String,
        val constitution: Int = 0,
        val level: Int = 1,
        val xpTotal: Long = 0L,
        val createdAtEpochMs: Long,
        val lastSeenEpochMs: Long,
        val passwordHash: String = "",
        val ansiEnabled: Boolean = false,
        val isStaff: Boolean = false,
    )

    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun nameKey(name: String) = "player:name:${name.lowercase()}"

    private fun idKey(id: Long) = "player:id:$id"

    override suspend fun findByName(name: String): PlayerRecord? {
        val cmds = redisManager.commands
        if (cmds != null) {
            try {
                val record =
                    withContext(Dispatchers.IO) {
                        val idStr = cmds.get(nameKey(name)) ?: return@withContext null
                        val id = idStr.toLongOrNull() ?: return@withContext null
                        val json = cmds.get(idKey(id)) ?: return@withContext null
                        mapper.readValue<PlayerJson>(json).toDomain()
                    }
                if (record != null) return record
            } catch (e: Exception) {
                log.warn(e) { "Redis error in findByName($name) — falling through to delegate" }
            }
        }
        val record = delegate.findByName(name)
        if (record != null) cacheRecord(record)
        return record
    }

    override suspend fun findById(id: PlayerId): PlayerRecord? {
        val cmds = redisManager.commands
        if (cmds != null) {
            try {
                val record =
                    withContext(Dispatchers.IO) {
                        val json = cmds.get(idKey(id.value)) ?: return@withContext null
                        mapper.readValue<PlayerJson>(json).toDomain()
                    }
                if (record != null) return record
            } catch (e: Exception) {
                log.warn(e) { "Redis error in findById($id) — falling through to delegate" }
            }
        }
        val record = delegate.findById(id)
        if (record != null) cacheRecord(record)
        return record
    }

    override suspend fun create(
        name: String,
        startRoomId: RoomId,
        nowEpochMs: Long,
        passwordHash: String,
        ansiEnabled: Boolean,
    ): PlayerRecord {
        val record = delegate.create(name, startRoomId, nowEpochMs, passwordHash, ansiEnabled)
        cacheRecord(record)
        return record
    }

    override suspend fun save(record: PlayerRecord) {
        delegate.save(record)
        cacheRecord(record)
    }

    private suspend fun cacheRecord(record: PlayerRecord) {
        val cmds = redisManager.commands ?: return
        try {
            withContext(Dispatchers.IO) {
                val json =
                    mapper.writeValueAsString(
                        PlayerJson(
                            id = record.id.value,
                            name = record.name,
                            roomId = record.roomId.value,
                            constitution = record.constitution,
                            level = record.level,
                            xpTotal = record.xpTotal,
                            createdAtEpochMs = record.createdAtEpochMs,
                            lastSeenEpochMs = record.lastSeenEpochMs,
                            passwordHash = record.passwordHash,
                            ansiEnabled = record.ansiEnabled,
                            isStaff = record.isStaff,
                        ),
                    )
                cmds.setex(idKey(record.id.value), cacheTtlSeconds, json)
                cmds.setex(nameKey(record.name), cacheTtlSeconds, record.id.value.toString())
            }
        } catch (e: Exception) {
            log.warn(e) { "Redis error caching player ${record.name} — ignoring" }
        }
    }

    private fun PlayerJson.toDomain(): PlayerRecord =
        PlayerRecord(
            id = PlayerId(id),
            name = name,
            roomId = RoomId(roomId),
            constitution = constitution,
            level = level,
            xpTotal = xpTotal,
            createdAtEpochMs = createdAtEpochMs,
            lastSeenEpochMs = lastSeenEpochMs,
            passwordHash = passwordHash,
            ansiEnabled = ansiEnabled,
            isStaff = isStaff,
        )
}
