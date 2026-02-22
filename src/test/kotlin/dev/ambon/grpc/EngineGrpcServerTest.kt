package dev.ambon.grpc

import dev.ambon.bus.LocalInboundBus
import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import dev.ambon.grpc.proto.EngineServiceGrpcKt
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

class EngineGrpcServerTest {
    @Test
    fun `stop returns promptly even with active gateway stream`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val inbound = LocalInboundBus(capacity = 256)
            val outbound = LocalOutboundBus(capacity = 256)
            val server =
                EngineGrpcServer(
                    port = 0,
                    inbound = inbound,
                    outbound = outbound,
                    scope = scope,
                    gracefulShutdownTimeoutMs = 100L,
                    forceShutdownTimeoutMs = 100L,
                )
            server.start()

            val channel =
                ManagedChannelBuilder
                    .forAddress("127.0.0.1", server.listeningPort())
                    .usePlaintext()
                    .build()
            val stub = EngineServiceGrpcKt.EngineServiceCoroutineStub(channel)

            val gatewayJob =
                scope.launch {
                    runCatching {
                        stub.eventStream(
                            flow {
                                emit(InboundEvent.Connected(sessionId = SessionId(1L)).toProto())
                                delay(10_000L)
                            },
                        ).collect {}
                    }
                }

            delay(100L)

            val stopThread =
                thread(
                    isDaemon = true,
                    name = "engine-grpc-stop-test",
                ) {
                    server.stop()
                }
            stopThread.join(2_000L)
            val stillAlive = stopThread.isAlive
            if (stillAlive) {
                stopThread.interrupt()
                stopThread.join(1_000L)
            }

            assertFalse(stillAlive, "EngineGrpcServer.stop() should not hang with active streams")

            gatewayJob.cancel()
            channel.shutdownNow()
            scope.cancel()
            inbound.close()
            outbound.close()
        }
}
