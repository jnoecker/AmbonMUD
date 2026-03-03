package dev.ambon.persistence

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/**
 * Base class for background workers that periodically flush state.
 * Subclasses implement [flush] (called each tick) and [shutdownFlush] (called once on shutdown).
 */
abstract class AbstractPeriodicWorker(
    private val flushIntervalMs: Long,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    protected abstract val workerName: String

    protected abstract suspend fun flush()

    protected abstract suspend fun shutdownFlush()

    fun start(): Job {
        val j =
            scope.launch(dispatcher) {
                log.info { "$workerName started (flushIntervalMs=$flushIntervalMs)" }
                while (isActive) {
                    delay(flushIntervalMs)
                    try {
                        flush()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        log.error(e) { "$workerName flush error" }
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
            shutdownFlush()
        } catch (e: Exception) {
            log.error(e) { "$workerName shutdown flush failed" }
        }
    }
}
