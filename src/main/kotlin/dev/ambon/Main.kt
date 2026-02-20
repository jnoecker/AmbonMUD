package dev.ambon

import dev.ambon.config.AmbonMudConfigLoader
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI

fun main() =
    runBlocking {
        val config = AmbonMudConfigLoader.load()
        val server = MudServer(config)
        server.start()
        println(
            "QuickMUD listening on telnet port ${config.deployment.telnetPort} " +
                "(telnet localhost ${config.deployment.telnetPort})",
        )
        println("QuickMUD web client at ${config.deployment.webClientUrl}")
        maybeAutoLaunchBrowser(config.deployment.webClientUrl, config.deployment.demoAutoLaunchBrowser)
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
