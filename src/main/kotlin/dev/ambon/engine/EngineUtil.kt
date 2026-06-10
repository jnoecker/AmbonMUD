package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.Rewards
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import java.util.Random

/**
 * Applies healing to [target], marks vitals dirty if HP changed, and returns the actual amount healed.
 */
internal fun applyHeal(
    sessionId: SessionId,
    target: PlayerState,
    amount: Int,
    dirtyNotifier: DirtyNotifier,
): Int {
    val before = target.hp
    target.healHp(amount)
    val healed = target.hp - before
    if (healed > 0) dirtyNotifier.playerVitalsDirty(sessionId)
    return healed
}

/** Standard error when `players.get(sessionId)` returns null in a system method returning `String?`. */
internal const val ERR_NOT_CONNECTED = "You are not connected."

/** Standard refusal when a player under the Akathavae pledge attempts any act of violence. */
internal const val ERR_AKATHAVAE_PLEDGE =
    "Your Akathavae pledge stays your hand — violence is no longer your craft. (Renounce your vow at a shrine to fight again.)"

/** Converts milliseconds to whole seconds, rounding up, with a minimum of 1. */
internal fun Long.ceilSeconds(): Long = ((this + 999) / 1000).coerceAtLeast(1)

/** Removes all entries whose expiry (extracted via [expiresAt]) is at or before [nowMs]. */
internal fun <K, V> MutableMap<K, V>.removeExpired(nowMs: Long, expiresAt: (V) -> Long) {
    entries.removeIf { expiresAt(it.value) <= nowMs }
}

/**
 * Sends [text] as a [OutboundEvent.SendText] to every player in [roomId],
 * optionally excluding [exclude].
 */
internal suspend fun broadcastToRoom(
    players: PlayerRegistry,
    outbound: OutboundBus,
    roomId: RoomId,
    text: String,
    exclude: SessionId? = null,
) {
    for (p in players.playersInRoom(roomId)) {
        if (exclude != null && p.sessionId == exclude) continue
        outbound.send(OutboundEvent.SendText(p.sessionId, text))
    }
}

internal suspend fun broadcastToRoom(
    players: PlayerRegistry,
    outbound: OutboundBus,
    roomId: RoomId,
    text: String,
    exclude1: SessionId,
    exclude2: SessionId,
) {
    for (p in players.playersInRoom(roomId)) {
        if (p.sessionId == exclude1 || p.sessionId == exclude2) continue
        outbound.send(OutboundEvent.SendText(p.sessionId, text))
    }
}

/**
 * Sends [text] as a [OutboundEvent.SendText] to every online player.
 * Optionally excludes [exclude].
 */
internal suspend fun broadcastToAll(
    players: PlayerRegistry,
    outbound: OutboundBus,
    text: String,
    exclude: SessionId? = null,
) {
    for (p in players.allPlayers()) {
        if (exclude != null && p.sessionId == exclude) continue
        outbound.send(OutboundEvent.SendText(p.sessionId, text))
    }
}

/**
 * Sends [text] as a [OutboundEvent.SendInfo] to every online player.
 * Optionally excludes [exclude].
 */
internal suspend fun broadcastInfoToAll(
    players: PlayerRegistry,
    outbound: OutboundBus,
    text: String,
    exclude: SessionId? = null,
) {
    for (p in players.allPlayers()) {
        if (exclude != null && p.sessionId == exclude) continue
        outbound.send(OutboundEvent.SendInfo(p.sessionId, text))
    }
}

/**
 * Returns a random integer in [[min], [max]] (inclusive).
 * Returns [min] when max <= min.
 */
internal fun rollRange(
    rng: Random,
    min: Int,
    max: Int,
): Int {
    if (max <= min) return min
    return min + rng.nextInt((max - min) + 1)
}

/**
 * Grants XP and gold from [rewards] to the player, sending feedback messages.
 * Gold is added to [ps] immediately. XP is granted via [players.grantXp], which
 * also handles persistence. The caller is responsible for persisting when no XP
 * is granted (i.e. when [rewards.xp] == 0 and gold was awarded).
 *
 * When [progression] and [sourceLevel] are both supplied, the XP reward is
 * passed through the same diminishing-returns curve that kills use, so players
 * who have out-levelled a quest or puzzle receive reduced XP instead of the
 * flat amount authored in content. Omit either to preserve legacy flat award
 * behaviour.
 */
internal suspend fun grantRewards(
    sessionId: SessionId,
    rewards: Rewards,
    ps: PlayerState,
    players: PlayerRegistry,
    outbound: OutboundBus,
    progression: PlayerProgression? = null,
    sourceLevel: Int? = null,
    onLevelUp: suspend (LevelUpResult) -> Unit = {},
) {
    if (rewards.gold > 0) {
        ps.gold += rewards.gold
        outbound.send(OutboundEvent.SendText(sessionId, "You receive ${rewards.gold} gold."))
    }
    if (rewards.xp > 0) {
        val effectiveXp =
            if (progression != null && sourceLevel != null) {
                val multiplier = progression.diminishingKillXpMultiplier(ps.level, sourceLevel)
                if (multiplier >= 1.0) {
                    rewards.xp
                } else {
                    (rewards.xp * multiplier).toLong().coerceAtLeast(0L)
                }
            } else {
                rewards.xp
            }
        if (effectiveXp > 0) {
            val result = players.grantXp(sessionId, effectiveXp, progression)
            outbound.send(OutboundEvent.SendText(sessionId, "You gain $effectiveXp XP."))
            if (result != null && result.levelsGained > 0) {
                if (progression != null) {
                    val message = progression.buildLevelUpMessage(
                        result,
                        ps.stats[progression.bindings.hpScalingStat],
                        ps.stats[progression.bindings.manaScalingStat],
                        ps.playerClass,
                    )
                    outbound.send(OutboundEvent.SendText(sessionId, message))
                } else {
                    outbound.send(
                        OutboundEvent.SendText(sessionId, "You reached level ${result.newLevel}!"),
                    )
                }
                onLevelUp(result)
            }
        }
    }
}
