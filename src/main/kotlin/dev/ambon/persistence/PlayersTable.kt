package dev.ambon.persistence

import org.jetbrains.exposed.sql.Table

object PlayersTable : Table("players") {
    val id = long("id").autoIncrement("player_id_seq")
    val name = varchar("name", 16)
    val nameLower = varchar("name_lower", 16)
    val roomId = varchar("room_id", 128)
    val constitution = integer("constitution").default(0)
    val level = integer("level").default(1)
    val xpTotal = long("xp_total").default(0L)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val lastSeenEpochMs = long("last_seen_epoch_ms")
    val passwordHash = varchar("password_hash", 72).default("")
    val ansiEnabled = bool("ansi_enabled").default(false)
    val isStaff = bool("is_staff").default(false)
    val mana = integer("mana").default(20)
    val maxMana = integer("max_mana").default(20)
    val playerClass = varchar("player_class", 16).default("WARRIOR")
    val playerRace = varchar("player_race", 16).default("HUMAN")

    override val primaryKey = PrimaryKey(id)
}
