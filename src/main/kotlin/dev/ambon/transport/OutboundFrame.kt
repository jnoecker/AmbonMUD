package dev.ambon.transport

sealed interface OutboundFrame {
    data class Text(
        val content: String,
    ) : OutboundFrame

    data class Gmcp(
        val gmcpPackage: String,
        val jsonData: String,
    ) : OutboundFrame
}
