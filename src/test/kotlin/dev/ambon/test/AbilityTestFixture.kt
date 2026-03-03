package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.CombatSystemConfig
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
    override val roomId: RoomId = RoomId("zone:room"),
    val clock: MutableClock = MutableClock(0L),
    val rng: Random = Random(42),
    val items: ItemRegistry = ItemRegistry(),
    val repo: InMemoryPlayerRepository = InMemoryPlayerRepository(),
    override val players: PlayerRegistry = buildTestPlayerRegistry(roomId, repo, items, clock = clock),
    override val mobs: MobRegistry = MobRegistry(),
    val outbound: LocalOutboundBus = LocalOutboundBus(),
) : TestFixtureBase {
    val combat: CombatSystem =
        CombatSystem(
            players = players,
            mobs = mobs,
            items = items,
            outbound = outbound,
            clock = clock,
            rng = rng,
            config = CombatSystemConfig(tickMillis = 1_000L),
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
}
