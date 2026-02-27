package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
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
    private val msPerConstitution: Long = 200L,
    private val regenAmount: Int = 1,
    private val manaBaseIntervalMs: Long = 3_000L,
    private val manaMinIntervalMs: Long = 1_000L,
    private val manaRegenAmount: Int = 1,
    private val msPerWisdom: Long = 200L,
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val markVitalsDirty: (SessionId) -> Unit = {},
) {
    private val lastRegenAtMs = mutableMapOf<SessionId, Long>()
    private val lastManaRegenAtMs = mutableMapOf<SessionId, Long>()

    fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        lastRegenAtMs.remove(oldSid)?.let { lastRegenAtMs[newSid] = it }
        lastManaRegenAtMs.remove(oldSid)?.let { lastManaRegenAtMs[newSid] = it }
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
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

            val sessionId = player.sessionId
            var didWork = false

            val bonuses = items.equipmentBonuses(sessionId)

            // HP regen
            if (player.hp >= player.maxHp) {
                lastRegenAtMs[sessionId] = now
            } else {
                val last = lastRegenAtMs.getOrPut(sessionId) { now }
                val interval = regenIntervalMs(player, bonuses.constitution)
                if (now - last >= interval) {
                    if (player.healHp(regenAmount)) {
                        markVitalsDirty(sessionId)
                    }
                    lastRegenAtMs[sessionId] = now
                    didWork = true
                }
            }

            // Mana regen
            if (player.mana >= player.maxMana) {
                lastManaRegenAtMs[sessionId] = now
            } else {
                val lastMana = lastManaRegenAtMs.getOrPut(sessionId) { now }
                val interval = manaRegenIntervalMs(player, bonuses.wisdom)
                if (now - lastMana >= interval) {
                    if (player.healMana(manaRegenAmount)) {
                        markVitalsDirty(sessionId)
                    }
                    lastManaRegenAtMs[sessionId] = now
                    didWork = true
                }
            }

            if (didWork) ran++
        }
    }

    private fun regenIntervalMs(player: PlayerState, equipCon: Int): Long {
        val totalCon = player.constitution + equipCon
        val conBonus = (totalCon - PlayerState.BASE_STAT).coerceAtLeast(0).toLong()
        return (baseIntervalMs - conBonus * msPerConstitution).coerceAtLeast(minIntervalMs)
    }

    private fun manaRegenIntervalMs(player: PlayerState, equipWis: Int): Long {
        val totalWis = player.wisdom + equipWis
        val wisBonus = (totalWis - PlayerState.BASE_STAT).coerceAtLeast(0).toLong()
        return (manaBaseIntervalMs - wisBonus * msPerWisdom).coerceAtLeast(manaMinIntervalMs)
    }
}
