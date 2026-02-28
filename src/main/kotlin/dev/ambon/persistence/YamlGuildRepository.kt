package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.guild.GuildRank
import dev.ambon.domain.guild.GuildRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class YamlGuildRepository(
    private val rootDir: Path,
) : GuildRepository {
    private val mapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())
    private val guildsDir: Path = rootDir.resolve("guilds")

    init {
        guildsDir.createDirectories()
    }

    override suspend fun findById(id: String): GuildRecord? =
        withContext(Dispatchers.IO) {
            val path = pathFor(id)
            readDto(path)?.toDomain()
        }

    override suspend fun findByName(name: String): GuildRecord? =
        withContext(Dispatchers.IO) {
            val target = name.trim().lowercase()
            if (target.isEmpty()) return@withContext null
            guildsDir
                .listDirectoryEntries("*.yaml")
                .mapNotNull { p -> runCatching { readDto(p) }.getOrNull() }
                .find { it.nameLower == target }
                ?.toDomain()
        }

    override suspend fun create(record: GuildRecord): GuildRecord =
        withContext(Dispatchers.IO) {
            write(record)
            record
        }

    override suspend fun save(record: GuildRecord): Unit =
        withContext(Dispatchers.IO) {
            write(record)
        }

    override suspend fun delete(id: String): Unit =
        withContext(Dispatchers.IO) {
            pathFor(id).deleteIfExists()
        }

    override suspend fun findAll(): List<GuildRecord> =
        withContext(Dispatchers.IO) {
            if (!guildsDir.exists()) return@withContext emptyList()
            guildsDir
                .listDirectoryEntries("*.yaml")
                .mapNotNull { p -> runCatching { readDto(p) }.getOrNull() }
                .map { it.toDomain() }
        }

    // -------- internals --------

    private fun pathFor(id: String): Path = guildsDir.resolve("${sanitizeId(id)}.yaml")

    private fun sanitizeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")

    private fun readDto(path: Path): GuildDto? {
        if (!path.exists()) return null
        return try {
            mapper.readValue<GuildDto>(path.readText())
        } catch (e: Exception) {
            throw PlayerPersistenceException("Failed to read guild file: $path", e)
        }
    }

    private fun write(record: GuildRecord) {
        val path = pathFor(record.id)
        atomicWriteText(path, mapper.writeValueAsString(GuildDto.from(record)))
    }

    private fun atomicWriteText(
        path: Path,
        contents: String,
    ) {
        try {
            path.parent?.createDirectories()
            val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
            Files.writeString(tmp, contents, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw PlayerPersistenceException("Failed to write guild file atomically: $path", e)
        }
    }
}

// -------- DTO --------

internal data class GuildDto(
    val id: String,
    val name: String,
    val nameLower: String,
    val tag: String,
    val leaderId: Long,
    val motd: String? = null,
    val members: Map<Long, String> = emptyMap(),
    val createdAtEpochMs: Long,
) {
    fun toDomain(): GuildRecord =
        GuildRecord(
            id = id,
            name = name,
            tag = tag,
            leaderId = PlayerId(leaderId),
            motd = motd,
            members = members.mapKeys { PlayerId(it.key) }.mapValues { GuildRank.valueOf(it.value) },
            createdAtEpochMs = createdAtEpochMs,
        )

    companion object {
        fun from(record: GuildRecord): GuildDto =
            GuildDto(
                id = record.id,
                name = record.name,
                nameLower = record.name.lowercase(),
                tag = record.tag,
                leaderId = record.leaderId.value,
                motd = record.motd,
                members = record.members.mapKeys { it.key.value }.mapValues { it.value.name },
                createdAtEpochMs = record.createdAtEpochMs,
            )
    }
}
