package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.LoginResult
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
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
