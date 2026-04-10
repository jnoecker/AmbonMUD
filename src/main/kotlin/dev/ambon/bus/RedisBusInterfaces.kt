package dev.ambon.bus

import dev.ambon.redis.RedisConnectionManager
import io.lettuce.core.pubsub.RedisPubSubAdapter

fun interface BusPublisher {
    fun publish(
        channel: String,
        message: String,
    )
}

fun interface BusSubscriberSetup {
    fun startListening(
        channelName: String,
        onMessage: (String) -> Unit,
    )
}

/** Creates a [BusPublisher] that publishes to Redis via the given [manager]. */
fun redisBusPublisher(manager: RedisConnectionManager): BusPublisher =
    BusPublisher { ch, msg ->
        manager.withAsyncCommands { it.publish(ch, msg) }
    }

/** Creates a [BusSubscriberSetup] that subscribes to Redis pub/sub via the given [manager]. */
fun redisBusSubscriberSetup(manager: RedisConnectionManager): BusSubscriberSetup =
    BusSubscriberSetup { ch, onMessage ->
        val conn = manager.connectPubSub()
        if (conn != null) {
            conn.addListener(
                object : RedisPubSubAdapter<String, String>() {
                    override fun message(
                        channel: String,
                        message: String,
                    ) = onMessage(message)
                },
            )
            conn.sync().subscribe(ch)
        }
    }
