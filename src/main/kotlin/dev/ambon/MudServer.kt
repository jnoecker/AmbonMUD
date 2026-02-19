package dev.ambon

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.InboundEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.persistence.YamlPlayerRepository
import dev.ambon.transport.BlockingSocketTransport
import dev.ambon.transport.KtorWebSocketTransport
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
import java.util.concurrent.atomic.AtomicLong

class MudServer(
    private val telnetPort: Int,
    private val webPort: Int = 8080,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inbound = Channel<InboundEvent>(capacity = 10_000)
    private val outbound = Channel<OutboundEvent>(capacity = 10_000)

    private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val sessionIdSeq = AtomicLong(1)

    private lateinit var outboundRouter: OutboundRouter
    private lateinit var telnetTransport: BlockingSocketTransport
    private lateinit var webTransport: KtorWebSocketTransport
    private var engineJob: Job? = null
    private var routerJob: Job? = null

    private val clock = Clock.systemUTC()

    val playerRepo =
        YamlPlayerRepository(
            rootDir = Paths.get("data/players"),
        )

    val items = ItemRegistry()
    val mobs = MobRegistry()

    val world = WorldFactory.demoWorld()
    val tickMillis: Long = 100L
    val scheduler: Scheduler = Scheduler(clock)

    val players =
        PlayerRegistry(
            startRoom = world.startRoom,
            repo = playerRepo,
            items = items,
            clock = clock,
        )

    suspend fun start() {
        outboundRouter = OutboundRouter(outbound, scope)
        routerJob = outboundRouter.start()

        engineJob =
            scope.launch(engineDispatcher) {
                GameEngine(inbound, outbound, players, world, mobs, items, clock, tickMillis, scheduler).run()
            }

        telnetTransport =
            BlockingSocketTransport(
                port = telnetPort,
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = ::allocateSessionId,
                scope = scope,
            )
        telnetTransport.start()

        webTransport =
            KtorWebSocketTransport(
                port = webPort,
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = ::allocateSessionId,
            )
        webTransport.start()
    }

    suspend fun stop() {
        runCatching { telnetTransport.stop() }
        runCatching { webTransport.stop() }
        scope.cancel()
        engineDispatcher.close()
        inbound.close()
        outbound.close()
    }

    private fun allocateSessionId(): SessionId = SessionId(sessionIdSeq.getAndIncrement())
}
