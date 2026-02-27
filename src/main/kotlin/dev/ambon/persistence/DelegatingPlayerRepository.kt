package dev.ambon.persistence

/**
 * Base implementation for repository decorators that keep a local cache.
 *
 * Subclasses provide cache primitives and can override persistence hooks
 * when writes are not immediate (for example, coalesced writes).
 */
abstract class DelegatingPlayerRepository(
    protected val delegate: PlayerRepository,
) : PlayerRepository {
    protected abstract suspend fun lookupCachedByName(name: String): PlayerRecord?

    protected abstract suspend fun lookupCachedById(id: PlayerId): PlayerRecord?

    protected abstract suspend fun storeInCache(record: PlayerRecord)

    protected open suspend fun storeOnReadMiss(record: PlayerRecord) = storeInCache(record)

    protected open suspend fun storeOnCreate(record: PlayerRecord) = storeInCache(record)

    protected open suspend fun storeOnSave(record: PlayerRecord) = storeInCache(record)

    protected open suspend fun persistSave(record: PlayerRecord) {
        delegate.save(record)
    }

    override suspend fun findByName(name: String): PlayerRecord? =
        lookupCachedByName(name) ?: delegate.findByName(name)?.also { storeOnReadMiss(it) }

    override suspend fun findById(id: PlayerId): PlayerRecord? =
        lookupCachedById(id) ?: delegate.findById(id)?.also { storeOnReadMiss(it) }

    override suspend fun create(request: PlayerCreationRequest): PlayerRecord =
        delegate.create(request).also { storeOnCreate(it) }

    override suspend fun save(record: PlayerRecord) {
        persistSave(record)
        storeOnSave(record)
    }
}
