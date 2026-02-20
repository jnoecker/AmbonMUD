package dev.ambon

import dev.ambon.config.AppConfigLoader
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI

fun main() =
    runBlocking {
        val config = AppConfigLoader.load()
        val server = MudServer(config)
        val webClientUrl = config.demo.webClientUrl ?: "http://${config.demo.webClientHost}:${config.server.webPort}"
        server.start()
        println("QuickMUD listening on telnet port ${config.server.telnetPort} (telnet localhost ${config.server.telnetPort})")
        println("QuickMUD web client at $webClientUrl")
        maybeAutoLaunchBrowser(webClientUrl, config.demo.autoLaunchBrowser)
        // keep alive
        kotlinx.coroutines.delay(Long.MAX_VALUE)
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
        name = "quickmud-browser-launcher"
        start()
    }
}
