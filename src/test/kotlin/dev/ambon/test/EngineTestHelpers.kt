package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.LoginResult
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent

fun LocalOutboundBus.drainAll(): List<OutboundEvent> {
    val out = mutableListOf<OutboundEvent>()
    while (true) {
        val ev = tryReceive().getOrNull() ?: break
        out += ev
    }
    return out
}

suspend fun PlayerRegistry.loginOrFail(
    sessionId: SessionId,
    name: String,
    password: String = "password",
) {
    val res = login(sessionId, name, password)
    require(res == LoginResult.Ok) { "Login failed: $res" }
}
