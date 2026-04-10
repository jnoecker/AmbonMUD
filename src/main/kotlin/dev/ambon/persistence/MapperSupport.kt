package dev.ambon.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import dev.ambon.config.PersistenceBackend
import dev.ambon.config.PersistenceConfig
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.nio.file.Path
import java.nio.file.Paths

/** Shared YAML ObjectMapper for persistence layer. Lenient on unknown properties for schema evolution. */
val yamlMapper: ObjectMapper =
    ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** Shared JSON ObjectMapper for persistence layer. Lenient on unknown properties for schema evolution. */
val jsonMapper: ObjectMapper =
    ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/**
 * General persistence exception for I/O failures, uniqueness violations, and
 * other repository-level errors. Originally named `PlayerPersistenceException`;
 * renamed so guild and world-state repositories can use it too.
 */
class PersistenceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Runs [statement] in a suspended Exposed transaction on [Dispatchers.IO]. */
suspend fun <T> Database.dbQuery(statement: suspend Transaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, this, statement = statement)

/**
 * Deserialises JSON with a fallback to [default] on any parse failure.
 * Shared across all Postgres repositories.
 */
fun <T> safeReadJson(
    json: String,
    type: com.fasterxml.jackson.core.type.TypeReference<T>,
    default: T,
): T = runCatching { jsonMapper.readValue(json, type) }.getOrDefault(default)

/**
 * Creates a repository by dispatching on the persistence backend.
 * Eliminates the repeated `when (backend) { YAML -> ..., POSTGRES -> ... }` factory pattern.
 */
fun <T> createRepository(
    persistence: PersistenceConfig,
    database: Database?,
    yamlFactory: (Path) -> T,
    postgresFactory: (Database) -> T,
): T =
    when (persistence.backend) {
        PersistenceBackend.YAML ->
            yamlFactory(Paths.get(persistence.rootDir))
        PersistenceBackend.POSTGRES ->
            postgresFactory(
                requireNotNull(database) {
                    "Database must be configured when persistence.backend=POSTGRES"
                },
            )
    }
