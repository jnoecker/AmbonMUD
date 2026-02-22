package dev.ambon.persistence

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

class PersistenceWorker(
    private val repo: WriteCoalescingPlayerRepository,
    private val flushIntervalMs: Long = 5_000L,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null

    fun start(): Job {
        val j =
            scope.launch(dispatcher) {
                log.info { "PersistenceWorker started (flushIntervalMs=$flushIntervalMs)" }
                while (isActive) {
                    delay(flushIntervalMs)
                    try {
                        val flushed = repo.flushDirty()
                        if (flushed > 0) {
                            log.debug { "PersistenceWorker flushed $flushed dirty record(s)" }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        log.error(e) { "PersistenceWorker flush error" }
                    }
                }
            }
        job = j
        return j
    }

    suspend fun shutdown() {
        job?.cancelAndJoin()
        job = null
        try {
            val flushed = repo.flushAll()
            log.info { "PersistenceWorker shutdown: flushed $flushed record(s)" }
        } catch (e: Exception) {
            log.error(e) { "PersistenceWorker shutdown flush failed" }
        }
    }
}
