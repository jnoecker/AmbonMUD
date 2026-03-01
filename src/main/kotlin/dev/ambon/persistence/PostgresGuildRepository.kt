package dev.ambon.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ambon.domain.guild.GuildRank
import dev.ambon.domain.guild.GuildRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.upsert

private val log = KotlinLogging.logger {}
private val membersType = object : TypeReference<Map<Long, String>>() {}

class PostgresGuildRepository(
    private val database: Database,
    private val mapper: ObjectMapper,
) : GuildRepository {
    override suspend fun findById(id: String): GuildRecord? =
        newSuspendedTransaction(Dispatchers.IO, database) {
            GuildsTable
                .selectAll()
                .where { GuildsTable.id eq id }
                .firstOrNull()
                ?.toGuildRecord()
        }

    override suspend fun findByName(name: String): GuildRecord? =
        newSuspendedTransaction(Dispatchers.IO, database) {
            GuildsTable
                .selectAll()
                .where { GuildsTable.nameLower eq name.trim().lowercase() }
                .firstOrNull()
                ?.toGuildRecord()
        }

    override suspend fun create(record: GuildRecord): GuildRecord {
        newSuspendedTransaction(Dispatchers.IO, database) { upsertRow(record) }
        return record
    }

    override suspend fun save(record: GuildRecord) {
        newSuspendedTransaction(Dispatchers.IO, database) { upsertRow(record) }
    }

    override suspend fun delete(id: String) {
        newSuspendedTransaction(Dispatchers.IO, database) {
            GuildsTable.deleteWhere { GuildsTable.id eq id }
        }
    }

    override suspend fun findAll(): List<GuildRecord> =
        newSuspendedTransaction(Dispatchers.IO, database) {
            GuildsTable.selectAll().map { it.toGuildRecord() }
        }

    // -------- internals --------

    private fun upsertRow(record: GuildRecord) {
        GuildsTable.upsert(GuildsTable.id) {
            it[id] = record.id
            it[name] = record.name
            it[nameLower] = record.name.lowercase()
            it[tag] = record.tag
            it[tagLower] = record.tag.lowercase()
            it[leaderId] = record.leaderId.value
            it[motd] = record.motd
            it[members] = mapper.writeValueAsString(
                record.members.mapKeys { e -> e.key.value }.mapValues { e -> e.value.name },
            )
            it[createdAtEpochMs] = record.createdAtEpochMs
        }
    }

    private fun ResultRow.toGuildRecord(): GuildRecord {
        val rawMembers: Map<Long, String> =
            runCatching {
                mapper.readValue(this[GuildsTable.members], membersType)
            }.onFailure { ex ->
                log.warn(ex) { "Failed to deserialize members for guild ${this[GuildsTable.id]}; defaulting to empty" }
            }.getOrDefault(emptyMap())
        return GuildRecord(
            id = this[GuildsTable.id],
            name = this[GuildsTable.name],
            tag = this[GuildsTable.tag],
            leaderId = PlayerId(this[GuildsTable.leaderId]),
            motd = this[GuildsTable.motd],
            members = rawMembers.mapKeys { PlayerId(it.key) }.mapValues { GuildRank.valueOf(it.value) },
            createdAtEpochMs = this[GuildsTable.createdAtEpochMs],
        )
    }
}
