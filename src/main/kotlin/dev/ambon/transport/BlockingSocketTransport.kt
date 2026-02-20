package dev.ambon.transport

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.InboundEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket

class BlockingSocketTransport(
    private val port: Int,
    private val inbound: SendChannel<InboundEvent>,
    private val outboundRouter: OutboundRouter,
    private val sessionIdFactory: () -> SessionId,
    private val scope: CoroutineScope,
    private val sessionQueueCapacity: Int = 200,
    private val lineMaxLength: Int = 1024,
    private val maxNonPrintablePerLine: Int = 32,
    private val readBufferBytes: Int = 4096,
) : Transport {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    override suspend fun start() {
        serverSocket = ServerSocket(port)
        acceptJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val sock = serverSocket!!.accept()
                    sock.tcpNoDelay = true
                    val sessionId = sessionIdFactory()
                    val outboundQueue = Channel<String>(capacity = sessionQueueCapacity)
                    val session =
                        NetworkSession(
                            sessionId = sessionId,
                            socket = sock,
                            inbound = inbound,
                            outboundQueue = outboundQueue,
                            onDisconnected = { outboundRouter.unregister(sessionId) },
                            scope = scope,
                            lineMaxLength = lineMaxLength,
                            maxNonPrintablePerLine = maxNonPrintablePerLine,
                            readBufferBytes = readBufferBytes,
                        )
                    outboundRouter.register(sessionId, outboundQueue) { reason ->
                        session.closeNow(reason)
                    }
                    session.start()
                }
            }
    }

    override suspend fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
    }
}
