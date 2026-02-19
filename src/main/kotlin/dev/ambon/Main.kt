package dev.ambon

import kotlinx.coroutines.runBlocking

fun main() =
    runBlocking {
        val server = MudServer(telnetPort = 4000, webPort = 8080)
        server.start()
        println("QuickMUD listening on telnet port 4000 (telnet localhost 4000)")
        println("QuickMUD web client at http://localhost:8080")
        // keep alive
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }
