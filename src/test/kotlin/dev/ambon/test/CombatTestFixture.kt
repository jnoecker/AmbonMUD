package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.CombatSystemCallbacks
import dev.ambon.engine.CombatSystemConfig
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.LevelUpResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PetSystem
import dev.ambon.engine.PlayerClassRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.InMemoryPlayerRepository
import java.time.Clock
import java.util.Random

/**
 * Stat bindings that disable level scaling, stat scaling, and variance so a
 * single basic-attack swing produces exactly [unarmedAttackPower] damage
 * against a 0-armor target. Used by tests that pin specific damage numbers
 * without caring about the new scaling formula.
 */
fun deterministicMeleeBindings(unarmedAttackPower: Int = 1): StatBindingsConfig =
    StatBindingsConfig(
        meleeStatMultiplier = 0.0,
        meleeLevelScalingRate = 1.0,
        meleeVarianceMin = 1.0,
        meleeVarianceMax = 1.0,
        meleeBaseAttackPower = unarmedAttackPower,
        spellStatMultiplier = 0.0,
        spellLevelScalingRate = 1.0,
        spellVarianceMin = 1.0,
        spellVarianceMax = 1.0,
        healStatMultiplier = 0.0,
        healLevelScalingRate = 1.0,
        healVarianceMin = 1.0,
        healVarianceMax = 1.0,
    )

class CombatTestFixture(
    override val roomId: RoomId = TEST_ROOM_ID,
    val clock: MutableClock = MutableClock(0L),
    val items: ItemRegistry = ItemRegistry(),
    val repo: InMemoryPlayerRepository = InMemoryPlayerRepository(),
    override val players: PlayerRegistry = buildTestPlayerRegistry(roomId, repo, items, clock = clock),
    override val mobs: MobRegistry = MobRegistry(),
    val outbound: LocalOutboundBus = LocalOutboundBus(),
) : TestFixtureBase {
    fun buildCombat(
        players: PlayerRegistry = this.players,
        mobs: MobRegistry = this.mobs,
        items: ItemRegistry = this.items,
        outbound: OutboundBus = this.outbound,
        clock: Clock = this.clock,
        rng: Random = Random(),
        tickMillis: Long = 1_000L,
        // Legacy knobs preserved for tests authored before the formula rework.
        // `minDamage`/`maxDamage` get translated into the new bindings as
        // unarmedAttackPower with deterministic-no-variance scaling so each
        // swing produces a predictable damage value matching the old behavior.
        minDamage: Int = 1,
        maxDamage: Int = 1,
        detailedFeedbackEnabled: Boolean = false,
        detailedFeedbackRoomBroadcastEnabled: Boolean = false,
        onMobRemoved: suspend (MobId, RoomId) -> Unit = { _, _ -> },
        progression: PlayerProgression = PlayerProgression(),
        metrics: GameMetrics = GameMetrics.noop(),
        onLevelUp: suspend (SessionId, LevelUpResult) -> Unit = { _, _ -> },
        bindings: StatBindingsConfig = deterministicMeleeBindings(unarmedAttackPower = minDamage),
        dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
        statusEffects: StatusEffectSystem? = null,
        onMobKilledByPlayer: suspend (SessionId, String) -> Unit = { _, _ -> },
        groupSystem: GroupSystem? = null,
        groupXpBonusPerMember: Double = 0.10,
        healingThreatMultiplier: Double = 0.5,
        classRegistry: PlayerClassRegistry? = null,
        onRoomItemsChanged: suspend (RoomId) -> Unit = { _ -> },
        petSystem: PetSystem? = null,
    ): CombatSystem {
        require(minDamage == maxDamage) {
            "Test fixture damage range collapsed to a single value (was $minDamage..$maxDamage). " +
                "If you need variance, pass an explicit `bindings = StatBindingsConfig(...)`."
        }
        return CombatSystem(
            players = players,
            mobs = mobs,
            items = items,
            outbound = outbound,
            clock = clock,
            rng = rng,
            progression = progression,
            metrics = metrics,
            dirtyNotifier = dirtyNotifier,
            statusEffects = statusEffects,
            groupSystem = groupSystem,
            config = CombatSystemConfig(
                tickMillis = tickMillis,
                healingThreatMultiplier = healingThreatMultiplier,
                groupXpBonusPerMember = groupXpBonusPerMember,
                detailedFeedbackEnabled = detailedFeedbackEnabled,
                detailedFeedbackRoomBroadcastEnabled = detailedFeedbackRoomBroadcastEnabled,
                bindings = bindings,
            ),
            callbacks = CombatSystemCallbacks(
                onMobRemoved = onMobRemoved,
                onLevelUp = onLevelUp,
                onMobKilledByPlayer = onMobKilledByPlayer,
                onRoomItemsChanged = onRoomItemsChanged,
            ),
            classRegistry = classRegistry,
            petSystem = petSystem,
        )
    }

    /**
     * Advance the clock by one combat tick interval and run combat.tick().
     */
    suspend fun tickCombat(
        combat: CombatSystem,
        tickMillis: Long = 1_000L,
    ) {
        clock.advance(tickMillis)
        combat.tick()
    }

    /**
     * Place an item in the room, pick it up, and equip it for the given session.
     */
    fun equipItem(
        sessionId: SessionId,
        instance: ItemInstance,
        roomId: RoomId = this.roomId,
    ) {
        items.addRoomItem(roomId, instance)
        val moved = items.takeFromRoom(sessionId, roomId, instance.item.keyword)
        requireNotNull(moved) { "Expected to move item '${instance.item.keyword}' into inventory" }
        val result = items.equipFromInventory(sessionId, instance.item.keyword)
        require(result is ItemRegistry.EquipResult.Equipped) { "Expected to equip '${instance.item.keyword}', got $result" }
    }

    /**
     * Build a [StatusEffectSystem] wired to this fixture's registries,
     * with the given effect definitions pre-registered.
     */
    fun buildStatusEffects(
        vararg definitions: StatusEffectDefinition,
        rng: Random = Random(1),
    ): StatusEffectSystem {
        val registry = StatusEffectRegistry()
        definitions.forEach { registry.register(it) }
        return StatusEffectSystem(
            registry = registry,
            players = players,
            mobs = mobs,
            outbound = outbound,
            clock = clock,
            rng = rng,
            dirtyNotifier = DirtyNotifier.NO_OP,
        )
    }
}

/**
 * Place an item in the room, pick it up, and equip it.
 * Standalone variant for use outside [CombatTestFixture].
 */
fun equipItemForTest(
    items: ItemRegistry,
    sessionId: SessionId,
    roomId: RoomId,
    instance: ItemInstance,
) {
    items.addRoomItem(roomId, instance)
    val moved = items.takeFromRoom(sessionId, roomId, instance.item.keyword)
    requireNotNull(moved) { "Expected to move item '${instance.item.keyword}' into inventory" }
    val result = items.equipFromInventory(sessionId, instance.item.keyword)
    require(result is ItemRegistry.EquipResult.Equipped) { "Expected to equip '${instance.item.keyword}', got $result" }
}
