package dev.ambon.transport

import dev.ambon.bus.InboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket

private val log = KotlinLogging.logger {}

class BlockingSocketTransport(
    private val port: Int,
    private val inbound: InboundBus,
    private val outboundRouter: OutboundRouter,
    private val sessionIdFactory: () -> SessionId,
    private val scope: CoroutineScope,
    private val sessionOutboundQueueCapacity: Int = 200,
    private val maxLineLen: Int = 1024,
    private val maxNonPrintablePerLine: Int = 32,
    private val maxInboundBackpressureFailures: Int = 3,
    private val metrics: GameMetrics = GameMetrics.noop(),
) : Transport {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    override suspend fun start() {
        serverSocket = ServerSocket(port)
        log.info { "Telnet listening on port $port" }
        acceptJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val sock = serverSocket!!.accept()
                    sock.tcpNoDelay = true
                    val sessionId = sessionIdFactory()
                    log.debug { "New telnet connection: remoteAddress=${sock.remoteSocketAddress} sessionId=$sessionId" }
                    val outboundQueue = Channel<String>(capacity = sessionOutboundQueueCapacity)
                    val session =
                        NetworkSession(
                            sessionId = sessionId,
                            socket = sock,
                            inbound = inbound,
                            outboundQueue = outboundQueue,
                            onDisconnected = { outboundRouter.unregister(sessionId) },
                            scope = scope,
                            onOutboundFrameWritten = { outboundRouter.onSessionQueueFrameConsumed(sessionId) },
                            maxLineLen = maxLineLen,
                            maxNonPrintablePerLine = maxNonPrintablePerLine,
                            maxInboundBackpressureFailures = maxInboundBackpressureFailures,
                            metrics = metrics,
                        )
                    outboundRouter.register(
                        sessionId = sessionId,
                        queue = outboundQueue,
                        queueCapacity = sessionOutboundQueueCapacity,
                        transport = "telnet",
                        defaultAnsiEnabled = false,
                    ) { reason ->
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
