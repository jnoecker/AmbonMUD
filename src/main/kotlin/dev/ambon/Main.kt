package dev.ambon

import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI

fun main() =
    runBlocking {
        val server = MudServer(telnetPort = 4000, webPort = 8080)
        server.start()
        println("QuickMUD listening on telnet port 4000 (telnet localhost 4000)")
        println("QuickMUD web client at $WEB_CLIENT_URL")
        maybeAutoLaunchBrowser(WEB_CLIENT_URL)
        // keep alive
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }

private fun maybeAutoLaunchBrowser(url: String) {
    if (!java.lang.Boolean.getBoolean(DEMO_AUTO_LAUNCH_PROP)) return

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

private const val DEMO_AUTO_LAUNCH_PROP = "quickmud.demo.autolaunchBrowser"
private const val WEB_CLIENT_URL = "http://localhost:8080"
