package dev.ambon.grpc

/**
 * Shared timeout constants for gRPC infrastructure.
 *
 * These values are used across both engine-side and gateway-side gRPC components
 * (buses, dispatchers, routers) to keep timeout behaviour consistent.
 */
object GrpcTimeouts {
    /**
     * Maximum time (ms) to wait for a gRPC send channel to accept an inbound event before
     * treating the remote engine as overloaded.
     *
     * Used by [dev.ambon.bus.GrpcInboundBus] and [dev.ambon.gateway.SessionRouter].
     */
    const val FORWARD_SEND_TIMEOUT_MS: Long = 5_000L

    /**
     * Maximum time (ms) to wait for a background coroutine to stop gracefully before
     * forcing cancellation.
     *
     * Used by [GrpcOutboundDispatcher] and `GrpcOutboundBus` via [cancelJobWithTimeout].
     */
    const val STOP_TIMEOUT_MS: Long = 5_000L

    /**
     * Maximum time (ms) to wait for a control-plane event to be delivered through a
     * backpressured channel before declaring delivery failure.
     *
     * Used as the default for the `controlPlaneSendTimeoutMs` parameter in both
     * [GrpcOutboundDispatcher] and `GrpcOutboundBus`.
     */
    const val DEFAULT_CONTROL_PLANE_SEND_TIMEOUT_MS: Long = 250L
}
