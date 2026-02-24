package dev.ambon.gateway

import dev.ambon.bus.InboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.grpc.proto.InboundEventProto
import dev.ambon.grpc.toProto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

/**
 * Multi-engine inbound routing for gateway mode.
 *
 * Routes inbound events from transports to the correct engine's gRPC channel
 * based on a session-to-engine mapping. New sessions are assigned to engines
 * via round-robin.
 *
 * When a [SessionRedirect][dev.ambon.engine.events.OutboundEvent.SessionRedirect]
 * is intercepted on the outbound side, [remapSession] updates the routing so
 * subsequent events for that session go to the new engine.
 */
class SessionRouter(
    engineIds: List<String>,
) : InboundBus {
    init {
        require(engineIds.isNotEmpty()) { "At least one engine ID is required" }
    }

    private val orderedEngineIds: List<String> = engineIds.toList()

    /** Mutable channel map — supports reconnect via [reattachEngine]. */
    private val engineChannels = ConcurrentHashMap<String, SendChannel<InboundEventProto>>()

    /** Session → engine ID mapping. */
    private val sessionToEngine = ConcurrentHashMap<SessionId, String>()

    private val roundRobinIndex = AtomicInteger(0)

    /** Used to produce a failure [ChannelResult] when routing fails. */
    private val closedChannel = Channel<Unit>(0).also { it.close() }

    /**
     * Register (or replace) the gRPC send channel for an engine.
     * Called during initial setup and during reconnect.
     */
    fun registerEngine(
        engineId: String,
        channel: SendChannel<InboundEventProto>,
    ) {
        engineChannels[engineId] = channel
        log.debug { "Registered channel for engine $engineId" }
    }

    /**
     * Swap in a new gRPC send channel for an engine (reconnect).
     */
    fun reattachEngine(
        engineId: String,
        newChannel: SendChannel<InboundEventProto>,
    ) {
        engineChannels[engineId] = newChannel
        log.info { "Reattached channel for engine $engineId" }
    }

    /**
     * Assign a session to an engine (round-robin among available engines).
     * Returns the engine ID assigned.
     */
    fun assignSession(sessionId: SessionId): String {
        val idx = roundRobinIndex.getAndIncrement() % orderedEngineIds.size
        val engineId = orderedEngineIds[idx]
        sessionToEngine[sessionId] = engineId
        log.debug { "Assigned session $sessionId to engine $engineId" }
        return engineId
    }

    /**
     * Remap a session to a different engine (called on SessionRedirect).
     * Returns true if the remap was successful.
     */
    fun remapSession(
        sessionId: SessionId,
        newEngineId: String,
    ): Boolean {
        if (!engineChannels.containsKey(newEngineId)) {
            log.warn { "Cannot remap session $sessionId to unknown engine $newEngineId" }
            return false
        }
        val oldEngineId = sessionToEngine.put(sessionId, newEngineId)
        log.info { "Remapped session $sessionId from engine $oldEngineId to $newEngineId" }
        return true
    }

    /** Remove session tracking (called on disconnect). */
    fun removeSession(sessionId: SessionId) {
        sessionToEngine.remove(sessionId)
    }

    /** Get the engine ID for a session, or null if not mapped. */
    fun engineFor(sessionId: SessionId): String? = sessionToEngine[sessionId]

    override suspend fun send(event: InboundEvent) {
        val sessionId = event.sessionId()

        if (event is InboundEvent.Connected) {
            assignSession(sessionId)
        }

        val engineId = sessionToEngine[sessionId]
        if (engineId == null) {
            log.warn { "No engine mapping for session $sessionId; dropping ${event::class.simpleName}" }
            return
        }

        val channel = engineChannels[engineId]
        if (channel == null) {
            log.warn { "No channel for engine $engineId (session $sessionId); dropping ${event::class.simpleName}" }
            return
        }

        val proto = event.toProto()
        try {
            withTimeout(FORWARD_SEND_TIMEOUT_MS) {
                channel.send(proto)
            }
        } catch (e: TimeoutCancellationException) {
            log.warn { "Engine $engineId gRPC channel unresponsive for ${FORWARD_SEND_TIMEOUT_MS}ms" }
            throw IllegalStateException(
                "Engine $engineId gRPC channel did not accept event within ${FORWARD_SEND_TIMEOUT_MS}ms",
                e,
            )
        }

        if (event is InboundEvent.Disconnected) {
            removeSession(sessionId)
        }
    }

    override fun trySend(event: InboundEvent): ChannelResult<Unit> {
        val sessionId = event.sessionId()

        if (event is InboundEvent.Connected) {
            assignSession(sessionId)
        }

        val engineId = sessionToEngine[sessionId]
        if (engineId == null) {
            log.warn { "No engine mapping for session $sessionId; dropping ${event::class.simpleName}" }
            return closedChannel.trySend(Unit)
        }

        val channel = engineChannels[engineId]
        if (channel == null) {
            log.warn { "No channel for engine $engineId; dropping ${event::class.simpleName}" }
            return closedChannel.trySend(Unit)
        }

        val result = channel.trySend(event.toProto())
        if (result.isFailure) {
            log.warn { "Engine $engineId gRPC channel full or closed; dropping ${event::class.simpleName}" }
        }

        if (result.isSuccess && event is InboundEvent.Disconnected) {
            removeSession(sessionId)
        }

        return result
    }

    override fun tryReceive(): ChannelResult<InboundEvent> =
        throw UnsupportedOperationException("SessionRouter does not support tryReceive — engines read from their own channels")

    override fun close() {
        for ((_, channel) in engineChannels) {
            channel.close()
        }
        sessionToEngine.clear()
    }
}

private fun InboundEvent.sessionId(): SessionId =
    when (this) {
        is InboundEvent.Connected -> sessionId
        is InboundEvent.Disconnected -> sessionId
        is InboundEvent.GmcpReceived -> sessionId
        is InboundEvent.LineReceived -> sessionId
    }

private const val FORWARD_SEND_TIMEOUT_MS = 5_000L
