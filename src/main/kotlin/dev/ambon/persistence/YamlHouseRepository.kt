package dev.ambon.persistence

import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.housing.HouseRecord
import dev.ambon.domain.housing.HouseRoomRecord
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class YamlHouseRepository(
    rootDir: Path,
) : HouseRepository {
    private val mapper = yamlMapper
    private val housesDir: Path = rootDir.resolve("houses")

    init {
        housesDir.createDirectories()
    }

    override suspend fun findByOwnerId(ownerId: PlayerId): HouseRecord? =
        withContext(Dispatchers.IO) {
            val path = pathFor(ownerId)
            readDto(path)?.toDomain()
        }

    override suspend fun findByOwnerName(name: String): HouseRecord? =
        withContext(Dispatchers.IO) {
            val target = name.trim().lowercase()
            if (target.isEmpty()) return@withContext null
            housesDir
                .listDirectoryEntries("*.yaml")
                .mapNotNull { p -> runCatching { readDto(p) }.getOrNull() }
                .find { it.ownerNameLower == target }
                ?.toDomain()
        }

    override suspend fun save(record: HouseRecord): Unit =
        withContext(Dispatchers.IO) {
            write(record)
        }

    override suspend fun delete(ownerId: PlayerId): Unit =
        withContext(Dispatchers.IO) {
            pathFor(ownerId).deleteIfExists()
        }

    // -------- internals --------

    private fun pathFor(ownerId: PlayerId): Path = housesDir.resolve("${ownerId.value}.yaml")

    private fun readDto(path: Path): HouseDto? {
        if (!path.exists()) return null
        return try {
            mapper.readValue<HouseDto>(path.readText())
        } catch (e: Exception) {
            throw PersistenceException("Failed to read house file: $path", e)
        }
    }

    private fun write(record: HouseRecord) {
        val path = pathFor(record.ownerId)
        atomicWriteText(path, mapper.writeValueAsString(HouseDto.from(record)))
    }
}

// -------- DTO --------

internal data class HouseDto(
    val ownerId: Long,
    val ownerName: String,
    val ownerNameLower: String,
    val rooms: List<HouseRoomDto>,
    val createdAtEpochMs: Long,
) {
    fun toDomain(): HouseRecord =
        HouseRecord(
            ownerId = PlayerId(ownerId),
            ownerName = ownerName,
            rooms = rooms.map { it.toDomain() },
            createdAtEpochMs = createdAtEpochMs,
        )

    companion object {
        fun from(record: HouseRecord): HouseDto =
            HouseDto(
                ownerId = record.ownerId.value,
                ownerName = record.ownerName,
                ownerNameLower = record.ownerName.lowercase(),
                rooms = record.rooms.map { HouseRoomDto.from(it) },
                createdAtEpochMs = record.createdAtEpochMs,
            )
    }
}

internal data class HouseRoomDto(
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
        fun from(record: HouseRoomRecord): HouseRoomDto =
            HouseRoomDto(
                templateId = record.templateId,
                customTitle = record.customTitle,
                customDescription = record.customDescription,
                exits = record.exits.mapKeys { it.key.name },
                storedItems = record.storedItems,
            )
    }
}
