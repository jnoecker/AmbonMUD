package dev.ambon.persistence

import dev.ambon.engine.WorldStateRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    flushIntervalMs: Long = 5_000L,
    scope: CoroutineScope,
    engineDispatcher: CoroutineDispatcher,
) : AbstractPeriodicWorker(flushIntervalMs, scope, engineDispatcher) {
    override val workerName = "WorldStatePersistenceWorker"

    override suspend fun flush() {
        if (!registry.isDirty) return
        val snapshot = registry.buildSnapshot()
        registry.clearDirty()
        withContext(Dispatchers.IO) { repo.save(snapshot) }
        log.debug { "WorldStatePersistenceWorker flushed world state" }
    }

    override suspend fun shutdownFlush() {
        if (!registry.isDirty) return
        val snapshot = registry.buildSnapshot()
        registry.clearDirty()
        withContext(Dispatchers.IO) { repo.save(snapshot) }
        log.info { "WorldStatePersistenceWorker shutdown: saved final world state" }
    }
}
