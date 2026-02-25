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

            // HP regen
            if (player.hp >= player.maxHp) {
                lastRegenAtMs[sessionId] = now
            } else {
                val last = lastRegenAtMs[sessionId] ?: now
                val interval = regenIntervalMs(player)
                if (now - last >= interval) {
                    val updated = (player.hp + regenAmount).coerceAtMost(player.maxHp)
                    if (updated != player.hp) {
                        player.hp = updated
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
                val lastMana = lastManaRegenAtMs[sessionId] ?: now
                val interval = manaRegenIntervalMs(player)
                if (now - lastMana >= interval) {
                    val updated = (player.mana + manaRegenAmount).coerceAtMost(player.maxMana)
                    if (updated != player.mana) {
                        player.mana = updated
                        markVitalsDirty(sessionId)
                    }
                    lastManaRegenAtMs[sessionId] = now
                    didWork = true
                }
            }

            if (didWork) ran++
        }
    }

    private fun regenIntervalMs(player: PlayerState): Long {
        val totalCon = totalConstitution(player)
        val conBonus = (totalCon - PlayerState.BASE_STAT).coerceAtLeast(0).toLong()
        val interval = baseIntervalMs - (conBonus * msPerConstitution)
        return interval.coerceAtLeast(minIntervalMs)
    }

    private fun manaRegenIntervalMs(player: PlayerState): Long {
        val equipWis = items.equipment(player.sessionId).values.sumOf { it.item.wisdom }
        val totalWis = player.wisdom + equipWis
        val wisBonus = (totalWis - PlayerState.BASE_STAT).coerceAtLeast(0).toLong()
        val interval = manaBaseIntervalMs - (wisBonus * msPerWisdom)
        return interval.coerceAtLeast(manaMinIntervalMs)
    }

    private fun totalConstitution(player: PlayerState): Int {
        val equipmentConstitution = items.equipment(player.sessionId).values.sumOf { it.item.constitution }
        val total = player.constitution + equipmentConstitution
        return total.coerceAtLeast(0)
    }
}
