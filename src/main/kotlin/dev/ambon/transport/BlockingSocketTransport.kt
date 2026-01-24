package dev.ambon.transport

import dev.ambon.domain.SessionId
import dev.ambon.engine.InboundEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

class BlockingSocketTransport(
    private val port: Int,
    private val inbound: SendChannel<InboundEvent>,
    private val outboundRouter: OutboundRouter,
    private val scope: CoroutineScope,
) : Transport {
    private val nextId = AtomicLong(1)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    override suspend fun start() {
        serverSocket = ServerSocket(port)
        acceptJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val sock = serverSocket!!.accept()
                    sock.tcpNoDelay = true
                    val sessionId = SessionId(nextId.getAndIncrement())
                    val outboundQueue = Channel<String>(capacity = 200)
                    val session = NetworkSession(sessionId, sock, inbound, outboundQueue, scope)
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
