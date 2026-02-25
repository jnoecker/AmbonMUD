package dev.ambon.bus

internal class FakePublisher : BusPublisher {
    val messages = mutableListOf<Pair<String, String>>()

    override fun publish(
        channel: String,
        message: String,
    ) {
        messages += channel to message
    }
}

internal class FakeSubscriberSetup : BusSubscriberSetup {
    private var listener: ((String) -> Unit)? = null

    fun inject(message: String) {
        listener?.invoke(message)
    }

    override fun startListening(
        channelName: String,
        onMessage: (String) -> Unit,
    ) {
        listener = onMessage
    }
}
