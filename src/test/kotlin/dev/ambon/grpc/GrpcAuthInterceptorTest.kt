package dev.ambon.grpc

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.grpc.proto.EngineServiceGrpcKt
import dev.ambon.hmacSha256
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GrpcAuthInterceptorTest {
    private val sharedSecret = "test-secret-for-grpc-auth"

    // ── Unit tests for HMAC and interceptor logic ──────────────────────────────

    @Test
    fun `hmacSha256 produces consistent results`() {
        val hmac1 = hmacSha256(sharedSecret, "1234567890")
        val hmac2 = hmacSha256(sharedSecret, "1234567890")
        assertEquals(hmac1, hmac2)
        assertTrue(hmac1.isNotBlank())
        // SHA256 hex output is 64 chars
        assertEquals(64, hmac1.length)
    }

    @Test
    fun `hmacSha256 produces different results for different payloads`() {
        val hmac1 = hmacSha256(sharedSecret, "1234567890")
        val hmac2 = hmacSha256(sharedSecret, "9876543210")
        assertNotEquals(hmac1, hmac2)
    }

    @Test
    fun `hmacSha256 produces different results for different secrets`() {
        val hmac1 = hmacSha256("secret-a", "payload")
        val hmac2 = hmacSha256("secret-b", "payload")
        assertNotEquals(hmac1, hmac2)
    }

    @Test
    fun `server interceptor rejects blank secret in constructor`() {
        assertThrows<IllegalArgumentException> {
            GrpcServerAuthInterceptor(sharedSecret = "")
        }
    }

    @Test
    fun `client interceptor rejects blank secret in constructor`() {
        assertThrows<IllegalArgumentException> {
            GrpcClientAuthInterceptor(sharedSecret = "")
        }
    }

    @Test
    fun `server interceptor rejects zero timestamp tolerance`() {
        assertThrows<IllegalArgumentException> {
            GrpcServerAuthInterceptor(sharedSecret = sharedSecret, timestampToleranceMs = 0L)
        }
    }

    // ── Integration tests with in-process gRPC ─────────────────────────────────

    private val serverName = "auth-test-${System.nanoTime()}"
    private lateinit var scope: CoroutineScope
    private lateinit var engineInbound: LocalInboundBus
    private lateinit var engineOutbound: LocalOutboundBus
    private lateinit var serviceImpl: EngineServiceImpl

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engineInbound = LocalInboundBus(capacity = 256)
        engineOutbound = LocalOutboundBus(capacity = 256)
        serviceImpl = EngineServiceImpl(
            inbound = engineInbound,
            outbound = engineOutbound,
            scope = scope,
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        engineInbound.close()
        engineOutbound.close()
    }

    @Test
    fun `authenticated client can connect and stream events`() = runBlocking {
        val fixedTime = 1_000_000L
        val serverInterceptor = GrpcServerAuthInterceptor(
            sharedSecret = sharedSecret,
            clockMillis = { fixedTime },
        )
        val clientInterceptor = GrpcClientAuthInterceptor(
            sharedSecret = sharedSecret,
            clockMillis = { fixedTime },
        )

        val server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(serviceImpl, serverInterceptor))
            .build()
            .start()

        val channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .intercept(clientInterceptor)
            .build()

        try {
            val stub = EngineServiceGrpcKt.EngineServiceCoroutineStub(channel)
            val collectJob = scope.launch {
                stub.eventStream(
                    flow {
                        emit(InboundEvent.Connected(sessionId = SessionId(1L)).toProto())
                        delay(500)
                    },
                ).collect {}
            }

            // Give the stream time to connect
            delay(200)

            // If we got here without UNAUTHENTICATED, the auth succeeded.
            // Verify the session was registered on the engine side.
            assertTrue(serviceImpl.sessionToStream.containsKey(SessionId(1L)))
            collectJob.cancel()
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `unauthenticated client is rejected`() = runBlocking {
        val serverInterceptor = GrpcServerAuthInterceptor(
            sharedSecret = sharedSecret,
        )

        val server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(serviceImpl, serverInterceptor))
            .build()
            .start()

        // No client interceptor — no auth headers.
        val channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build()

        try {
            val stub = EngineServiceGrpcKt.EngineServiceCoroutineStub(channel)
            var caughtStatus: Status.Code? = null
            try {
                stub.eventStream(
                    flow {
                        emit(InboundEvent.Connected(sessionId = SessionId(1L)).toProto())
                    },
                ).collect {}
            } catch (e: StatusRuntimeException) {
                caughtStatus = e.status.code
            } catch (e: StatusException) {
                caughtStatus = e.status.code
            }
            assertEquals(Status.Code.UNAUTHENTICATED, caughtStatus)
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `wrong secret is rejected`() = runBlocking {
        val fixedTime = 1_000_000L
        val serverInterceptor = GrpcServerAuthInterceptor(
            sharedSecret = "correct-secret",
            clockMillis = { fixedTime },
        )
        val clientInterceptor = GrpcClientAuthInterceptor(
            sharedSecret = "wrong-secret",
            clockMillis = { fixedTime },
        )

        val server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(serviceImpl, serverInterceptor))
            .build()
            .start()

        val channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .intercept(clientInterceptor)
            .build()

        try {
            val stub = EngineServiceGrpcKt.EngineServiceCoroutineStub(channel)
            var caughtStatus: Status.Code? = null
            try {
                stub.eventStream(
                    flow {
                        emit(InboundEvent.Connected(sessionId = SessionId(1L)).toProto())
                    },
                ).collect {}
            } catch (e: StatusRuntimeException) {
                caughtStatus = e.status.code
            } catch (e: StatusException) {
                caughtStatus = e.status.code
            }
            assertEquals(Status.Code.UNAUTHENTICATED, caughtStatus)
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `expired timestamp is rejected`() = runBlocking {
        val serverTime = 100_000L
        val clientTime = 1L // Way in the past relative to server

        val serverInterceptor = GrpcServerAuthInterceptor(
            sharedSecret = sharedSecret,
            timestampToleranceMs = 30_000L,
            clockMillis = { serverTime },
        )
        val clientInterceptor = GrpcClientAuthInterceptor(
            sharedSecret = sharedSecret,
            clockMillis = { clientTime },
        )

        val server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(serviceImpl, serverInterceptor))
            .build()
            .start()

        val channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .intercept(clientInterceptor)
            .build()

        try {
            val stub = EngineServiceGrpcKt.EngineServiceCoroutineStub(channel)
            var caughtStatus: Status.Code? = null
            try {
                stub.eventStream(
                    flow {
                        emit(InboundEvent.Connected(sessionId = SessionId(1L)).toProto())
                    },
                ).collect {}
            } catch (e: StatusRuntimeException) {
                caughtStatus = e.status.code
            } catch (e: StatusException) {
                caughtStatus = e.status.code
            }
            assertEquals(Status.Code.UNAUTHENTICATED, caughtStatus)
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `timestamp within tolerance is accepted`() = runBlocking {
        val serverTime = 100_000L
        val clientTime = serverTime - 15_000L // 15 seconds behind, within 30s tolerance

        val serverInterceptor = GrpcServerAuthInterceptor(
            sharedSecret = sharedSecret,
            timestampToleranceMs = 30_000L,
            clockMillis = { serverTime },
        )
        val clientInterceptor = GrpcClientAuthInterceptor(
            sharedSecret = sharedSecret,
            clockMillis = { clientTime },
        )

        val server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(serviceImpl, serverInterceptor))
            .build()
            .start()

        val channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .intercept(clientInterceptor)
            .build()

        try {
            val stub = EngineServiceGrpcKt.EngineServiceCoroutineStub(channel)
            val collectJob = scope.launch {
                stub.eventStream(
                    flow {
                        emit(InboundEvent.Connected(sessionId = SessionId(1L)).toProto())
                        delay(500)
                    },
                ).collect {}
            }

            delay(200)
            assertTrue(serviceImpl.sessionToStream.containsKey(SessionId(1L)))
            collectJob.cancel()
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `server without interceptor accepts any client`() = runBlocking {
        // No interceptor on server — should accept all clients (STANDALONE mode behavior).
        val server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start()

        val channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build()

        try {
            val stub = EngineServiceGrpcKt.EngineServiceCoroutineStub(channel)
            val collectJob = scope.launch {
                stub.eventStream(
                    flow {
                        emit(InboundEvent.Connected(sessionId = SessionId(1L)).toProto())
                        delay(500)
                    },
                ).collect {}
            }

            delay(200)
            assertTrue(serviceImpl.sessionToStream.containsKey(SessionId(1L)))
            collectJob.cancel()
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }
}
