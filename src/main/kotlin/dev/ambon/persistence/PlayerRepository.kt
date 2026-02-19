package dev.ambon.persistence

interface PlayerRepository {
    suspend fun findByName(name: String): PlayerRecord?

    suspend fun findById(id: PlayerId): PlayerRecord?

    suspend fun create(
        name: String,
        startRoomId: dev.ambon.domain.ids.RoomId,
        nowEpochMs: Long,
        passwordHash: String,
    ): PlayerRecord

    suspend fun save(record: PlayerRecord)
}
