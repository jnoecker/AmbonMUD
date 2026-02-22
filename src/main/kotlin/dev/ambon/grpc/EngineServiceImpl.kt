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
import kotlinx.coroutines.flow.channelFlow
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

    override fun eventStream(requests: Flow<InboundEventProto>): Flow<OutboundEventProto> =
        channelFlow {
            // Register this stream's send channel so GrpcOutboundDispatcher can route to it.
            val streamChannel: SendChannel<OutboundEventProto> = channel

            val readerJob =
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
                        // Gateway stream broke — generate synthetic Disconnected for all orphaned sessions.
                        val orphans = sessionToStream.keys().toList().filter { sessionToStream[it] === streamChannel }
                        orphans.forEach { sid ->
                            sessionToStream.remove(sid)
                            try {
                                inbound.send(InboundEvent.Disconnected(sessionId = sid, reason = "gateway-disconnect"))
                            } catch (_: Exception) {
                                // best-effort
                            }
                        }
                        close()
                    }
                }

            readerJob.join()
        }
}

/** Outbound channel capacity used when registering streams. */
private const val STREAM_CHANNEL_CAPACITY = Channel.UNLIMITED
