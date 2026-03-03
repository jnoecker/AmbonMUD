package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.Rewards
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import java.util.Random

/** Standard error when `players.get(sessionId)` returns null in a system method returning `String?`. */
internal const val ERR_NOT_CONNECTED = "You are not connected."

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
 */
internal suspend fun grantRewards(
    sessionId: SessionId,
    rewards: Rewards,
    ps: PlayerState,
    players: PlayerRegistry,
    outbound: OutboundBus,
) {
    if (rewards.gold > 0) {
        ps.gold += rewards.gold
        outbound.send(OutboundEvent.SendText(sessionId, "You receive ${rewards.gold} gold."))
    }
    if (rewards.xp > 0) {
        players.grantXp(sessionId, rewards.xp)
        outbound.send(OutboundEvent.SendText(sessionId, "You gain ${rewards.xp} XP."))
    }
}
