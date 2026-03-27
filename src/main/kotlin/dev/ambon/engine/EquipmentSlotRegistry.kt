package dev.ambon.engine

import dev.ambon.config.EquipmentConfig
import dev.ambon.domain.items.ItemSlot

data class EquipmentSlotDefinition(
    val slot: ItemSlot,
    val displayName: String,
    val order: Int,
    val x: Double = 50.0,
    val y: Double = 50.0,
)

class EquipmentSlotRegistry(
    config: EquipmentConfig,
) {
    private var slotMap: Map<String, EquipmentSlotDefinition>
    private var orderedSlots: List<EquipmentSlotDefinition>

    init {
        val (map, ordered) = buildSlots(config)
        slotMap = map
        orderedSlots = ordered
    }

    fun reload(config: EquipmentConfig) {
        val (map, ordered) = buildSlots(config)
        slotMap = map
        orderedSlots = ordered
    }

    private companion object {
        fun buildSlots(config: EquipmentConfig): Pair<Map<String, EquipmentSlotDefinition>, List<EquipmentSlotDefinition>> {
            val map = config.slots.map { (id, cfg) ->
                val key = id.trim().lowercase()
                key to EquipmentSlotDefinition(
                    slot = ItemSlot(key),
                    displayName = cfg.displayName.ifEmpty { key.replaceFirstChar { it.uppercase() } },
                    order = cfg.order,
                    x = cfg.x,
                    y = cfg.y,
                )
            }.toMap()
            return map to map.values.sortedBy { it.order }
        }
    }

    fun isValid(slot: ItemSlot): Boolean = slotMap.containsKey(slot.name)

    fun isValid(raw: String): Boolean = slotMap.containsKey(raw.trim().lowercase())

    fun all(): List<EquipmentSlotDefinition> = orderedSlots

    fun allSlots(): List<ItemSlot> = orderedSlots.map { it.slot }

    fun get(slot: ItemSlot): EquipmentSlotDefinition? = slotMap[slot.name]

    fun slotNames(): List<String> = orderedSlots.map { it.slot.name }
}
