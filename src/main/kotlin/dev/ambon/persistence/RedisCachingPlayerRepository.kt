package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.RoomId
import dev.ambon.redis.redisObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

class RedisCachingPlayerRepository(
    private val delegate: PlayerRepository,
    private val cache: StringCache,
    private val cacheTtlSeconds: Long,
    private val mapper: ObjectMapper = redisObjectMapper,
) : PlayerRepository {
    private data class PlayerJson(
        val id: Long,
        val name: String,
        val roomId: String,
        val strength: Int = 10,
        val dexterity: Int = 10,
        val constitution: Int = 10,
        val intelligence: Int = 10,
        val wisdom: Int = 10,
        val charisma: Int = 10,
        val race: String = "HUMAN",
        val playerClass: String = "WARRIOR",
        val level: Int = 1,
        val xpTotal: Long = 0L,
        val createdAtEpochMs: Long,
        val lastSeenEpochMs: Long,
        val passwordHash: String = "",
        val ansiEnabled: Boolean = false,
        val isStaff: Boolean = false,
        val mana: Int = 20,
        val maxMana: Int = 20,
    )

    private fun nameKey(name: String) = "player:name:${name.lowercase()}"

    private fun idKey(id: Long) = "player:id:$id"

    override suspend fun findByName(name: String): PlayerRecord? {
        try {
            val record =
                withContext(Dispatchers.IO) {
                    val idStr = cache.get(nameKey(name)) ?: return@withContext null
                    val id = idStr.toLongOrNull() ?: return@withContext null
                    val json = cache.get(idKey(id)) ?: return@withContext null
                    mapper.readValue<PlayerJson>(json).toDomain()
                }
            if (record != null) return record
        } catch (e: Exception) {
            log.warn(e) { "Redis error in findByName($name) - falling through to delegate" }
        }

        val record = delegate.findByName(name)
        if (record != null) cacheRecord(record)
        return record
    }

    override suspend fun findById(id: PlayerId): PlayerRecord? {
        try {
            val record =
                withContext(Dispatchers.IO) {
                    val json = cache.get(idKey(id.value)) ?: return@withContext null
                    mapper.readValue<PlayerJson>(json).toDomain()
                }
            if (record != null) return record
        } catch (e: Exception) {
            log.warn(e) { "Redis error in findById($id) - falling through to delegate" }
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
        race: String,
        playerClass: String,
        strength: Int,
        dexterity: Int,
        constitution: Int,
        intelligence: Int,
        wisdom: Int,
        charisma: Int,
    ): PlayerRecord {
        val record =
            delegate.create(
                name,
                startRoomId,
                nowEpochMs,
                passwordHash,
                ansiEnabled,
                race,
                playerClass,
                strength,
                dexterity,
                constitution,
                intelligence,
                wisdom,
                charisma,
            )
        cacheRecord(record)
        return record
    }

    override suspend fun save(record: PlayerRecord) {
        delegate.save(record)
        cacheRecord(record)
    }

    private suspend fun cacheRecord(record: PlayerRecord) {
        try {
            withContext(Dispatchers.IO) {
                val json =
                    mapper.writeValueAsString(
                        PlayerJson(
                            id = record.id.value,
                            name = record.name,
                            roomId = record.roomId.value,
                            strength = record.strength,
                            dexterity = record.dexterity,
                            constitution = record.constitution,
                            intelligence = record.intelligence,
                            wisdom = record.wisdom,
                            charisma = record.charisma,
                            race = record.race,
                            playerClass = record.playerClass,
                            level = record.level,
                            xpTotal = record.xpTotal,
                            createdAtEpochMs = record.createdAtEpochMs,
                            lastSeenEpochMs = record.lastSeenEpochMs,
                            passwordHash = record.passwordHash,
                            ansiEnabled = record.ansiEnabled,
                            isStaff = record.isStaff,
                            mana = record.mana,
                            maxMana = record.maxMana,
                        ),
                    )
                cache.setEx(idKey(record.id.value), cacheTtlSeconds, json)
                cache.setEx(nameKey(record.name), cacheTtlSeconds, record.id.value.toString())
            }
        } catch (e: Exception) {
            log.warn(e) { "Redis error caching player ${record.name} - ignoring" }
        }
    }

    private fun PlayerJson.toDomain(): PlayerRecord {
        // Legacy migration: old cache entries stored constitution=0; remap to base 10
        val migratedCon = if (constitution == 0) 10 else constitution
        return PlayerRecord(
            id = PlayerId(id),
            name = name,
            roomId = RoomId(roomId),
            strength = strength,
            dexterity = dexterity,
            constitution = migratedCon,
            intelligence = intelligence,
            wisdom = wisdom,
            charisma = charisma,
            race = race,
            playerClass = playerClass,
            level = level,
            xpTotal = xpTotal,
            createdAtEpochMs = createdAtEpochMs,
            lastSeenEpochMs = lastSeenEpochMs,
            passwordHash = passwordHash,
            ansiEnabled = ansiEnabled,
            isStaff = isStaff,
            mana = mana,
            maxMana = maxMana,
        )
    }
}
