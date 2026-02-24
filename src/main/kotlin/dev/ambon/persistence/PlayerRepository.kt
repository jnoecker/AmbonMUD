package dev.ambon.persistence

interface PlayerRepository {
    suspend fun findByName(name: String): PlayerRecord?

    suspend fun findById(id: PlayerId): PlayerRecord?

    suspend fun create(
        name: String,
        startRoomId: dev.ambon.domain.ids.RoomId,
        nowEpochMs: Long,
        passwordHash: String,
        ansiEnabled: Boolean,
        race: String = "HUMAN",
        playerClass: String = "WARRIOR",
        strength: Int = 10,
        dexterity: Int = 10,
        constitution: Int = 10,
        intelligence: Int = 10,
        wisdom: Int = 10,
        charisma: Int = 10,
    ): PlayerRecord

    suspend fun save(record: PlayerRecord)
}
