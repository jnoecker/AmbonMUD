package dev.ambon.persistence

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private val log = KotlinLogging.logger {}

class PersistenceWorker(
    private val repo: WriteCoalescingPlayerRepository,
    flushIntervalMs: Long = 5_000L,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractPeriodicWorker(flushIntervalMs, scope, dispatcher) {
    override val workerName = "PersistenceWorker"

    override suspend fun flush() {
        val result = repo.flushDirty()
        if (result.flushed > 0 || result.failed > 0) {
            log.debug { "PersistenceWorker flushed ${result.flushed} dirty record(s)" }
        }
        if (result.failed > 0) {
            log.warn { "PersistenceWorker flush: ${result.failed} record(s) failed to persist" }
        }
    }

    override suspend fun shutdownFlush() {
        val result = repo.flushAll()
        log.info { "PersistenceWorker shutdown: flushed ${result.flushed} record(s)" }
        if (result.failed > 0) {
            log.error { "PersistenceWorker shutdown: ${result.failed} record(s) failed to persist!" }
        }
    }
}
