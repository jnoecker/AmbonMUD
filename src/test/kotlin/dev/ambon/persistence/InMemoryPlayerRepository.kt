package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId
import java.util.concurrent.atomic.AtomicLong

class InMemoryPlayerRepository : PlayerRepository {
    private val players = mutableMapOf<PlayerId, PlayerRecord>()
    private val nextId = AtomicLong(1)

    override suspend fun findByName(name: String): PlayerRecord? = players.values.find { it.name.equals(name, ignoreCase = true) }

    override suspend fun findById(id: PlayerId): PlayerRecord? = players[id]

    override suspend fun create(
        name: String,
        startRoomId: RoomId,
        nowEpochMs: Long,
    ): PlayerRecord {
        val id = PlayerId(nextId.getAndIncrement())
        val record =
            PlayerRecord(
                id = id,
                name = name,
                roomId = startRoomId,
                createdAtEpochMs = nowEpochMs,
                lastSeenEpochMs = nowEpochMs,
            )
        players[id] = record
        return record
    }

    override suspend fun save(record: PlayerRecord) {
        players[record.id] = record
    }

    override suspend fun delete(id: PlayerId) {
        players.remove(id)
    }
}
