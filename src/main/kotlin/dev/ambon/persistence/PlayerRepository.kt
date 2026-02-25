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

/**
 * Constructs a brand-new [PlayerRecord] from this request, assigning the given [id].
 * All repositories should use this to avoid diverging default logic.
 */
fun PlayerCreationRequest.toNewPlayerRecord(id: PlayerId): PlayerRecord =
    PlayerRecord(
        id = id,
        name = name.trim(),
        roomId = startRoomId,
        createdAtEpochMs = nowEpochMs,
        lastSeenEpochMs = nowEpochMs,
        passwordHash = passwordHash,
        ansiEnabled = ansiEnabled,
        race = race,
        playerClass = playerClass,
        strength = strength,
        dexterity = dexterity,
        constitution = constitution,
        intelligence = intelligence,
        wisdom = wisdom,
        charisma = charisma,
    )

interface PlayerRepository {
    suspend fun findByName(name: String): PlayerRecord?

    suspend fun findById(id: PlayerId): PlayerRecord?

    suspend fun create(request: PlayerCreationRequest): PlayerRecord

    suspend fun save(record: PlayerRecord)
}
