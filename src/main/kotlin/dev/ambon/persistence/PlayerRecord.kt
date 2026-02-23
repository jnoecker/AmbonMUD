package dev.ambon.persistence

import dev.ambon.domain.character.PlayerClass
import dev.ambon.domain.character.PlayerRace
import dev.ambon.domain.ids.RoomId

@JvmInline
value class PlayerId(
    val value: Long,
)

data class PlayerRecord(
    val id: PlayerId,
    val name: String,
    val roomId: RoomId,
    val constitution: Int = 0,
    val level: Int = 1,
    val xpTotal: Long = 0L,
    val createdAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val passwordHash: String = "",
    val ansiEnabled: Boolean = false,
    val isStaff: Boolean = false,
    val mana: Int = 20,
    val maxMana: Int = 20,
    val playerClass: PlayerClass = PlayerClass.WARRIOR,
    val playerRace: PlayerRace = PlayerRace.HUMAN,
)
