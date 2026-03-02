package dev.ambon.engine

open class DefinitionRegistry<Id, Def>(
    private val idExtractor: (Def) -> Id,
) {
    private val map = mutableMapOf<Id, Def>()

    fun register(def: Def) {
        map[idExtractor(def)] = def
    }

    fun get(id: Id): Def? = map[id]

    fun all(): Collection<Def> = map.values

    protected fun entries(): Map<Id, Def> = map
}
