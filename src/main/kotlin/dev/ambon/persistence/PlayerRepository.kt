package dev.ambon.persistence

import dev.ambon.domain.ids.RoomId

/**
 * Bundles all fields required to create a new [PlayerRecord].
 * Defaults match the server's base character statistics.
 */
data class PlayerCreationRequest(
    val name: String,
    val startRoomId: RoomId,
    val nowEpochMs: Long,
    val passwordHash: String,
    val ansiEnabled: Boolean,
    val race: String = "HUMAN",
    val playerClass: String = "WARRIOR",
    val strength: Int = 10,
    val dexterity: Int = 10,
    val constitution: Int = 10,
    val intelligence: Int = 10,
    val wisdom: Int = 10,
    val charisma: Int = 10,
)

interface PlayerRepository {
    suspend fun findByName(name: String): PlayerRecord?

    suspend fun findById(id: PlayerId): PlayerRecord?

    suspend fun create(request: PlayerCreationRequest): PlayerRecord

    suspend fun save(record: PlayerRecord)
}
