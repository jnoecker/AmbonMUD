package dev.ambon.engine

import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.toStatMap
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.metrics.GameMetrics
import java.time.Clock
import java.util.Random

class RegenSystem(
    private val players: PlayerRegistry,
    private val items: ItemRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val rng: Random = Random(),
    private val baseIntervalMs: Long = 5_000L,
    private val minIntervalMs: Long = 1_000L,
    private val regenAmount: Int = 1,
    private val manaBaseIntervalMs: Long = 3_000L,
    private val manaMinIntervalMs: Long = 1_000L,
    private val manaRegenAmount: Int = 1,
    private val bindings: StatBindingsConfig = StatBindingsConfig(),
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
) : GameSystem {
    private val lastRegenAtMs = mutableMapOf<SessionId, Long>()
    private val lastManaRegenAtMs = mutableMapOf<SessionId, Long>()

    override fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        lastRegenAtMs.remapKey(oldSid, newSid)
        lastManaRegenAtMs.remapKey(oldSid, newSid)
    }

    override suspend fun onPlayerDisconnected(sessionId: SessionId) {
        lastRegenAtMs.remove(sessionId)
        lastManaRegenAtMs.remove(sessionId)
    }

    fun tick(maxPlayersPerTick: Int = 50) {
        val now = clock.millis()
        var ran = 0

        val list = players.allPlayers()
        if (list.isEmpty()) return
        val start = rng.nextInt(list.size)

        for (i in list.indices) {
            if (ran >= maxPlayersPerTick) break
            val player = list[(start + i) % list.size]
            ran++

            val sessionId = player.sessionId
            val equipStats = items.equipmentBonuses(sessionId).stats.toStatMap()

            applyRegen(
                now = now,
                sessionId = sessionId,
                tracker = lastRegenAtMs,
                current = player.hp,
                max = player.maxHp,
                intervalMs = regenIntervalMs(player, equipStats[bindings.hpRegenStat]),
                heal = { player.healHp(regenAmount) },
            )

            applyRegen(
                now = now,
                sessionId = sessionId,
                tracker = lastManaRegenAtMs,
                current = player.mana,
                max = player.maxMana,
                intervalMs = manaRegenIntervalMs(player, equipStats[bindings.manaRegenStat]),
                heal = { player.healMana(manaRegenAmount) },
            )
        }
    }

    private inline fun applyRegen(
        now: Long,
        sessionId: SessionId,
        tracker: MutableMap<SessionId, Long>,
        current: Int,
        max: Int,
        intervalMs: Long,
        heal: () -> Boolean,
    ) {
        if (current >= max) {
            tracker[sessionId] = now
        } else {
            val last = tracker.getOrPut(sessionId) { now }
            if (now - last >= intervalMs) {
                if (heal()) {
                    dirtyNotifier.playerVitalsDirty(sessionId)
                }
                tracker[sessionId] = now
            }
        }
    }

    private fun regenIntervalMs(player: PlayerState, equipBonus: Int): Long =
        regenInterval(
            player.getStat(bindings.hpRegenStat) + equipBonus,
            baseIntervalMs,
            bindings.hpRegenMsPerPoint,
            minIntervalMs,
        )

    private fun manaRegenIntervalMs(player: PlayerState, equipBonus: Int): Long =
        regenInterval(
            player.getStat(bindings.manaRegenStat) + equipBonus,
            manaBaseIntervalMs,
            bindings.manaRegenMsPerPoint,
            manaMinIntervalMs,
        )

    private fun regenInterval(totalStat: Int, baseMs: Long, msPerPoint: Long, minMs: Long): Long {
        val bonus = (totalStat - PlayerState.BASE_STAT).coerceAtLeast(0).toLong()
        return (baseMs - bonus * msPerPoint).coerceAtLeast(minMs)
    }
}
