package dev.ambon.transport

sealed interface OutboundFrame {
    val enqueuedAt: Long

    data class Text(
        val content: String,
    ) : OutboundFrame {
        override val enqueuedAt: Long = System.currentTimeMillis()
    }

    data class Gmcp(
        val gmcpPackage: String,
        val jsonData: String,
    ) : OutboundFrame {
        override val enqueuedAt: Long = System.currentTimeMillis()
    }
}
