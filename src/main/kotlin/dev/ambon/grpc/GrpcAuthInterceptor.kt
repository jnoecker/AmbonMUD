package dev.ambon.grpc

import dev.ambon.hmacSha256
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

private val log = KotlinLogging.logger {}

/**
 * HMAC-based shared-secret authentication for gRPC engine/gateway communication.
 *
 * The client sends two metadata headers on every call:
 *   - `x-ambon-auth-hmac`: HMAC-SHA256(secret, timestamp)
 *   - `x-ambon-auth-ts`: the Unix-millis timestamp used for the HMAC
 *
 * The server validates:
 *   1. Both headers are present and non-blank.
 *   2. The timestamp is within [timestampToleranceMs] of the server clock.
 *   3. The HMAC matches the recomputed value.
 *
 * This prevents replay attacks (time window) and forged connections (shared secret).
 */
object GrpcAuthMetadata {
    val HMAC_KEY: Metadata.Key<String> =
        Metadata.Key.of("x-ambon-auth-hmac", Metadata.ASCII_STRING_MARSHALLER)
    val TIMESTAMP_KEY: Metadata.Key<String> =
        Metadata.Key.of("x-ambon-auth-ts", Metadata.ASCII_STRING_MARSHALLER)
}

/**
 * Server-side interceptor that validates the shared-secret HMAC on every incoming call.
 *
 * Rejects unauthenticated calls with [Status.UNAUTHENTICATED].
 *
 * @param sharedSecret the shared secret configured on both engine and gateway.
 * @param timestampToleranceMs maximum allowed clock skew between engine and gateway (default 30s).
 * @param clockMillis supplier for the current time; defaults to [System.currentTimeMillis].
 *                    Override in tests for determinism.
 */
class GrpcServerAuthInterceptor(
    private val sharedSecret: String,
    private val timestampToleranceMs: Long = DEFAULT_TIMESTAMP_TOLERANCE_MS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ServerInterceptor {
    init {
        require(sharedSecret.isNotBlank()) { "gRPC shared secret must not be blank" }
        require(timestampToleranceMs > 0L) { "timestampToleranceMs must be > 0" }
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val hmac = headers.get(GrpcAuthMetadata.HMAC_KEY)
        val tsString = headers.get(GrpcAuthMetadata.TIMESTAMP_KEY)

        if (hmac.isNullOrBlank() || tsString.isNullOrBlank()) {
            log.warn { "gRPC auth rejected: missing HMAC or timestamp header" }
            return rejectCall(call, "Missing authentication headers")
        }

        val timestamp = tsString.toLongOrNull()
        if (timestamp == null) {
            log.warn { "gRPC auth rejected: non-numeric timestamp '$tsString'" }
            return rejectCall(call, "Invalid timestamp")
        }

        val now = clockMillis()
        val drift = kotlin.math.abs(now - timestamp)
        if (drift > timestampToleranceMs) {
            log.warn { "gRPC auth rejected: timestamp drift ${drift}ms exceeds tolerance ${timestampToleranceMs}ms" }
            return rejectCall(call, "Timestamp outside acceptable window")
        }

        val expectedHmac = hmacSha256(sharedSecret, tsString)
        if (!constantTimeEquals(expectedHmac, hmac)) {
            log.warn { "gRPC auth rejected: HMAC mismatch" }
            return rejectCall(call, "Invalid HMAC signature")
        }

        return next.startCall(call, headers)
    }

    private fun <ReqT, RespT> rejectCall(
        call: ServerCall<ReqT, RespT>,
        description: String,
    ): ServerCall.Listener<ReqT> {
        call.close(Status.UNAUTHENTICATED.withDescription(description), Metadata())
        return object : ServerCall.Listener<ReqT>() {}
    }

    companion object {
        /** Default timestamp tolerance: 30 seconds. */
        const val DEFAULT_TIMESTAMP_TOLERANCE_MS = 30_000L
    }
}

/**
 * Client-side interceptor that attaches the HMAC shared-secret headers to every outgoing call.
 *
 * @param sharedSecret the shared secret configured on both engine and gateway.
 * @param clockMillis supplier for the current time; defaults to [System.currentTimeMillis].
 *                    Override in tests for determinism.
 */
class GrpcClientAuthInterceptor(
    private val sharedSecret: String,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ClientInterceptor {
    init {
        require(sharedSecret.isNotBlank()) { "gRPC shared secret must not be blank" }
    }

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val delegate = next.newCall(method, callOptions)
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(delegate) {
            override fun start(
                responseListener: Listener<RespT>,
                headers: Metadata,
            ) {
                val ts = clockMillis().toString()
                headers.put(GrpcAuthMetadata.TIMESTAMP_KEY, ts)
                headers.put(GrpcAuthMetadata.HMAC_KEY, hmacSha256(sharedSecret, ts))
                super.start(responseListener, headers)
            }
        }
    }
}

/**
 * Constant-time string comparison to prevent timing side-channel attacks on HMAC validation.
 */
private fun constantTimeEquals(
    a: String,
    b: String,
): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].code xor b[i].code)
    }
    return result == 0
}
