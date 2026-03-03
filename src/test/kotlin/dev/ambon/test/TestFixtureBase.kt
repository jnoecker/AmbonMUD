package dev.ambon.test

import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry

interface TestFixtureBase {
    val players: PlayerRegistry
    val mobs: MobRegistry
    val roomId: RoomId

    suspend fun loginPlayer(
        sessionId: SessionId,
        name: String,
        password: String = "password",
    ) {
        players.loginOrFail(sessionId, name, password)
    }

    fun spawnMob(
        id: MobId,
        name: String,
        hp: Int = 10,
        maxHp: Int = hp,
        damage: DamageRange = DamageRange(1, 4),
        armor: Int = 0,
        xpReward: Long = 30L,
        goldMin: Long = 0L,
        goldMax: Long = 0L,
        roomId: RoomId = this.roomId,
    ): MobState {
        val mob =
            MobState(
                id = id,
                name = name,
                roomId = roomId,
                hp = hp,
                maxHp = maxHp,
                damage = damage,
                armor = armor,
                xpReward = xpReward,
                goldMin = goldMin,
                goldMax = goldMax,
            )
        mobs.upsert(mob)
        return mob
    }
}
