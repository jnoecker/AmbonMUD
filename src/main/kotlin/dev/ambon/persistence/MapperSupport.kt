package dev.ambon.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

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
