package dev.ambon.engine.status

class StatusEffectRegistry {
    private val definitions = mutableMapOf<StatusEffectId, StatusEffectDefinition>()

    fun register(definition: StatusEffectDefinition) {
        definitions[definition.id] = definition
    }

    fun get(id: StatusEffectId): StatusEffectDefinition? = definitions[id]

    fun contains(id: StatusEffectId): Boolean = definitions.containsKey(id)

    fun all(): Collection<StatusEffectDefinition> = definitions.values
}
