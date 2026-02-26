package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId

fun interface EngineEventDispatcher {
    suspend fun dispatch(event: InboundEvent)
}

class DefaultEngineEventDispatcher(
    private val onConnected: suspend (SessionId, Boolean) -> Unit,
    private val onGmcpReceived: suspend (InboundEvent.GmcpReceived) -> Unit,
    private val onDisconnected: suspend (SessionId) -> Unit,
    private val onLineReceived: suspend (SessionId, String) -> Unit,
) : EngineEventDispatcher {
    override suspend fun dispatch(event: InboundEvent) {
        when (event) {
            is InboundEvent.Connected -> onConnected(event.sessionId, event.defaultAnsiEnabled)
            is InboundEvent.GmcpReceived -> onGmcpReceived(event)
            is InboundEvent.Disconnected -> onDisconnected(event.sessionId)
            is InboundEvent.LineReceived -> onLineReceived(event.sessionId, event.line)
        }
    }
}
