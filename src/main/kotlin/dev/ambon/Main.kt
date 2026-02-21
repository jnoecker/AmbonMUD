package dev.ambon

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import dev.ambon.config.AppConfigLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI

private val log = KotlinLogging.logger {}

fun main() =
    runBlocking {
        val config = AppConfigLoader.load()

        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).level =
            Level.toLevel(config.logging.level, Level.INFO)
        config.logging.packageLevels.forEach { (pkg, lvl) ->
            ctx.getLogger(pkg).level = Level.toLevel(lvl, Level.INFO)
        }

        val server = MudServer(config)
        val webClientUrl = config.demo.webClientUrl ?: "http://${config.demo.webClientHost}:${config.server.webPort}"
        server.start()
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking { server.stop() }
            },
        )
        log.info { "AmbonMUD listening on telnet port ${config.server.telnetPort} (telnet localhost ${config.server.telnetPort})" }
        log.info { "AmbonMUD web client at $webClientUrl" }
        maybeAutoLaunchBrowser(webClientUrl, config.demo.autoLaunchBrowser)
        server.awaitShutdown()
        log.info { "Shutdown signal received. Stopping server..." }
        server.stop()
    }

private fun maybeAutoLaunchBrowser(
    url: String,
    enabled: Boolean,
) {
    if (!enabled) return

    Thread {
        Thread.sleep(300)
        val opened =
            runCatching {
                if (!Desktop.isDesktopSupported()) return@runCatching false
                val desktop = Desktop.getDesktop()
                if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching false
                desktop.browse(URI(url))
                true
            }.getOrDefault(false)

        if (!opened) {
            println("Demo mode: couldn't auto-open a browser. Open $url manually.")
        }
    }.apply {
        isDaemon = true
        name = "ambonMUD-browser-launcher"
        start()
    }
}
