package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.CombatSystemConfig
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PetSystem
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import java.util.Random

class AbilityTestFixture(
    override val roomId: RoomId = TEST_ROOM_ID,
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
        // Default to no-variance, no-level-scaling bindings so spell-damage tests pin
        // exact damage values without modeling the production scaling curve.
        bindings: StatBindingsConfig = deterministicMeleeBindings(),
        progression: PlayerProgression = PlayerProgression(bindings = bindings),
        petSystem: PetSystem? = null,
        onCombatEvent: suspend (SessionId, CombatEvent) -> Unit = { _, _ -> },
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
            petSystem = petSystem,
            bindings = bindings,
            progression = progression,
            onCombatEvent = onCombatEvent,
        )
}
