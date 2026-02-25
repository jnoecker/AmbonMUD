package dev.ambon.persistence

import org.jetbrains.exposed.sql.Table

object WorldStateTable : Table("world_state") {
    val id = varchar("id", 64).default("singleton")
    val doorStates = text("door_states").default("{}")
    val containerStates = text("container_states").default("{}")
    val leverStates = text("lever_states").default("{}")
    val containerItems = text("container_items").default("{}")

    override val primaryKey = PrimaryKey(id)
}
