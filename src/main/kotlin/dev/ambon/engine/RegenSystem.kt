package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
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
    private val maxPlayersPerTick: Int = 50,
) {
    private val lastRegenAtMs = mutableMapOf<SessionId, Long>()

    fun onPlayerDisconnected(sessionId: SessionId) {
        lastRegenAtMs.remove(sessionId)
    }

    fun tick(maxPlayersPerTick: Int = this.maxPlayersPerTick) {
        val now = clock.millis()
        var ran = 0

        val list = players.allPlayers().toMutableList()
        list.shuffle(rng)

        for (player in list) {
            if (ran >= maxPlayersPerTick) break

            val sessionId = player.sessionId
            if (player.hp >= player.maxHp) {
                lastRegenAtMs[sessionId] = now
                continue
            }

            val last = lastRegenAtMs[sessionId] ?: now
            val interval = regenIntervalMs(player)
            if (now - last < interval) continue

            val updated = (player.hp + regenAmount).coerceAtMost(player.maxHp)
            if (updated != player.hp) {
                player.hp = updated
            }
            lastRegenAtMs[sessionId] = now
            ran++
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
