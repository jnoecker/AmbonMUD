package dev.ambon.persistence

import dev.ambon.domain.housing.HouseRecord

interface HouseRepository {
    suspend fun findByOwnerId(ownerId: PlayerId): HouseRecord?

    suspend fun findByOwnerName(name: String): HouseRecord?

    suspend fun save(record: HouseRecord)

    suspend fun delete(ownerId: PlayerId)
}
