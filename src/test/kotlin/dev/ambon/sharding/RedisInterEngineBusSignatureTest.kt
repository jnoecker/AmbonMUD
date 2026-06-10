package dev.ambon.sharding

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.bus.BusPublisher
import dev.ambon.bus.BusSubscriberSetup
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The inter-engine Redis bus must authenticate every message: anyone with Redis pub/sub access can
 * otherwise forge player handoffs, kicks, broadcasts, or shutdowns. These tests drive one engine's
 * published envelope into another engine's receiver and assert that only HMAC-valid envelopes are
 * delivered.
 */
class RedisInterEngineBusSignatureTest {
    private val mapper = jacksonObjectMapper()
    private val secret = "cluster-shared-secret"
    private val broadcastChannel = "ambon:engine:broadcast"

    private class Capture {
        var published: String? = null
        val publisher = BusPublisher { _, msg -> published = msg }
    }

    private fun receiverBus(
        engineId: String,
        sharedSecret: String,
        onReceiver: (((String) -> Unit)) -> Unit,
    ): RedisInterEngineBus {
        val subscriber =
            BusSubscriberSetup { channel, onMessage ->
                if (channel == broadcastChannel) onReceiver(onMessage)
            }
        return RedisInterEngineBus(
            engineId = engineId,
            publisher = BusPublisher { _, _ -> },
            subscriberSetup = subscriber,
            mapper = mapper,
            sharedSecret = sharedSecret,
        )
    }

    @Test
    fun `delivers a correctly signed broadcast to another engine`() =
        runBlocking {
            val capture = Capture()
            val sender =
                RedisInterEngineBus(
                    engineId = "engine-1",
                    publisher = capture.publisher,
                    subscriberSetup = BusSubscriberSetup { _, _ -> },
                    mapper = mapper,
                    sharedSecret = secret,
                )
            sender.start()

            var receiver: ((String) -> Unit)? = null
            val recvBus = receiverBus("engine-2", secret) { receiver = it }
            recvBus.start()

            sender.broadcast(InterEngineMessage.GlobalBroadcast(BroadcastType.ANNOUNCEMENT, "System", "hello"))
            receiver!!.invoke(capture.published!!)

            val got = recvBus.incoming().tryReceive().getOrNull()
            assertEquals(
                InterEngineMessage.GlobalBroadcast(BroadcastType.ANNOUNCEMENT, "System", "hello"),
                got,
            )
        }

    @Test
    fun `drops a broadcast whose payload was tampered after signing`() =
        runBlocking {
            val capture = Capture()
            val sender =
                RedisInterEngineBus(
                    engineId = "engine-1",
                    publisher = capture.publisher,
                    subscriberSetup = BusSubscriberSetup { _, _ -> },
                    mapper = mapper,
                    sharedSecret = secret,
                )
            sender.start()
            sender.broadcast(InterEngineMessage.GlobalBroadcast(BroadcastType.ANNOUNCEMENT, "System", "hello"))

            // Tamper with the signed payload — flip the broadcast text — keeping the stale signature.
            val tampered = capture.published!!.replace("hello", "you-are-hacked")

            var receiver: ((String) -> Unit)? = null
            val recvBus = receiverBus("engine-2", secret) { receiver = it }
            recvBus.start()
            receiver!!.invoke(tampered)

            assertNull(recvBus.incoming().tryReceive().getOrNull())
        }

    @Test
    fun `drops a broadcast signed with the wrong shared secret`() =
        runBlocking {
            val capture = Capture()
            val attacker =
                RedisInterEngineBus(
                    engineId = "engine-evil",
                    publisher = capture.publisher,
                    subscriberSetup = BusSubscriberSetup { _, _ -> },
                    mapper = mapper,
                    sharedSecret = "wrong-secret",
                )
            attacker.start()
            attacker.broadcast(InterEngineMessage.ShutdownRequest("attacker"))

            var receiver: ((String) -> Unit)? = null
            val recvBus = receiverBus("engine-2", secret) { receiver = it }
            recvBus.start()
            receiver!!.invoke(capture.published!!)

            assertNull(recvBus.incoming().tryReceive().getOrNull())
        }
}
