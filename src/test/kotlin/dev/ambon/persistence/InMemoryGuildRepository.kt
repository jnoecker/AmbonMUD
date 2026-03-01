package dev.ambon.persistence

import dev.ambon.domain.guild.GuildRecord

class InMemoryGuildRepository : GuildRepository {
    private val guilds = mutableMapOf<String, GuildRecord>()

    override suspend fun findById(id: String): GuildRecord? = guilds[id]

    override suspend fun findByName(name: String): GuildRecord? =
        guilds.values.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    override suspend fun create(record: GuildRecord): GuildRecord {
        guilds[record.id] = record
        return record
    }

    override suspend fun save(record: GuildRecord) {
        guilds[record.id] = record
    }

    override suspend fun delete(id: String) {
        guilds.remove(id)
    }

    override suspend fun findAll(): List<GuildRecord> = guilds.values.toList()

    fun clear() {
        guilds.clear()
    }
}
