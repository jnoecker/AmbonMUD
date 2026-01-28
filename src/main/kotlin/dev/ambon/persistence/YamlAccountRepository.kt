package dev.ambon.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class YamlAccountRepository(
    private val rootDir: Path,
) : AccountRepository {
    private data class AccountFile(
        val username: String,
        val usernameLower: String,
        val passwordHash: String,
        val playerId: Long,
    )

    private val mapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())

    init {
        rootDir.createDirectories()
    }

    override fun findByUsernameLower(usernameLower: String): AccountRecord? {
        val key = usernameLower.trim().lowercase()
        if (key.isEmpty()) return null
        if (!isValidKey(key)) return null
        val path = pathFor(key)
        val af = readAccountFile(path) ?: return null
        return af.toDomain()
    }

    override fun create(record: AccountRecord): AccountRecord {
        val key = record.usernameLower.trim().lowercase()
        require(key.isNotEmpty()) { "usernameLower cannot be blank" }
        require(isValidKey(key)) { "usernameLower may only use letters, digits, or _." }
        if (findByUsernameLower(key) != null) {
            throw IllegalStateException("Account already exists: '$key'")
        }

        val normalized = record.copy(usernameLower = key)
        writeAccountFile(normalized)
        return normalized
    }

    override fun all(): List<AccountRecord> {
        if (!rootDir.exists()) return emptyList()
        return rootDir
            .listDirectoryEntries("*.yaml")
            .mapNotNull { readAccountFile(it) }
            .map { it.toDomain() }
    }

    private fun AccountFile.toDomain(): AccountRecord =
        AccountRecord(
            username = username,
            usernameLower = usernameLower,
            passwordHash = passwordHash,
            playerId = PlayerId(playerId),
        )

    private fun isValidKey(key: String): Boolean {
        for (c in key) {
            val ok = c.isLetterOrDigit() || c == '_'
            if (!ok) return false
        }
        return true
    }

    private fun pathFor(usernameLower: String): Path = rootDir.resolve("$usernameLower.yaml")

    private fun readAccountFile(path: Path): AccountFile? {
        if (!path.exists()) return null
        return try {
            mapper.readValue<AccountFile>(path.readText())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read account file: $path", e)
        }
    }

    private fun writeAccountFile(record: AccountRecord) {
        val af =
            AccountFile(
                username = record.username,
                usernameLower = record.usernameLower,
                passwordHash = record.passwordHash,
                playerId = record.playerId.value,
            )
        val outPath = pathFor(record.usernameLower)
        atomicWriteText(outPath, mapper.writeValueAsString(af))
    }

    private fun atomicWriteText(
        path: Path,
        contents: String,
    ) {
        try {
            path.parent?.createDirectories()
            val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
            Files.writeString(tmp, contents, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw IllegalStateException("Failed to write account file: $path", e)
        }
    }
}
