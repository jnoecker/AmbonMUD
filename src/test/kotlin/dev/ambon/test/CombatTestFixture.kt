package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.CombatSystemCallbacks
import dev.ambon.engine.CombatSystemConfig
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
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
        minDamage: Int = 1,
        maxDamage: Int = 4,
        detailedFeedbackEnabled: Boolean = false,
        detailedFeedbackRoomBroadcastEnabled: Boolean = false,
        onMobRemoved: suspend (MobId, RoomId) -> Unit = { _, _ -> },
        progression: PlayerProgression = PlayerProgression(),
        metrics: GameMetrics = GameMetrics.noop(),
        onLevelUp: suspend (SessionId, Int) -> Unit = { _, _ -> },
        strDivisor: Int = 3,
        dexDodgePerPoint: Int = 2,
        maxDodgePercent: Int = 30,
        dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
        statusEffects: StatusEffectSystem? = null,
        onMobKilledByPlayer: suspend (SessionId, String) -> Unit = { _, _ -> },
        groupSystem: GroupSystem? = null,
        groupXpBonusPerMember: Double = 0.10,
        threatMultiplierWarrior: Double = 1.5,
        threatMultiplierDefault: Double = 1.0,
        healingThreatMultiplier: Double = 0.5,
        onRoomItemsChanged: suspend (RoomId) -> Unit = { _ -> },
    ): CombatSystem =
        CombatSystem(
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
                minDamage = minDamage,
                maxDamage = maxDamage,
                strDivisor = strDivisor,
                dexDodgePerPoint = dexDodgePerPoint,
                maxDodgePercent = maxDodgePercent,
                threatMultiplierWarrior = threatMultiplierWarrior,
                threatMultiplierDefault = threatMultiplierDefault,
                healingThreatMultiplier = healingThreatMultiplier,
                groupXpBonusPerMember = groupXpBonusPerMember,
                detailedFeedbackEnabled = detailedFeedbackEnabled,
                detailedFeedbackRoomBroadcastEnabled = detailedFeedbackRoomBroadcastEnabled,
            ),
            callbacks = CombatSystemCallbacks(
                onMobRemoved = onMobRemoved,
                onLevelUp = onLevelUp,
                onMobKilledByPlayer = onMobKilledByPlayer,
                onRoomItemsChanged = onRoomItemsChanged,
            ),
        )

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
