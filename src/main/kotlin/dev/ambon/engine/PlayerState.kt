package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.persistence.PlayerId

data class PlayerState(
    val sessionId: SessionId,
    var name: String,
    var roomId: RoomId,
    var playerId: PlayerId? = null,
    var hp: Int = BASE_MAX_HP,
    var maxHp: Int = BASE_MAX_HP,
    var constitution: Int = 0,
    var level: Int = 1,
    var xpTotal: Long = 0L,
) {
    companion object {
        const val BASE_MAX_HP = 10
    }
}
