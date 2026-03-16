package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.LoginResult
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.withTimeout

fun LocalOutboundBus.drainAll(): List<OutboundEvent> {
    val out = mutableListOf<OutboundEvent>()
    while (true) {
        val ev = tryReceive().getOrNull() ?: break
        out += ev
    }
    return out
}

suspend fun LocalOutboundBus.collectUntil(
    timeoutMs: Long = 500,
    predicate: (List<OutboundEvent>) -> Boolean,
): List<OutboundEvent> {
    val events = mutableListOf<OutboundEvent>()
    withTimeout(timeoutMs) {
        while (!predicate(events)) {
            events += asReceiveChannel().receive()
        }
    }
    return events
}

suspend fun PlayerRegistry.loginOrFail(
    sessionId: SessionId,
    name: String,
    password: String = "password",
) {
    val res = login(sessionId, name, password)
    require(res == LoginResult.Ok) { "Login failed: $res" }
}

// ── Event assertion helpers ─────────────────────────────────────────────────

/** Extracts all [OutboundEvent.SendText] message strings, optionally filtered by session. */
fun List<OutboundEvent>.textMessages(sessionId: SessionId? = null): List<String> =
    filterIsInstance<OutboundEvent.SendText>()
        .let { if (sessionId != null) it.filter { e -> e.sessionId == sessionId } else it }
        .map { it.text }

/** Extracts all [OutboundEvent.SendInfo] message strings, optionally filtered by session. */
fun List<OutboundEvent>.infoMessages(sessionId: SessionId? = null): List<String> =
    filterIsInstance<OutboundEvent.SendInfo>()
        .let { if (sessionId != null) it.filter { e -> e.sessionId == sessionId } else it }
        .map { it.text }

/** Extracts all [OutboundEvent.SendError] message strings, optionally filtered by session. */
fun List<OutboundEvent>.errorMessages(sessionId: SessionId? = null): List<String> =
    filterIsInstance<OutboundEvent.SendError>()
        .let { if (sessionId != null) it.filter { e -> e.sessionId == sessionId } else it }
        .map { it.text }

/** True if any [OutboundEvent.SendPrompt] exists, optionally filtered by session. */
fun List<OutboundEvent>.hasPrompt(sessionId: SessionId? = null): Boolean =
    filterIsInstance<OutboundEvent.SendPrompt>()
        .any { sessionId == null || it.sessionId == sessionId }

// ── Shared system-test components ───────────────────────────────────────────

/**
 * Common test infrastructure shared across system tests.
 * Bundles the four components that every system test creates identically:
 * [repo], [items], [outbound], and [players].
 */
class SystemTestComponents(
    val roomId: RoomId = TEST_ROOM_ID,
    clockInitialMs: Long = 0L,
) {
    val repo = InMemoryPlayerRepository()
    val items = ItemRegistry()
    val outbound = LocalOutboundBus()
    val clock = MutableClock(clockInitialMs)
    val players: PlayerRegistry = buildTestPlayerRegistry(roomId, repo, items)
}
