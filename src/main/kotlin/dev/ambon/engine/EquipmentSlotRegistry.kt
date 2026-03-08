package dev.ambon.engine

import dev.ambon.config.EquipmentConfig
import dev.ambon.domain.items.ItemSlot

data class EquipmentSlotDefinition(
    val slot: ItemSlot,
    val displayName: String,
    val order: Int,
)

class EquipmentSlotRegistry(
    config: EquipmentConfig,
) {
    private val slotMap: Map<String, EquipmentSlotDefinition>
    private val orderedSlots: List<EquipmentSlotDefinition>

    init {
        slotMap = config.slots.map { (id, cfg) ->
            val key = id.trim().lowercase()
            key to EquipmentSlotDefinition(
                slot = ItemSlot(key),
                displayName = cfg.displayName.ifEmpty { key.replaceFirstChar { it.uppercase() } },
                order = cfg.order,
            )
        }.toMap()
        orderedSlots = slotMap.values.sortedBy { it.order }
    }

    fun isValid(slot: ItemSlot): Boolean = slotMap.containsKey(slot.name)

    fun isValid(raw: String): Boolean = slotMap.containsKey(raw.trim().lowercase())

    fun all(): List<EquipmentSlotDefinition> = orderedSlots

    fun allSlots(): List<ItemSlot> = orderedSlots.map { it.slot }

    fun get(slot: ItemSlot): EquipmentSlotDefinition? = slotMap[slot.name]

    fun slotNames(): List<String> = orderedSlots.map { it.slot.name }
}
