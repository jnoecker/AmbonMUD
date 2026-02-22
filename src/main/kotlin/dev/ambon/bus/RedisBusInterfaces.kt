package dev.ambon.bus

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
