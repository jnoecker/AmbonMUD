package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import java.util.Random

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
