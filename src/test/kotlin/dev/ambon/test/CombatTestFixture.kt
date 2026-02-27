package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.persistence.InMemoryPlayerRepository
import java.time.Clock
import java.util.Random

class CombatTestFixture(
    val roomId: RoomId = RoomId("zone:room"),
    val clock: MutableClock = MutableClock(0L),
    val items: ItemRegistry = ItemRegistry(),
    val repo: InMemoryPlayerRepository = InMemoryPlayerRepository(),
    val players: PlayerRegistry = PlayerRegistry(roomId, repo, items),
    val mobs: MobRegistry = MobRegistry(),
    val outbound: LocalOutboundBus = LocalOutboundBus(),
) {
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
            tickMillis = tickMillis,
            minDamage = minDamage,
            maxDamage = maxDamage,
            detailedFeedbackEnabled = detailedFeedbackEnabled,
            detailedFeedbackRoomBroadcastEnabled = detailedFeedbackRoomBroadcastEnabled,
            onMobRemoved = onMobRemoved,
            progression = progression,
            metrics = metrics,
            onLevelUp = onLevelUp,
            strDivisor = strDivisor,
            dexDodgePerPoint = dexDodgePerPoint,
            maxDodgePercent = maxDodgePercent,
            dirtyNotifier = dirtyNotifier,
            statusEffects = statusEffects,
            onMobKilledByPlayer = onMobKilledByPlayer,
            groupSystem = groupSystem,
            groupXpBonusPerMember = groupXpBonusPerMember,
            threatMultiplierWarrior = threatMultiplierWarrior,
            threatMultiplierDefault = threatMultiplierDefault,
            healingThreatMultiplier = healingThreatMultiplier,
            onRoomItemsChanged = onRoomItemsChanged,
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
        hp: Int = 10,
        maxHp: Int = hp,
        minDamage: Int = 1,
        maxDamage: Int = 4,
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
                minDamage = minDamage,
                maxDamage = maxDamage,
                armor = armor,
                xpReward = xpReward,
                goldMin = goldMin,
                goldMax = goldMax,
            )
        mobs.upsert(mob)
        return mob
    }
}
