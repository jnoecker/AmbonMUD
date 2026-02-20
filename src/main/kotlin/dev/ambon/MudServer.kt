package dev.ambon

import dev.ambon.config.AppConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.WorldFactory
import dev.ambon.engine.GameEngine
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
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
    private val config: AppConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inbound = Channel<InboundEvent>(capacity = config.server.inboundChannelCapacity)
    private val outbound = Channel<OutboundEvent>(capacity = config.server.outboundChannelCapacity)

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
            rootDir = Paths.get(config.persistence.rootDir),
        )

    val items = ItemRegistry()
    val mobs = MobRegistry()
    val progression = PlayerProgression(config.progression)

    val world = WorldFactory.demoWorld(config.world.resources)
    val tickMillis: Long = config.server.tickMillis
    val scheduler: Scheduler = Scheduler(clock)

    val players =
        PlayerRegistry(
            startRoom = world.startRoom,
            repo = playerRepo,
            items = items,
            clock = clock,
            progression = progression,
        )

    suspend fun start() {
        outboundRouter = OutboundRouter(outbound, scope)
        routerJob = outboundRouter.start()

        engineJob =
            scope.launch(engineDispatcher) {
                GameEngine(
                    inbound = inbound,
                    outbound = outbound,
                    players = players,
                    world = world,
                    mobs = mobs,
                    items = items,
                    clock = clock,
                    tickMillis = tickMillis,
                    scheduler = scheduler,
                    loginConfig = config.login,
                    engineConfig = config.engine,
                    progression = progression,
                ).run()
            }

        telnetTransport =
            BlockingSocketTransport(
                port = config.server.telnetPort,
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = ::allocateSessionId,
                scope = scope,
                sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
                maxLineLen = config.transport.telnet.maxLineLen,
                maxNonPrintablePerLine = config.transport.telnet.maxNonPrintablePerLine,
            )
        telnetTransport.start()

        webTransport =
            KtorWebSocketTransport(
                port = config.server.webPort,
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = ::allocateSessionId,
                host = config.transport.websocket.host,
                sessionOutboundQueueCapacity = config.server.sessionOutboundQueueCapacity,
                stopGraceMillis = config.transport.websocket.stopGraceMillis,
                stopTimeoutMillis = config.transport.websocket.stopTimeoutMillis,
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
