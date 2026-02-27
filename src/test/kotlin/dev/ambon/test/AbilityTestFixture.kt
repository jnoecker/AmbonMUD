package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import java.util.Random

class AbilityTestFixture(
    val roomId: RoomId = RoomId("zone:room"),
    val clock: MutableClock = MutableClock(0L),
    val rng: Random = Random(42),
    val items: ItemRegistry = ItemRegistry(),
    val repo: InMemoryPlayerRepository = InMemoryPlayerRepository(),
    val players: PlayerRegistry = buildTestPlayerRegistry(roomId, repo, items, clock = clock),
    val mobs: MobRegistry = MobRegistry(),
    val outbound: LocalOutboundBus = LocalOutboundBus(),
) {
    val combat: CombatSystem =
        CombatSystem(
            players = players,
            mobs = mobs,
            items = items,
            outbound = outbound,
            clock = clock,
            rng = rng,
            tickMillis = 1_000L,
        )

    fun buildAbilitySystem(
        registry: AbilityRegistry,
        statusEffects: StatusEffectSystem? = null,
        dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
        mobsForAbility: MobRegistry? = null,
    ): AbilitySystem =
        AbilitySystem(
            players = players,
            registry = registry,
            outbound = outbound,
            combat = combat,
            clock = clock,
            rng = rng,
            statusEffects = statusEffects,
            dirtyNotifier = dirtyNotifier,
            mobs = mobsForAbility ?: mobs,
        )

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
        hp: Int = 20,
        maxHp: Int = hp,
        minDamage: Int = 1,
        maxDamage: Int = 4,
        roomId: RoomId = this.roomId,
    ): MobState {
        val mob =
            MobState(
                id = id,
                name = name,
                roomId = roomId,
                hp = hp,
                maxHp = maxHp,
                minDamage = minDamage,
                maxDamage = maxDamage,
            )
        mobs.upsert(mob)
        return mob
    }
}
