package dev.ambon.persistence

import dev.ambon.domain.housing.HouseRecord

class InMemoryHouseRepository : HouseRepository {
    private val houses = mutableMapOf<PlayerId, HouseRecord>()

    override suspend fun findByOwnerId(ownerId: PlayerId): HouseRecord? = houses[ownerId]

    override suspend fun findByOwnerName(name: String): HouseRecord? =
        houses.values.firstOrNull { it.ownerName.equals(name.trim(), ignoreCase = true) }

    override suspend fun save(record: HouseRecord) {
        houses[record.ownerId] = record
    }

    override suspend fun delete(ownerId: PlayerId) {
        houses.remove(ownerId)
    }

    fun clear() {
        houses.clear()
    }
}
