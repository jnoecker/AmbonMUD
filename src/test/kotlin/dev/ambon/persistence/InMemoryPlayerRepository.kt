package dev.ambon.persistence

import java.util.concurrent.atomic.AtomicLong

class InMemoryPlayerRepository : PlayerRepository {
    private val players = mutableMapOf<PlayerId, PlayerRecord>()
    private val nextId = AtomicLong(1)

    override suspend fun findByName(name: String): PlayerRecord? = players.values.find { it.name.equals(name, ignoreCase = true) }

    override suspend fun findById(id: PlayerId): PlayerRecord? = players[id]

    override suspend fun create(request: PlayerCreationRequest): PlayerRecord {
        val id = PlayerId(nextId.getAndIncrement())
        val record =
            PlayerRecord(
                id = id,
                name = request.name,
                roomId = request.startRoomId,
                createdAtEpochMs = request.nowEpochMs,
                lastSeenEpochMs = request.nowEpochMs,
                passwordHash = request.passwordHash,
                ansiEnabled = request.ansiEnabled,
                race = request.race,
                playerClass = request.playerClass,
                strength = request.strength,
                dexterity = request.dexterity,
                constitution = request.constitution,
                intelligence = request.intelligence,
                wisdom = request.wisdom,
                charisma = request.charisma,
            )
        players[id] = record
        return record
    }

    override suspend fun save(record: PlayerRecord) {
        players[record.id] = record
    }

    fun clear() {
        players.clear()
    }
}
