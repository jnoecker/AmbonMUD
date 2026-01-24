package dev.ambon

import dev.ambon.engine.GameEngine
import dev.ambon.engine.InboundEvent
import dev.ambon.engine.OutboundEvent
import dev.ambon.transport.BlockingSocketTransport
import dev.ambon.transport.OutboundRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MudServer(
    private val port: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inbound = Channel<InboundEvent>(capacity = 10_000)
    private val outbound = Channel<OutboundEvent>(capacity = 10_000)

    private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private lateinit var outboundRouter: OutboundRouter
    private lateinit var transport: BlockingSocketTransport
    private var engineJob: Job? = null
    private var routerJob: Job? = null

    suspend fun start() {
        outboundRouter = OutboundRouter(outbound, scope)
        routerJob = outboundRouter.start()

        engineJob =
            scope.launch(engineDispatcher) {
                GameEngine(inbound, outbound).run()
            }

        transport = BlockingSocketTransport(port, inbound, outboundRouter, scope)
        transport.start()
    }

    suspend fun stop() {
        transport.stop()
        scope.cancel()
        engineDispatcher.close()
        inbound.close()
        outbound.close()
    }
}
