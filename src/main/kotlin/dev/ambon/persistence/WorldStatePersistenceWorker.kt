package dev.ambon.persistence

import dev.ambon.engine.WorldStateRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

/**
 * Periodically flushes [WorldStateRegistry] to [WorldStateRepository] when dirty.
 *
 * Must be launched on the engine dispatcher so that [WorldStateRegistry.buildSnapshot] and
 * [WorldStateRegistry.clearDirty] are called on the engine thread. Actual I/O is dispatched
 * to [Dispatchers.IO] via [withContext] to avoid blocking the engine.
 */
class WorldStatePersistenceWorker(
    private val registry: WorldStateRegistry,
    private val repo: WorldStateRepository,
    private val flushIntervalMs: Long = 5_000L,
    private val scope: CoroutineScope,
    /** Must be the single-threaded engine dispatcher so snapshot building is thread-safe. */
    private val engineDispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    fun start(): Job {
        val j =
            scope.launch(engineDispatcher) {
                log.info { "WorldStatePersistenceWorker started (flushIntervalMs=$flushIntervalMs)" }
                while (isActive) {
                    delay(flushIntervalMs)
                    flush()
                }
            }
        job = j
        return j
    }

    /**
     * Cancels the periodic job then performs a final flush.
     * Safe to call after the engine coroutine has stopped (no concurrent modifications).
     */
    suspend fun shutdown() {
        job?.cancelAndJoin()
        job = null
        try {
            if (registry.isDirty) {
                val snapshot = registry.buildSnapshot()
                registry.clearDirty()
                withContext(Dispatchers.IO) { repo.save(snapshot) }
                log.info { "WorldStatePersistenceWorker shutdown: saved final world state" }
            }
        } catch (e: Exception) {
            log.error(e) { "WorldStatePersistenceWorker shutdown flush failed" }
        }
    }

    // Called on the engine dispatcher — safe to read/write registry here.
    private suspend fun flush() {
        if (!registry.isDirty) return
        try {
            val snapshot = registry.buildSnapshot()
            registry.clearDirty()
            withContext(Dispatchers.IO) { repo.save(snapshot) }
            log.debug { "WorldStatePersistenceWorker flushed world state" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.error(e) { "WorldStatePersistenceWorker flush error" }
        }
    }
}
