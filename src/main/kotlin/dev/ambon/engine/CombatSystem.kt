package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.metrics.GameMetrics
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
    internal val minDamage: Int = 1,
    internal val maxDamage: Int = 4,
    private val onMobRemoved: (MobId) -> Unit = {},
    private val progression: PlayerProgression = PlayerProgression(),
    private val metrics: GameMetrics = GameMetrics.noop(),
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

    fun syncPlayerDefense(sessionId: SessionId) {
        val player = players.get(sessionId) ?: return
        syncPlayerDefense(player)
    }

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

    fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        val fight = fightsByPlayer.remove(oldSid)
        if (fight != null) {
            val newFight = fight.copy(sessionId = newSid)
            fightsByPlayer[newSid] = newFight
            fightsByMob[fight.mobId] = newFight
        }
        defenseByPlayer.remove(oldSid)?.let { defenseByPlayer[newSid] = it }
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
        val fight = fightsByPlayer[sessionId]
        if (fight != null) {
            endFight(fight)
        }
        defenseByPlayer.remove(sessionId)
    }

    fun endCombatFor(sessionId: SessionId) {
        val fight = fightsByPlayer[sessionId] ?: return
        endFight(fight)
    }

    suspend fun onMobRemovedExternally(mobId: MobId) {
        val fight = fightsByMob[mobId] ?: return
        endFight(fight)
        outbound.send(OutboundEvent.SendText(fight.sessionId, "Your opponent vanishes."))
        outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
    }

    suspend fun tick(maxCombatsPerTick: Int = 20): Int {
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

            syncPlayerDefense(player)

            if (player.hp <= 0) {
                metrics.onPlayerDeath()
                endFight(fight)
                outbound.send(OutboundEvent.SendText(fight.sessionId, "You are too wounded to keep fighting and flee."))
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            val playerAttack = equippedAttack(player.sessionId)

            val rawPlayerDamage = rollDamage() + playerAttack
            val effectivePlayerDamage = (rawPlayerDamage - mob.armor).coerceAtLeast(1)
            mob.hp = (mob.hp - effectivePlayerDamage).coerceAtLeast(0)
            outbound.send(OutboundEvent.SendText(fight.sessionId, "You hit ${mob.name} for $effectivePlayerDamage damage."))
            if (mob.hp <= 0) {
                handleMobDeath(fight.sessionId, mob)
                endFight(fight)
                outbound.send(OutboundEvent.SendPrompt(fight.sessionId))
                ran++
                continue
            }

            val mobDamage = rollDamage(mob.minDamage, mob.maxDamage)
            player.hp = (player.hp - mobDamage).coerceAtLeast(0)
            outbound.send(OutboundEvent.SendText(fight.sessionId, "${mob.name} hits you for $mobDamage damage."))

            if (player.hp <= 0) {
                metrics.onPlayerDeath()
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
        return ran
    }

    private fun endFight(fight: Fight) {
        fightsByPlayer.remove(fight.sessionId)
        fightsByMob.remove(fight.mobId)
    }

    private fun rollDamage(
        min: Int = minDamage,
        max: Int = maxDamage,
    ): Int {
        require(min > 0) { "min damage must be > 0" }
        require(max >= min) { "max damage must be >= min damage" }
        val range = (max - min) + 1
        return min + rng.nextInt(range)
    }

    private fun equippedAttack(sessionId: SessionId): Int = items.equipment(sessionId).values.sumOf { it.item.damage }

    private fun equippedDefense(sessionId: SessionId): Int = items.equipment(sessionId).values.sumOf { it.item.armor }

    private fun syncPlayerDefense(player: PlayerState) {
        val sessionId = player.sessionId
        val currentDefense = equippedDefense(sessionId)
        val previousDefense = defenseByPlayer[sessionId] ?: 0
        if (currentDefense == previousDefense) return

        val delta = currentDefense - previousDefense
        val newMaxHp = (player.maxHp + delta).coerceAtLeast(0)
        player.maxHp = newMaxHp
        player.hp =
            if (delta > 0) {
                (player.hp + delta).coerceAtMost(newMaxHp)
            } else {
                player.hp.coerceAtMost(newMaxHp)
            }
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

    private suspend fun handleMobDeath(
        killerSessionId: SessionId,
        mob: MobState,
    ) {
        mobs.remove(mob.id)
        onMobRemoved(mob.id)
        items.dropMobItemsToRoom(mob.id, mob.roomId)
        rollDrops(mob)
        broadcastToRoom(mob.roomId, "${mob.name} dies.")
        grantKillXp(killerSessionId, mob)
    }

    private fun rollDrops(mob: MobState) {
        for (drop in mob.drops) {
            if (drop.chance <= 0.0) continue
            val shouldDrop = drop.chance >= 1.0 || rng.nextDouble() < drop.chance
            if (!shouldDrop) continue
            items.placeMobDrop(drop.itemId, mob.roomId)
        }
    }

    private suspend fun grantKillXp(
        sessionId: SessionId,
        mob: MobState,
    ) {
        val reward = progression.killXpReward(mob)
        if (reward <= 0L) return

        val result = players.grantXp(sessionId, reward, progression) ?: return
        metrics.onXpAwarded(reward, "kill")
        outbound.send(OutboundEvent.SendText(sessionId, "You gain $reward XP."))
        if (result.levelsGained <= 0) return
        metrics.onLevelUp()

        val oldMaxHp = progression.maxHpForLevel(result.previousLevel)
        val newMaxHp = progression.maxHpForLevel(result.newLevel)
        val hpGain = (newMaxHp - oldMaxHp).coerceAtLeast(0)
        val levelUpMessage =
            if (hpGain > 0) {
                "You reached level ${result.newLevel}! (+$hpGain max HP)"
            } else {
                "You reached level ${result.newLevel}!"
            }
        outbound.send(OutboundEvent.SendText(sessionId, levelUpMessage))
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
