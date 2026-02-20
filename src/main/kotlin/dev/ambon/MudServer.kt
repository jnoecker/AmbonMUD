package dev.ambon

import dev.ambon.config.AmbonMudConfig
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
    private val config: AmbonMudConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inbound = Channel<InboundEvent>(capacity = config.deployment.inboundChannelCapacity)
    private val outbound = Channel<OutboundEvent>(capacity = config.deployment.outboundChannelCapacity)

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
            rootDir = Paths.get(config.deployment.playerDataDir),
        )

    val items = ItemRegistry()
    val mobs = MobRegistry()

    val world = WorldFactory.fromResources(config.deployment.worldResources)
    val scheduler: Scheduler = Scheduler(clock, config.gameplay.schedulerMaxActionsPerTick)

    val players =
        PlayerRegistry(
            startRoom = world.startRoom,
            repo = playerRepo,
            items = items,
            clock = clock,
        )

    suspend fun start() {
        outboundRouter = OutboundRouter(outbound, scope, promptText = config.deployment.promptText)
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
                    tickMillis = config.gameplay.engineTickMillis,
                    scheduler = scheduler,
                    gameplay = config.gameplay,
                ).run()
            }

        telnetTransport =
            BlockingSocketTransport(
                port = config.deployment.telnetPort,
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = ::allocateSessionId,
                scope = scope,
                sessionQueueCapacity = config.deployment.sessionOutboundQueueCapacity,
                lineMaxLength = config.deployment.telnetLineMaxLength,
                maxNonPrintablePerLine = config.deployment.telnetMaxNonPrintablePerLine,
                readBufferBytes = config.deployment.telnetReadBufferBytes,
            )
        telnetTransport.start()

        webTransport =
            KtorWebSocketTransport(
                port = config.deployment.webPort,
                inbound = inbound,
                outboundRouter = outboundRouter,
                sessionIdFactory = ::allocateSessionId,
                host = config.deployment.webHost,
                sessionQueueCapacity = config.deployment.sessionOutboundQueueCapacity,
                stopGracePeriodMillis = config.deployment.webStopGracePeriodMillis,
                stopTimeoutMillis = config.deployment.webStopTimeoutMillis,
                maxCloseReasonLength = config.deployment.webMaxCloseReasonLength,
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
