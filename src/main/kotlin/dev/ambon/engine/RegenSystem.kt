package dev.ambon.engine

import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.metrics.GameMetrics
import java.time.Clock
import java.util.Random

/** How regen is credited between the engine's polls (`ambonMUD.engine.regen.model`). */
enum class RegenModel {
    /**
     * One fixed heal per elapsed interval, judged at each poll. Because the poll runs once per
     * master tick, intervals shorter than the tick are unreachable and stat investment saturates.
     */
    DISCRETE,

    /**
     * Every poll credits `amount * elapsed / interval` to a per-player fractional accumulator and
     * heals its integer part, so a stat-shortened interval keeps paying out below the poll cadence.
     * Averages the same as DISCRETE whenever the interval is a multiple of the poll.
     */
    RATE,
    ;

    companion object {
        fun parse(value: String): RegenModel =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown regen model '$value' (expected discrete or rate)")
    }
}

class RegenSystem(
    private val players: PlayerRegistry,
    private val items: ItemRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val rng: Random = Random(),
    private val baseIntervalMs: Long = 5_000L,
    private val minIntervalMs: Long = 1_000L,
    private val hpRegenPercent: Double = 0.05,
    private val manaBaseIntervalMs: Long = 3_000L,
    private val manaMinIntervalMs: Long = 1_000L,
    private val manaRegenPercent: Double = 0.05,
    private val inCombatMultiplier: Double = 0.5,
    /** In-combat multiplier for mana only; null inherits [inCombatMultiplier]. */
    private val manaInCombatMultiplier: Double? = null,
    private val inCombat: (SessionId) -> Boolean = { false },
    private val innMultiplier: Double = 2.0,
    private val inInn: (SessionId) -> Boolean = { false },
    private val model: RegenModel = RegenModel.DISCRETE,
    private val tickIntervalMs: Long = 100L,
    private val cycleTargetMs: Long = 2_000L,
    private val minPlayersPerTick: Int = 5,
    private val maxPlayersPerTick: Int = 200,
    private val bindings: StatBindingsConfig = StatBindingsConfig(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
    private val classRegistry: PlayerClassRegistry? = null,
) : GameSystem {
    private val scoped = SessionScoped()
    private val lastRegenAtMs = scoped.map<Long>()
    private val lastManaRegenAtMs = scoped.map<Long>()

    // RATE model only: fractional regen accrued but not yet healed, per resource.
    private val hpCarry = scoped.map<Double>()
    private val manaCarry = scoped.map<Double>()

    override fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) = scoped.remap(oldSid, newSid)

    override suspend fun onPlayerDisconnected(sessionId: SessionId) = scoped.clear(sessionId)

    fun tick(capOverride: Int? = null) {
        val now = clock.millis()
        var ran = 0

        val list = players.allPlayers()
        if (list.isEmpty()) return

        val cap = capOverride ?: adaptiveCap(list.size)
        if (cap <= 0) return

        val start = rng.nextInt(list.size)

        for (i in list.indices) {
            if (ran >= cap) break
            val player = list[(start + i) % list.size]
            ran++

            val sessionId = player.sessionId
            val equipStats = items.equipmentBonuses(sessionId, classRegistry?.get(player.playerClass)).stats
            val fighting = inCombat(sessionId)
            val innMult = if (inInn(sessionId)) innMultiplier else 1.0
            val hpMultiplier = (if (fighting) inCombatMultiplier else 1.0) * innMult
            val manaMultiplier = (if (fighting) (manaInCombatMultiplier ?: inCombatMultiplier) else 1.0) * innMult

            applyRegen(
                now = now,
                sessionId = sessionId,
                tracker = lastRegenAtMs,
                carry = hpCarry,
                current = player.hp,
                max = player.maxHp,
                intervalMs = regenIntervalMs(player, equipStats[bindings.hpRegenStat]),
                percent = hpRegenPercent,
                multiplier = hpMultiplier,
                heal = { amount -> player.healHp(amount) },
            )

            applyRegen(
                now = now,
                sessionId = sessionId,
                tracker = lastManaRegenAtMs,
                carry = manaCarry,
                current = player.mana,
                max = player.maxMana,
                intervalMs = manaRegenIntervalMs(player, equipStats[bindings.manaRegenStat]),
                percent = manaRegenPercent,
                multiplier = manaMultiplier,
                heal = { amount -> player.healMana(amount) },
            )
        }
    }

    private fun regenAmount(max: Int, percent: Double, multiplier: Double): Int {
        val raw = max * percent * multiplier
        // Guarantee a minimum of 1 whenever the math yields any positive amount,
        // so low-pool resources (e.g. starting mana of 10 at 5%) still tick.
        // A multiplier or percent of exactly 0 still yields 0 (no regen).
        return if (raw <= 0.0) 0 else raw.toInt().coerceAtLeast(1)
    }

    private inline fun applyRegen(
        now: Long,
        sessionId: SessionId,
        tracker: MutableMap<SessionId, Long>,
        carry: MutableMap<SessionId, Double>,
        current: Int,
        max: Int,
        intervalMs: Long,
        percent: Double,
        multiplier: Double,
        heal: (Int) -> Boolean,
    ) {
        if (current >= max) {
            tracker[sessionId] = now
            carry.remove(sessionId)
            return
        }
        val last = tracker.getOrPut(sessionId) { now }
        when (model) {
            RegenModel.DISCRETE -> {
                if (now - last >= intervalMs) {
                    if (heal(regenAmount(max, percent, multiplier))) {
                        dirtyNotifier.playerVitalsDirty(sessionId)
                    }
                    tracker[sessionId] = now
                }
            }
            RegenModel.RATE -> {
                val elapsed = now - last
                if (elapsed <= 0L) return
                val raw = max * percent * multiplier
                val credit = if (raw <= 0.0 || intervalMs <= 0L) 0.0 else raw * elapsed / intervalMs
                val accrued = (carry[sessionId] ?: 0.0) + credit
                val whole = accrued.toInt()
                if (whole >= 1 && heal(whole)) {
                    dirtyNotifier.playerVitalsDirty(sessionId)
                }
                carry[sessionId] = accrued - whole
                tracker[sessionId] = now
            }
        }
    }

    private fun regenIntervalMs(player: PlayerState, equipBonus: Int): Long =
        regenInterval(
            player.stats[bindings.hpRegenStat] + equipBonus,
            baseIntervalMs,
            bindings.hpRegenMsPerPoint,
            minIntervalMs,
        )

    private fun manaRegenIntervalMs(player: PlayerState, equipBonus: Int): Long =
        regenInterval(
            player.stats[bindings.manaRegenStat] + equipBonus,
            manaBaseIntervalMs,
            bindings.manaRegenMsPerPoint,
            manaMinIntervalMs,
        )

    private fun regenInterval(totalStat: Int, baseMs: Long, msPerPoint: Long, minMs: Long): Long {
        val bonus = (totalStat - PlayerState.BASE_STAT).coerceAtLeast(0).toLong()
        return (baseMs - bonus * msPerPoint).coerceAtLeast(minMs)
    }

    internal fun adaptiveCap(playerCount: Int): Int {
        if (playerCount <= 0) return 0
        val raw = ((playerCount.toLong() * tickIntervalMs + cycleTargetMs - 1) / cycleTargetMs).toInt()
        return raw.coerceIn(minPlayersPerTick, maxPlayersPerTick)
    }
}
