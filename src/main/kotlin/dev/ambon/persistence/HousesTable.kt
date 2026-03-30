package dev.ambon.persistence

import org.jetbrains.exposed.sql.Table

object HousesTable : Table("houses") {
    val ownerId = long("owner_id")
    val ownerName = varchar("owner_name", 16)
    val ownerNameLower = varchar("owner_name_lower", 16)
    val rooms = text("rooms").default("[]")
    val createdAtEpochMs = long("created_at_epoch_ms")

    override val primaryKey = PrimaryKey(ownerId)
}
