package dev.ambon.engine

import dev.ambon.domain.StatDefinition

/** Ordered registry of all defined stats. Order matches definition order in config. */
class StatRegistry {
    private val byId = linkedMapOf<String, StatDefinition>()

    fun register(def: StatDefinition) {
        byId[def.id.uppercase()] = def
    }

    fun get(id: String): StatDefinition? = byId[id.uppercase()]

    fun all(): List<StatDefinition> = byId.values.toList()

    fun ids(): List<String> = byId.keys.toList()

    fun baseStat(id: String): Int = byId[id.uppercase()]?.baseStat ?: 0

    fun contains(id: String): Boolean = byId.containsKey(id.uppercase())
}
