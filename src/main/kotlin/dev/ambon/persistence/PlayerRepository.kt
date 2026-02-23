package dev.ambon.persistence

import dev.ambon.domain.character.PlayerClass
import dev.ambon.domain.character.PlayerRace

interface PlayerRepository {
    suspend fun findByName(name: String): PlayerRecord?

    suspend fun findById(id: PlayerId): PlayerRecord?

    suspend fun create(
        name: String,
        startRoomId: dev.ambon.domain.ids.RoomId,
        nowEpochMs: Long,
        passwordHash: String,
        ansiEnabled: Boolean,
        playerClass: PlayerClass = PlayerClass.WARRIOR,
        playerRace: PlayerRace = PlayerRace.HUMAN,
    ): PlayerRecord

    suspend fun save(record: PlayerRecord)
}
