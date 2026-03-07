package dev.ambon.engine

import dev.ambon.domain.RaceDef

class RaceRegistry {
    private val byId = linkedMapOf<String, RaceDef>()

    fun register(def: RaceDef) {
        byId[def.id.uppercase()] = def
    }

    fun get(id: String): RaceDef? = byId[id.uppercase()]

    fun all(): List<RaceDef> = byId.values.toList()
}
