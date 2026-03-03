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
        val flushed = repo.flushDirty()
        if (flushed > 0) {
            log.debug { "PersistenceWorker flushed $flushed dirty record(s)" }
        }
    }

    override suspend fun shutdownFlush() {
        val flushed = repo.flushAll()
        log.info { "PersistenceWorker shutdown: flushed $flushed record(s)" }
    }
}
