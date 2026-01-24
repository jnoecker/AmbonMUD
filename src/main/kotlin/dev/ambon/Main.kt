package dev.ambon

import kotlinx.coroutines.runBlocking

fun main() =
    runBlocking {
        val server = MudServer(port = 4000)
        server.start()
        println("QuickMUD listening on port 4000 (telnet localhost 4000)")
        // keep alive
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }
