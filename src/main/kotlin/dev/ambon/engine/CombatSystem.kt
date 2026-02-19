package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import kotlinx.coroutines.channels.SendChannel
import java.time.Clock
import java.util.Random

class CombatSystem(
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val outbound: SendChannel<OutboundEvent>,
    private val clock: Clock = Clock.systemUTC(),
    private val rng: Random = Random(),
    private val tickMillis: Long = 1_000L,
    private val minDamage: Int = 1,
    private val maxDamage: Int = 4,
    private val onMobRemoved: (MobId) -> Unit = {},
) {
    private data class Fight(
        val sessionId: SessionId,
        val mobId: MobId,
        var nextTickAtMs: Long,
    )

    private val fightsByPlayer = mutableMapOf<SessionId, Fight>()
    private val fightsByMob = mutableMapOf<MobId, Fight>()
    private val defenseByPlayer = mutableMapOf<SessionId, Int>()

    fun isInCombat(sessionId: SessionId): Boolean = fightsByPlayer.containsKey(sessionId)

    fun isMobInCombat(mobId: MobId): Boolean = fightsByMob.containsKey(mobId)

    suspend fun startCombat(
        sessionId: SessionId,
        keywordRaw: String,
    ): String? {
        val player = players.get(sessionId) ?: return "You are not connected."
        val keyword = keywordRaw.trim()
        if (keyword.isEmpty()) return "Kill what?"

        val existing = fightsByPlayer[sessionId]
        if (existing != null) {
            val mobName = mobs.get(existing.mobId)?.name ?: "your target"
            return "You are already fighting $mobName."
        }

        val roomId = player.roomId
        val matches = findMobsInRoom(roomId, keyword)
        if (matches.isEmpty()) return "You don't see '$keyword' here."

        val mob =
            matches.firstOrNull { !fightsByMob.containsKey(it.id) }
                ?: return "${matches.first().name} is already fighting someone."

        val fight =
            Fight(
                sessionId = sessionId,
                mobId = mob.id,
                nextTickAtMs = clock.millis() + tickMillis,
            )
        fightsByPlayer[sessionId] = fight
        fightsByMob[mob.id] = fight

        outbound.send(OutboundEvent.SendText(sessionId, "You attack ${mob.name}."))
        broadcastToRoom(roomId, "${player.name} attacks ${mob.name}.", exclude = sessionId)

        return null
    }

    suspend fun flee(
        sessionId: SessionId,
        forced: Boolean = false,
    ): String? {
        val fight = fightsByPlayer[sessionId] ?: return "You are not in combat."
        val mobName = mobs.get(fight.mobId)?.name ?: "your foe"
        endFight(fight)

        val msg =
            if (forced) {
                "You are forced to flee from $mobName."
            } else {
                "You flee from $mobName."
            }
        outbound.send(OutboundEvent.SendText(sessionId, msg))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
        return null
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
        val fight = fightsByPlayer[sessionId]
        if (fight != null) {
            endFight(fight)
        }
        defenseByPlayer.remove(sessionId)
    }

    suspend fun tick(maxCombatsPerTick: Int = 20) {
        val now = clock.millis()
        var ran = 0
        val fights = fightsByPlayer.values.toMutableList()
        fights.shuffle(rng)
        for (fight in fights) {
            if (ran >= maxCombatsPerTick) break
            if (now < fight.nextTickAtMs) continue

            val player = players.get(fight.sessionId)
            val mob = mobs.get(fight.mobId)
            if (player == null || mob == null) {
                endFight(fight)
                continue
            }

            if (player.roomId != mob.roomId) {
                endFight(fight)
                outbound.send(OutboundEvent.SendText(fight.sessionId, "${mob.name} is no longer here."))
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            if (player.hp <= 0) {
                endFight(fight)
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You are too wounded to keep fighting and flee."))
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            syncPlayerDefense(player)
            val playerAttack = equippedAttack(player.sessionId)

            val playerDamage = rollDamage() + playerAttack
            mob.hp = (mob.hp - playerDamage).coerceAtLeast(0)
            outbound.send(OutboundEvent.SendText(fight.sessionId, "You hit ${mob.name} for $playerDamage damage."))
            if (mob.hp <= 0) {
                handleMobDeath(mob)
                endFight(fight)
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            val mobDamage = rollDamage()
            player.hp = (player.hp - mobDamage).coerceAtLeast(0)
            outbound.send(OutboundEvent.SendText(fight.sessionId, "${mob.name} hits you for $mobDamage damage."))

            if (player.hp <= 0) {
                endFight(fight)
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You are forced to flee from ${mob.name}."))
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            fight.nextTickAtMs = now + tickMillis
            outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
            ran++
        }
    }

    private fun endFight(fight: Fight) {
        fightsByPlayer.remove(fight.sessionId)
        fightsByMob.remove(fight.mobId)
    }

    private fun rollDamage(): Int {
        require(minDamage > 0) { "minDamage must be > 0" }
        require(maxDamage >= minDamage) { "maxDamage must be >= minDamage" }
        val range = (maxDamage - minDamage) + 1
        return minDamage + rng.nextInt(range)
    }

    private fun equippedAttack(sessionId: SessionId): Int =
        items.equipment(sessionId).values.sumOf { it.item.damage }

    private fun equippedDefense(sessionId: SessionId): Int =
        items.equipment(sessionId).values.sumOf { it.item.armor }

    private fun syncPlayerDefense(player: PlayerState) {
        val sessionId = player.sessionId
        val currentDefense = equippedDefense(sessionId)
        val previousDefense = defenseByPlayer[sessionId] ?: 0
        if (currentDefense == previousDefense) return

        val delta = currentDefense - previousDefense
        player.maxHp += delta
        player.hp = (player.hp + delta).coerceAtMost(player.maxHp)
        if (player.hp < 0) player.hp = 0

        defenseByPlayer[sessionId] = currentDefense
    }

    private fun findMobsInRoom(
        roomId: RoomId,
        keyword: String,
    ): List<MobState> {
        val lower = keyword.lowercase()
        return mobs
            .mobsInRoom(roomId)
            .filter { it.name.lowercase().contains(lower) }
            .sortedBy { it.name }
    }

    private suspend fun handleMobDeath(mob: MobState) {
        mobs.remove(mob.id)
        onMobRemoved(mob.id)
        items.dropMobItemsToRoom(mob.id, mob.roomId)
        broadcastToRoom(mob.roomId, "${mob.name} dies.")
    }

    private suspend fun broadcastToRoom(
        roomId: RoomId,
        text: String,
        exclude: SessionId? = null,
    ) {
        for (p in players.playersInRoom(roomId)) {
            if (exclude != null && p.sessionId == exclude) continue
            outbound.send(OutboundEvent.SendText(p.sessionId, text))
        }
    }
}
