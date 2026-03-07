package dev.ambon.engine

import dev.ambon.domain.PlayerClassDef

class PlayerClassRegistry {
    private val byId = linkedMapOf<String, PlayerClassDef>()

    fun register(def: PlayerClassDef) {
        byId[def.id.uppercase()] = def
    }

    fun get(id: String): PlayerClassDef? = byId[id.uppercase()]

    fun selectable(): List<PlayerClassDef> = byId.values.filter { it.selectable }

    fun all(): List<PlayerClassDef> = byId.values.toList()
}
