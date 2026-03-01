package dev.ambon.persistence

import dev.ambon.domain.guild.GuildRecord

interface GuildRepository {
    suspend fun findById(id: String): GuildRecord?

    suspend fun findByName(name: String): GuildRecord?

    suspend fun create(record: GuildRecord): GuildRecord

    suspend fun save(record: GuildRecord)

    suspend fun delete(id: String)

    suspend fun findAll(): List<GuildRecord>
}
