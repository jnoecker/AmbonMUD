package dev.ambon.persistence

interface WorldStateRepository {
    suspend fun load(): WorldStateSnapshot?

    suspend fun save(snapshot: WorldStateSnapshot)
}
