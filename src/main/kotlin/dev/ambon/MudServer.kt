package dev.ambon

import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.YamlPlayerRepository
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
import java.nio.file.Paths
import java.time.Clock
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

    private val clock = Clock.systemUTC()

    val playerRepo =
        YamlPlayerRepository(
            rootDir = Paths.get("data/players"),
        )

    val world = WorldFactory.demoWorld()

    val players =
        PlayerRegistry(
            startRoom = world.startRoom,
            repo = playerRepo,
            clock = clock,
        )

    suspend fun start() {
        outboundRouter = OutboundRouter(outbound, scope)
        routerJob = outboundRouter.start()

        engineJob =
            scope.launch(engineDispatcher) {
                GameEngine(inbound, outbound, players).run()
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
