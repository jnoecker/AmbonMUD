package dev.ambon.persistence

import org.jetbrains.exposed.sql.Table

object GuildsTable : Table("guilds") {
    val id = varchar("id", 64)
    val name = varchar("name", 64)
    val nameLower = varchar("name_lower", 64)
    val tag = varchar("tag", 5)
    val tagLower = varchar("tag_lower", 5)
    val leaderId = long("leader_id")
    val motd = text("motd").nullable()
    val members = text("members").default("{}")
    val createdAtEpochMs = long("created_at_epoch_ms")

    override val primaryKey = PrimaryKey(id)
}
