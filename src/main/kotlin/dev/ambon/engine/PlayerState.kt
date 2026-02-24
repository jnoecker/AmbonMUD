package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.persistence.PlayerId

data class PlayerState(
    val sessionId: SessionId,
    var name: String,
    var roomId: RoomId,
    var playerId: PlayerId? = null,
    var baseMaxHp: Int = BASE_MAX_HP,
    var hp: Int = BASE_MAX_HP,
    var maxHp: Int = BASE_MAX_HP,
    var strength: Int = BASE_STAT,
    var dexterity: Int = BASE_STAT,
    var constitution: Int = BASE_STAT,
    var intelligence: Int = BASE_STAT,
    var wisdom: Int = BASE_STAT,
    var charisma: Int = BASE_STAT,
    var race: String = "HUMAN",
    var playerClass: String = "WARRIOR",
    var level: Int = 1,
    var xpTotal: Long = 0L,
    var ansiEnabled: Boolean = false,
    var isStaff: Boolean = false,
    var mana: Int = BASE_MANA,
    var maxMana: Int = BASE_MANA,
    var baseMana: Int = BASE_MANA,
    var gold: Long = 0L,
) {
    companion object {
        const val BASE_MAX_HP = 10
        const val BASE_MANA = 20
        const val BASE_STAT = 10
    }
}
