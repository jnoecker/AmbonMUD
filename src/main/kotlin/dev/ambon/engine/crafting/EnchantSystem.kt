package dev.ambon.engine.crafting

import dev.ambon.config.EnchantingConfig
import dev.ambon.config.EnchantmentDefinition
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.items.ItemRegistry
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class EnchantSystem(
    private val config: EnchantingConfig,
    private val items: ItemRegistry,
    private val craftingSystem: CraftingSystem,
) {
    fun allDefinitions(): Map<String, EnchantmentDefinition> = config.definitions

    fun getDefinition(id: String): EnchantmentDefinition? = config.definitions[id]

    /**
     * Attempts to enchant an item in the player's inventory.
     *
     * If [enchantmentId] is null, selects the best available enchantment for the item
     * based on player skill level and available materials.
     */
    fun enchant(
        sessionId: SessionId,
        itemKeyword: String,
        enchantmentId: String?,
        playerSkills: MutableMap<String, dev.ambon.domain.crafting.CraftingSkillState>,
    ): EnchantResult {
        // Find the item in inventory
        val inv = items.inventory(sessionId)
        val targetItem = inv.firstOrNull { matchesKeyword(it, itemKeyword) }
            ?: return EnchantResult.ItemNotFound

        // Must be an equippable item
        val slot = targetItem.item.slot
            ?: return EnchantResult.NotEquippable

        // Check enchantment limit
        if (targetItem.enchantments.size >= config.maxEnchantmentsPerItem) {
            return EnchantResult.AlreadyEnchanted
        }

        // Resolve which enchantment to apply
        val (defId, definition) = if (enchantmentId != null) {
            val def = config.definitions[enchantmentId]
                ?: return EnchantResult.EnchantmentNotFound
            enchantmentId to def
        } else {
            // Auto-select: find best available enchantment for this slot
            selectBestEnchantment(sessionId, slot, playerSkills)
                ?: return EnchantResult.NoEnchantmentAvailable
        }

        // Already has this specific enchantment?
        if (defId in targetItem.enchantments) {
            return EnchantResult.AlreadyHasEnchantment(definition.displayName)
        }

        // Check slot restriction
        if (definition.targetSlots.isNotEmpty() && slot.name !in definition.targetSlots) {
            return EnchantResult.WrongSlot(definition.displayName, definition.targetSlots)
        }

        // Check skill level
        val skillState = playerSkills[definition.skill]
        val skillLevel = skillState?.level ?: 0
        if (skillLevel < definition.skillRequired) {
            return EnchantResult.SkillTooLow(definition.skill, definition.skillRequired, skillLevel)
        }

        // Check materials
        val missingMaterials = mutableListOf<String>()
        for (mat in definition.materials) {
            val matItemId = ItemId(mat.itemId)
            val count = inv.count { it.item.keyword == mat.itemId || it.id == matItemId }
            if (count < mat.quantity) {
                val templateName = items.getTemplate(matItemId)?.displayName ?: mat.itemId
                missingMaterials.add("${mat.quantity}x $templateName")
            }
        }
        if (missingMaterials.isNotEmpty()) {
            return EnchantResult.MissingMaterials(missingMaterials)
        }

        // Consume materials
        for (mat in definition.materials) {
            val matItemId = ItemId(mat.itemId)
            repeat(mat.quantity) {
                items.removeFromInventoryById(sessionId, matItemId)
            }
        }

        // Build the enchanted item
        val bonusStats = StatMap(definition.statBonuses.mapKeys { it.key.uppercase() })
        val newStats = targetItem.item.stats + bonusStats
        val newDamage = targetItem.item.damage + definition.damageBonus
        val newArmor = targetItem.item.armor + definition.armorBonus
        val suffix = formatEnchantmentSuffix(definition)
        val newDisplayName = "${targetItem.item.displayName} $suffix"

        val enchantedItem = targetItem.copy(
            item = targetItem.item.copy(
                displayName = newDisplayName,
                stats = newStats,
                damage = newDamage,
                armor = newArmor,
            ),
            enchantments = targetItem.enchantments + defId,
        )

        // Replace in inventory
        items.replaceInInventory(sessionId, enchantedItem)

        // Award XP
        val xpResult = craftingSystem.addEnchantingXp(
            playerSkills,
            definition.skill,
            definition.xpReward,
        )

        log.debug { "Enchanted ${targetItem.item.displayName} with $defId for $sessionId" }

        return EnchantResult.Success(
            enchantedItem = enchantedItem,
            enchantmentName = definition.displayName,
            xpAwarded = definition.xpReward,
            leveledUp = xpResult.leveledUp,
            newLevel = xpResult.newLevel,
            skill = definition.skill,
        )
    }

    private fun selectBestEnchantment(
        sessionId: SessionId,
        slot: ItemSlot,
        playerSkills: MutableMap<String, dev.ambon.domain.crafting.CraftingSkillState>,
    ): Pair<String, EnchantmentDefinition>? {
        val inv = items.inventory(sessionId)
        return config.definitions
            .filter { (_, def) ->
                // Slot matches
                (def.targetSlots.isEmpty() || slot.name in def.targetSlots) &&
                    // Skill sufficient
                    (playerSkills[def.skill]?.level ?: 0) >= def.skillRequired &&
                    // Materials available
                    def.materials.all { mat ->
                        val matItemId = ItemId(mat.itemId)
                        inv.count { it.item.keyword == mat.itemId || it.id == matItemId } >= mat.quantity
                    }
            }
            .maxByOrNull { (_, def) -> def.skillRequired }
            ?.toPair()
    }

    private fun matchesKeyword(item: ItemInstance, keyword: String): Boolean {
        if (item.item.keyword.equals(keyword, ignoreCase = true)) return true
        if (keyword.length >= 3 && item.item.displayName.contains(keyword, ignoreCase = true)) return true
        return false
    }

    companion object {
        fun formatEnchantmentSuffix(definition: EnchantmentDefinition): String {
            val parts = mutableListOf<String>()
            if (definition.damageBonus > 0) parts.add("+${definition.damageBonus} dmg")
            if (definition.armorBonus > 0) parts.add("+${definition.armorBonus} armor")
            for ((stat, value) in definition.statBonuses) {
                if (value > 0) {
                    parts.add("+$value ${stat.uppercase()}")
                } else if (value < 0) {
                    parts.add("$value ${stat.uppercase()}")
                }
            }
            return if (parts.isEmpty()) "(enchanted)" else "(${parts.joinToString(", ")})"
        }
    }
}

sealed interface EnchantResult {
    data class Success(
        val enchantedItem: ItemInstance,
        val enchantmentName: String,
        val xpAwarded: Int,
        val leveledUp: Boolean,
        val newLevel: Int,
        val skill: String,
    ) : EnchantResult

    data object ItemNotFound : EnchantResult

    data object NotEquippable : EnchantResult

    data object AlreadyEnchanted : EnchantResult

    data object EnchantmentNotFound : EnchantResult

    data object NoEnchantmentAvailable : EnchantResult

    data class AlreadyHasEnchantment(
        val name: String,
    ) : EnchantResult

    data class WrongSlot(
        val enchantName: String,
        val validSlots: List<String>,
    ) : EnchantResult

    data class SkillTooLow(
        val skill: String,
        val required: Int,
        val current: Int,
    ) : EnchantResult

    data class MissingMaterials(
        val missing: List<String>,
    ) : EnchantResult
}

data class EnchantXpResult(
    val leveledUp: Boolean,
    val newLevel: Int,
)
