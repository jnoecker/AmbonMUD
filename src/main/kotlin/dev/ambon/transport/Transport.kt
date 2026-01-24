package dev.ambon.transport

interface Transport {
    suspend fun start()

    suspend fun stop()
}
