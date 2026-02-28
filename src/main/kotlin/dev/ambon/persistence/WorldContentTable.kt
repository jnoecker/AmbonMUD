package dev.ambon.persistence

import org.jetbrains.exposed.sql.Table

object WorldContentTable : Table("world_content") {
    val sourceName = varchar("source_name", 255)
    val zone = varchar("zone", 128)
    val content = text("content")
    val loadOrder = integer("load_order")
    val importedAtEpochMs = long("imported_at_epoch_ms")

    override val primaryKey = PrimaryKey(sourceName)
}
