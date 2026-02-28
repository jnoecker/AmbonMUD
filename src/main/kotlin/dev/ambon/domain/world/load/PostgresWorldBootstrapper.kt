package dev.ambon.domain.world.load

import dev.ambon.config.MobTiersConfig
import dev.ambon.config.WorldStorageConfig
import dev.ambon.persistence.PostgresWorldContentRepository
import dev.ambon.persistence.WorldContentDocument
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Clock
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class PostgresWorldBootstrapper(
    private val repository: PostgresWorldContentRepository,
    private val storage: WorldStorageConfig,
    private val tiers: MobTiersConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun loadWorld(zoneFilter: Set<String> = emptySet()) =
        run {
            importPendingFiles()

            val documents = repository.loadAll()
            if (documents.isEmpty()) {
                throw IllegalStateException(
                    "No static world content found in Postgres and no staged world files were present in '${storage.importDirectory}'",
                )
            }

            val parsedFiles =
                documents.map { document ->
                    WorldLoader.parseWorldFile(document.content, document.sourceName)
                }
            WorldLoader.loadFromFiles(parsedFiles, tiers, zoneFilter)
        }

    fun importPendingFiles(): Int {
        val stagedFiles = stagedFiles()
        if (stagedFiles.isEmpty()) {
            return 0
        }
        importStagedFiles(stagedFiles)
        return stagedFiles.size
    }

    private fun importStagedFiles(files: List<Path>) {
        val importedAtEpochMs = clock.millis()
        val existingDocuments = repository.loadAll()
        val stagedDocuments =
            files.mapIndexed { index, path ->
                val content = Files.readString(path)
                val parsed = WorldLoader.parseWorldFile(content, path.fileName.toString())
                WorldContentDocument(
                    sourceName = path.fileName.toString(),
                    zone = parsed.zone.trim(),
                    content = content,
                    loadOrder = existingDocuments.size + index,
                    importedAtEpochMs = importedAtEpochMs,
                ) to parsed
            }

        val mergedDocuments = mergeDocuments(existingDocuments, stagedDocuments.map { it.first })
        // Validate the full merged world before replacing the DB snapshot.
        WorldLoader.loadFromFiles(
            mergedDocuments.map { document ->
                WorldLoader.parseWorldFile(document.content, document.sourceName)
            },
            tiers,
        )
        repository.replaceAll(mergedDocuments)
        archiveImportedFiles(files)
    }

    private fun mergeDocuments(
        existingDocuments: List<WorldContentDocument>,
        stagedDocuments: List<WorldContentDocument>,
    ): List<WorldContentDocument> {
        if (stagedDocuments.isEmpty()) {
            return existingDocuments
        }

        val existingBySource = existingDocuments.associateBy { it.sourceName }.toMutableMap()
        stagedDocuments.forEach { staged ->
            existingBySource[staged.sourceName] =
                existingBySource[staged.sourceName]?.copy(
                    zone = staged.zone,
                    content = staged.content,
                    importedAtEpochMs = staged.importedAtEpochMs,
                ) ?: staged
        }

        return existingDocuments
            .mapNotNull { existingBySource.remove(it.sourceName) }
            .plus(stagedDocuments.mapNotNull { staged -> existingBySource.remove(staged.sourceName) })
            .mapIndexed { index, document -> document.copy(loadOrder = index) }
    }

    private fun stagedFiles(): List<Path> {
        val importDir = Paths.get(storage.importDirectory)
        if (!Files.exists(importDir)) return emptyList()
        require(importDir.isDirectory()) {
            "World import path '${storage.importDirectory}' must be a directory"
        }

        Files.list(importDir).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) }
                .filter { path ->
                    val ext = path.extension.lowercase()
                    (ext == "yaml" || ext == "yml") && path.fileSize() > 0L
                }
                .sorted(compareBy<Path> { it.name.lowercase() }.thenBy { it.name })
                .toList()
        }
    }

    private fun archiveImportedFiles(files: List<Path>) {
        val archiveRoot = Paths.get(storage.archiveDirectory)
        val batchDir = archiveRoot.resolve("import-${clock.millis()}")
        Files.createDirectories(batchDir)

        files.forEach { path ->
            Files.move(
                path,
                batchDir.resolve(path.fileName.toString()),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
