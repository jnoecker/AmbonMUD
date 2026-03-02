package dev.ambon.engine.status

import dev.ambon.engine.DefinitionRegistry

class StatusEffectRegistry : DefinitionRegistry<StatusEffectId, StatusEffectDefinition>({ it.id }) {
    fun contains(id: StatusEffectId): Boolean = get(id) != null
}
