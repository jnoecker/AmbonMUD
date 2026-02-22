package dev.ambon.grpc

import dev.ambon.bus.InboundBus
import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.grpc.proto.EngineServiceGrpcKt
import dev.ambon.grpc.proto.InboundEventProto
import dev.ambon.grpc.proto.OutboundEventProto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * gRPC service implementation for the Engine.
 *
 * One bidirectional stream per gateway (not per session). The gateway multiplexes all its sessions
 * over a single [eventStream] call. The engine identifies which gateway a session belongs to via
 * the [sessionToStream] map, which is maintained here and consumed by [GrpcOutboundDispatcher].
 */
class EngineServiceImpl(
    private val inbound: InboundBus,
    private val outbound: OutboundBus,
    private val scope: CoroutineScope,
) : EngineServiceGrpcKt.EngineServiceCoroutineImplBase() {
    /**
     * Maps sessionId → the outbound channel for the gateway stream that owns that session.
     * Written by [eventStream] when Connected/Disconnected events arrive.
     * Read by [GrpcOutboundDispatcher] to route outbound events.
     */
    internal val sessionToStream = ConcurrentHashMap<SessionId, SendChannel<OutboundEventProto>>()

    override fun eventStream(requests: Flow<InboundEventProto>): Flow<OutboundEventProto> {
        // Explicit channel so we control capacity directly. The dispatcher uses trySend() into
        // this channel; a large buffer prevents spurious drops under burst load while still
        // bounding memory per gateway stream. If the channel stays full the dispatcher logs a
        // warning and drops low-value events (see GrpcOutboundDispatcher).
        val streamChannel = Channel<OutboundEventProto>(capacity = STREAM_CHANNEL_CAPACITY)

        scope.launch {
            try {
                requests.collect { proto ->
                    val event = proto.toDomain() ?: return@collect
                    // Track session ownership.
                    when (event) {
                        is InboundEvent.Connected -> {
                            sessionToStream[event.sessionId] = streamChannel
                            log.debug { "Session ${event.sessionId} registered to stream" }
                        }
                        is InboundEvent.Disconnected -> {
                            sessionToStream.remove(event.sessionId)
                            log.debug { "Session ${event.sessionId} removed from stream" }
                        }
                        else -> Unit
                    }
                    inbound.send(event)
                }
            } catch (e: Exception) {
                log.warn(e) { "Gateway stream reader error; generating synthetic disconnects" }
            } finally {
                // Gateway stream ended — generate synthetic Disconnected for any orphaned sessions
                // that never sent an explicit Disconnected (e.g. gateway crash).
                val orphans = sessionToStream.keys().toList().filter { sessionToStream[it] === streamChannel }
                orphans.forEach { sid ->
                    sessionToStream.remove(sid)
                    try {
                        inbound.send(InboundEvent.Disconnected(sessionId = sid, reason = "gateway-disconnect"))
                    } catch (_: Exception) {
                        // best-effort
                    }
                }
                streamChannel.close()
            }
        }

        return streamChannel.receiveAsFlow()
    }
}

/** Per-gateway-stream outbound buffer. Large enough to absorb bursts without constant drops. */
private const val STREAM_CHANNEL_CAPACITY = 8_192
