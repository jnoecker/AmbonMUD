package dev.ambon.swarm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.random.Random

private val log = KotlinLogging.logger {}

private val ansiRegex = Regex("\u001B\\[[0-9;]*[A-Za-z]")

private fun stripAnsi(text: String): String = ansiRegex.replace(text, "")

private fun cleanLine(raw: String): String = stripAnsi(raw.replace("\r", "").replace("\uFFFD", "")).trim()

class SwarmRunner(
    private val config: SwarmConfig,
) {
    private val metrics = SwarmMetrics()

    suspend fun run() {
        val startedAt = System.currentTimeMillis()
        val creds =
            (1..config.run.totalBots).map { idx ->
                BotCredential(name = generateName(config.run.namespacePrefix, idx), password = config.run.basePassword)
            }

        val launchIntervalMs = computeLaunchIntervalMillis(config.run.totalBots, config.run.rampSeconds)
        val endAt = startedAt + config.run.durationSeconds * 1000L

        log.info {
            "Starting swarm: bots=${config.run.totalBots}, duration=${config.run.durationSeconds}s, ramp=${config.run.rampSeconds}s"
        }

        val parent = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = mutableListOf<Job>()

        creds.forEachIndexed { idx, credential ->
            val botRandom =
                if (config.run.seed != null) Random(config.run.seed + idx) else Random(Random.nextLong())
            val protocol = selectProtocol(config.run.protocolMix, botRandom)
            jobs +=
                parent.launch {
                    BotWorker(
                        id = idx + 1,
                        protocol = protocol,
                        credential = credential,
                        config = config,
                        metrics = metrics,
                        random = botRandom,
                        endAtEpochMs = endAt,
                    ).run()
                }
            if (launchIntervalMs > 0) delay(launchIntervalMs)
        }

        val monitor =
            parent.launch {
                while (isActive && System.currentTimeMillis() < endAt) {
                    delay(5_000)
                    log.info { metrics.snapshotLine("progress") }
                }
            }

        val remaining = endAt - System.currentTimeMillis()
        if (remaining > 0) delay(remaining)
        parent.coroutineContext[Job]?.cancel(CancellationException("Swarm duration reached"))

        jobs.forEach {
            runCatching { it.join() }
        }
        monitor.cancel()

        println(metrics.summary(config, System.currentTimeMillis() - startedAt))
    }

    private fun computeLaunchIntervalMillis(
        totalBots: Int,
        rampSeconds: Int,
    ): Long {
        if (rampSeconds <= 0 || totalBots <= 1) return 0L
        return max(1L, (rampSeconds * 1000L) / totalBots)
    }

    private fun selectProtocol(
        mix: ProtocolMixConfig,
        random: Random,
    ): BotProtocol = if (random.nextInt(100) < mix.telnetPercent) BotProtocol.TELNET else BotProtocol.WEBSOCKET

    private fun generateName(
        prefix: String,
        idx: Int,
    ): String {
        val body = "${prefix}_${idx.toString().padStart(4, '0')}"
        return body.take(16)
    }
}

enum class BotProtocol {
    TELNET,
    WEBSOCKET,
}

data class BotCredential(
    val name: String,
    val password: String,
)

private class BotWorker(
    private val id: Int,
    private val protocol: BotProtocol,
    private val credential: BotCredential,
    private val config: SwarmConfig,
    private val metrics: SwarmMetrics,
    private val random: Random,
    private val endAtEpochMs: Long,
) {
    suspend fun run() {
        while (System.currentTimeMillis() < endAtEpochMs) {
            val connectionStarted = System.nanoTime()
            val connection =
                runCatching { connect(protocol) }.getOrElse {
                    metrics.connectionFailed()
                    delay(250)
                    continue
                }
            metrics.connectionSucceeded(Duration.ofNanos(System.nanoTime() - connectionStarted).toMillis())

            try {
                val loggedIn = login(connection)
                if (!loggedIn) {
                    metrics.loginFailed()
                    connection.close()
                    delay(250)
                    continue
                }
                metrics.loginSucceeded()

                while (System.currentTimeMillis() < endAtEpochMs) {
                    val action = WeightedActionPicker.pick(config.behavior.weights, random)
                    if (action == BotAction.LOGIN_CHURN) {
                        metrics.intentionalDisconnect()
                        connection.close()
                        break
                    }
                    performAction(connection, action)
                    delay(random.nextInt(config.behavior.commandIntervalMs.min, config.behavior.commandIntervalMs.max + 1).toLong())
                }
            } catch (_: Throwable) {
                metrics.connectionDropped()
            } finally {
                runCatching { connection.close() }
            }
        }
    }

    private suspend fun login(connection: BotConnection): Boolean {
        var stage = 0
        var consecutiveTimeouts = 0
        val started = System.nanoTime()
        while (System.currentTimeMillis() < endAtEpochMs) {
            val line = connection.pollLine(timeoutMillis = 1500)
            if (line == null) {
                consecutiveTimeouts++
                if (consecutiveTimeouts >= 3) return false
                continue
            }
            consecutiveTimeouts = 0
            val lower = line.lowercase()
            when {
                "enter your name" in lower -> {
                    connection.sendLine(credential.name)
                    stage = max(stage, 1)
                }

                "create a new user" in lower -> {
                    connection.sendLine("yes")
                    stage = max(stage, 2)
                }

                "create a password" in lower -> {
                    connection.sendLine(credential.password)
                    stage = max(stage, 3)
                }

                "choose your race" in lower -> {
                    val choice = config.behavior.races.random(random)
                    connection.sendLine(choice)
                    stage = max(stage, 4)
                }

                "choose your class" in lower -> {
                    val choice = config.behavior.classes.random(random)
                    connection.sendLine(choice)
                    stage = max(stage, 5)
                }

                lower.contains("password:") -> {
                    connection.sendLine(credential.password)
                    stage = max(stage, 6)
                }

                "exits:" in lower -> {
                    metrics.loginLatency(Duration.ofNanos(System.nanoTime() - started).toMillis())
                    return true
                }
            }
        }
        return false
    }

    private suspend fun performAction(
        connection: BotConnection,
        action: BotAction,
    ) {
        when (action) {
            BotAction.IDLE -> Unit
            BotAction.MOVEMENT -> connection.sendLine(config.behavior.movementCommands.random(random))
            BotAction.CHAT -> {
                val phrase = config.behavior.chatPhrases.random(random)
                connection.sendLine("say [swarm-$id] $phrase")
            }

            BotAction.AUTO_COMBAT -> connection.sendLine(config.behavior.combatCommands.random(random))
            BotAction.LOGIN_CHURN -> Unit
        }
        if (action != BotAction.IDLE) {
            metrics.commandSent()
        }
    }

    private fun connect(protocol: BotProtocol): BotConnection =
        when (protocol) {
            BotProtocol.TELNET -> TelnetBotConnection(config.target.host, config.target.telnetPort)
            BotProtocol.WEBSOCKET -> WebSocketBotConnection(config.target.websocketUrl)
        }
}

private interface BotConnection {
    suspend fun sendLine(line: String)

    suspend fun pollLine(timeoutMillis: Long): String?

    fun close()
}

private class TelnetBotConnection(
    host: String,
    port: Int,
) : BotConnection {
    private val socket = Socket()
    private val incoming = Collections.synchronizedList(mutableListOf<String>())
    private val closed = AtomicBoolean(false)
    private val outputLock = Mutex()
    private val output: java.io.OutputStream
    private val readerThread: Thread

    init {
        socket.connect(InetSocketAddress(host, port), 3000)
        socket.soTimeout = 500
        val input = socket.getInputStream()
        output = socket.getOutputStream()

        readerThread =
            Thread {
                val buf = ByteArray(4096)
                val sb = StringBuilder()
                while (!closed.get()) {
                    val n = runCatching { input.read(buf) }.getOrElse { -1 }
                    if (n <= 0) break
                    val text = String(buf, 0, n, StandardCharsets.UTF_8)
                    sb.append(text)
                    while (true) {
                        val idx = sb.indexOf("\n")
                        if (idx < 0) break
                        val line = cleanLine(sb.substring(0, idx))
                        sb.delete(0, idx + 1)
                        if (line.isNotBlank()) incoming += line
                    }
                }
                closed.set(true)
            }.apply {
                isDaemon = true
                start()
            }
    }

    override suspend fun sendLine(line: String) {
        outputLock.withLock {
            output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
    }

    override suspend fun pollLine(timeoutMillis: Long): String? {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMillis) {
            synchronized(incoming) {
                if (incoming.isNotEmpty()) {
                    return incoming.removeFirst()
                }
            }
            delay(25)
        }
        return null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            readerThread.interrupt()
        }
    }
}

private class WebSocketBotConnection(
    url: String,
) : BotConnection {
    private val client = HttpClient.newHttpClient()
    private val incoming = Collections.synchronizedList(mutableListOf<String>())
    private val closed = AtomicBoolean(false)
    private val ws: WebSocket
    private val outputLock = Mutex()

    init {
        ws =
            client
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(url), Listener(incoming, closed))
                .join()
    }

    override suspend fun sendLine(line: String) {
        outputLock.withLock {
            ws.sendText(line, true).join()
        }
    }

    override suspend fun pollLine(timeoutMillis: Long): String? {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMillis) {
            synchronized(incoming) {
                if (incoming.isNotEmpty()) {
                    return incoming.removeFirst()
                }
            }
            delay(25)
        }
        return null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join() }
        }
    }

    private class Listener(
        private val incoming: MutableList<String>,
        private val closed: AtomicBoolean,
    ) : WebSocket.Listener {
        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletionStage<*> {
            synchronized(incoming) {
                incoming +=
                    data
                        .toString()
                        .split("\n")
                        .map { cleanLine(it) }
                        .filter { it.isNotBlank() }
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletionStage<*> {
            closed.set(true)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable,
        ) {
            closed.set(true)
        }
    }
}
