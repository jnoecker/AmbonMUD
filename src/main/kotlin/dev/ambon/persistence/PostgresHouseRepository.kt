package dev.ambon.persistence

import com.fasterxml.jackson.core.type.TypeReference
import dev.ambon.domain.housing.HouseRecord
import dev.ambon.domain.housing.HouseRoomRecord
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.Direction
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

private val log = KotlinLogging.logger {}
private val roomsType = object : TypeReference<List<RoomColumnDto>>() {}

class PostgresHouseRepository(
    private val database: Database,
) : HouseRepository {
    private val mapper = jsonMapper

    override suspend fun findByOwnerId(ownerId: PlayerId): HouseRecord? =
        database.dbQuery {
            HousesTable
                .selectAll()
                .where { HousesTable.ownerId eq ownerId.value }
                .firstOrNull()
                ?.toHouseRecord()
        }

    override suspend fun findByOwnerName(name: String): HouseRecord? =
        database.dbQuery {
            HousesTable
                .selectAll()
                .where { HousesTable.ownerNameLower eq name.trim().lowercase() }
                .firstOrNull()
                ?.toHouseRecord()
        }

    override suspend fun save(record: HouseRecord) {
        database.dbQuery { upsertRow(record) }
    }

    override suspend fun delete(ownerId: PlayerId) {
        database.dbQuery {
            HousesTable.deleteWhere { HousesTable.ownerId eq ownerId.value }
        }
    }

    // -------- internals --------

    private fun upsertRow(record: HouseRecord) {
        HousesTable.upsert(HousesTable.ownerId) {
            it[ownerId] = record.ownerId.value
            it[ownerName] = record.ownerName
            it[ownerNameLower] = record.ownerName.lowercase()
            it[rooms] = mapper.writeValueAsString(record.rooms.map { r -> RoomColumnDto.from(r) })
            it[createdAtEpochMs] = record.createdAtEpochMs
        }
    }

    private fun ResultRow.toHouseRecord(): HouseRecord {
        val rawRooms: List<RoomColumnDto> =
            runCatching {
                mapper.readValue(this[HousesTable.rooms], roomsType)
            }.onFailure { ex ->
                log.warn(ex) { "Failed to deserialize rooms for house owner ${this[HousesTable.ownerId]}; defaulting to empty" }
            }.getOrDefault(emptyList())
        return HouseRecord(
            ownerId = PlayerId(this[HousesTable.ownerId]),
            ownerName = this[HousesTable.ownerName],
            rooms = rawRooms.map { it.toDomain() },
            createdAtEpochMs = this[HousesTable.createdAtEpochMs],
        )
    }
}

/**
 * JSON DTO for room data stored in the `rooms` TEXT column.
 * Directions are serialised as strings so they survive schema evolution.
 */
internal data class RoomColumnDto(
    val templateId: String,
    val customTitle: String? = null,
    val customDescription: String? = null,
    val exits: Map<String, Int> = emptyMap(),
    val storedItems: List<ItemInstance> = emptyList(),
) {
    fun toDomain(): HouseRoomRecord =
        HouseRoomRecord(
            templateId = templateId,
            customTitle = customTitle,
            customDescription = customDescription,
            exits = exits.mapKeys { Direction.valueOf(it.key) },
            storedItems = storedItems,
        )

    companion object {
        fun from(record: HouseRoomRecord): RoomColumnDto =
            RoomColumnDto(
                templateId = record.templateId,
                customTitle = record.customTitle,
                customDescription = record.customDescription,
                exits = record.exits.mapKeys { it.key.name },
                storedItems = record.storedItems,
            )
    }
}
