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
    private val metrics: GameMetrics = GameMetrics.noop(),
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

        val list = players.allPlayers().toMutableList()
        list.shuffle(rng)

        for (player in list) {
            if (ran >= maxPlayersPerTick) break

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
                if (now - lastMana >= manaBaseIntervalMs) {
                    val updated = (player.mana + manaRegenAmount).coerceAtMost(player.maxMana)
                    if (updated != player.mana) {
                        player.mana = updated
                    }
                    lastManaRegenAtMs[sessionId] = now
                    didWork = true
                }
            }

            if (didWork) ran++
        }
    }

    private fun regenIntervalMs(player: PlayerState): Long {
        val totalConstitution = totalConstitution(player).toLong()
        val interval = baseIntervalMs - (totalConstitution * msPerConstitution)
        return interval.coerceAtLeast(minIntervalMs)
    }

    private fun totalConstitution(player: PlayerState): Int {
        val equipmentConstitution = items.equipment(player.sessionId).values.sumOf { it.item.constitution }
        val total = player.constitution + equipmentConstitution
        return total.coerceAtLeast(0)
    }
}
