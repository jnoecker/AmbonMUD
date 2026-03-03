package dev.ambon.engine.crafting

import dev.ambon.config.CraftingConfig
import dev.ambon.domain.crafting.CraftingSkill
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.crafting.CraftingStationType
import dev.ambon.domain.crafting.GatheringNodeDef
import dev.ambon.domain.crafting.RecipeDef
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.engine.PlayerState
import dev.ambon.engine.items.ItemRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import kotlin.math.pow
import kotlin.random.Random

private val log = KotlinLogging.logger {}

data class GatherResult(
    val node: GatheringNodeDef,
    val itemsGathered: Map<ItemId, Int>,
    val xpAwarded: Int,
    val leveledUp: Boolean,
    val newLevel: Int,
)

data class CraftResult(
    val recipe: RecipeDef,
    val quantityProduced: Int,
    val xpAwarded: Int,
    val leveledUp: Boolean,
    val newLevel: Int,
    val stationBonusApplied: Boolean,
)

sealed interface GatherError {
    data object NoNodeFound : GatherError

    data class SkillTooLow(
        val required: Int,
        val current: Int,
    ) : GatherError

    data class NodeDepleted(
        val respawnInSeconds: Long,
    ) : GatherError

    data class OnCooldown(
        val remainingMs: Long,
    ) : GatherError
}

sealed interface CraftError {
    data object RecipeNotFound : CraftError

    data class SkillTooLow(
        val required: Int,
        val current: Int,
    ) : CraftError

    data class LevelTooLow(
        val required: Int,
        val current: Int,
    ) : CraftError

    data class MissingMaterials(
        val missing: List<Pair<ItemId, Int>>,
    ) : CraftError
}

class CraftingSystem(
    private val gatheringRegistry: GatheringRegistry,
    private val craftingRegistry: CraftingRegistry,
    private val config: CraftingConfig,
    private val clock: Clock,
    private val random: Random = Random.Default,
) {
    /** Tracks when each node becomes available again (epoch-ms). */
    private val nodeDepletedUntil = mutableMapOf<String, Long>()

    fun gather(
        player: PlayerState,
        keyword: String,
        roomId: RoomId,
        items: ItemRegistry,
    ): Either<GatherError, GatherResult> {
        val now = clock.millis()

        // Check gather cooldown
        if (player.gatherCooldownUntilMs > now) {
            val remaining = player.gatherCooldownUntilMs - now
            return Either.Left(GatherError.OnCooldown(remaining))
        }

        // Find node
        val node = gatheringRegistry.findNodeInRoom(roomId, keyword)
            ?: return Either.Left(GatherError.NoNodeFound)

        // Check skill level
        val skillState = player.craftingSkills.getOrDefault(node.skill, CraftingSkillState())
        if (skillState.level < node.skillRequired) {
            return Either.Left(GatherError.SkillTooLow(node.skillRequired, skillState.level))
        }

        // Check node depletion
        val depletedUntil = nodeDepletedUntil[node.id]
        if (depletedUntil != null && now < depletedUntil) {
            val remaining = (depletedUntil - now) / 1000
            return Either.Left(GatherError.NodeDepleted(remaining))
        }

        // Gather yields
        val gathered = mutableMapOf<ItemId, Int>()
        for (yield in node.yields) {
            val qty = if (yield.minQuantity == yield.maxQuantity) {
                yield.minQuantity
            } else {
                random.nextInt(yield.minQuantity, yield.maxQuantity + 1)
            }
            for (i in 0 until qty) {
                val instance = items.createFromTemplate(yield.itemId)
                if (instance != null) {
                    items.addToInventory(player.sessionId, instance)
                    gathered[yield.itemId] = (gathered[yield.itemId] ?: 0) + 1
                }
            }
        }

        // Mark node as depleted
        nodeDepletedUntil[node.id] = now + (node.respawnSeconds * 1000L)

        // Set gather cooldown
        player.gatherCooldownUntilMs = now + config.gatherCooldownMs

        // Award XP and check level up
        val leveledUp = addSkillXp(player, node.skill, node.xpReward.toLong())
        val currentState = player.craftingSkills.getOrDefault(node.skill, CraftingSkillState())

        return Either.Right(
            GatherResult(
                node = node,
                itemsGathered = gathered,
                xpAwarded = node.xpReward,
                leveledUp = leveledUp,
                newLevel = currentState.level,
            ),
        )
    }

    fun craft(
        player: PlayerState,
        recipeKeyword: String,
        roomId: RoomId,
        items: ItemRegistry,
        roomStation: CraftingStationType?,
    ): Either<CraftError, CraftResult> {
        // Find recipe
        val recipe = craftingRegistry.findRecipe(recipeKeyword)
            ?: return Either.Left(CraftError.RecipeNotFound)

        // Check player level
        if (player.level < recipe.levelRequired) {
            return Either.Left(CraftError.LevelTooLow(recipe.levelRequired, player.level))
        }

        // Check skill level
        val skillState = player.craftingSkills.getOrDefault(recipe.skill, CraftingSkillState())
        if (skillState.level < recipe.skillRequired) {
            return Either.Left(CraftError.SkillTooLow(recipe.skillRequired, skillState.level))
        }

        // Check materials
        val inv = items.inventory(player.sessionId)
        val missing = mutableListOf<Pair<ItemId, Int>>()
        for (mat in recipe.materials) {
            val count = inv.count { it.id == mat.itemId }
            if (count < mat.quantity) {
                missing.add(mat.itemId to (mat.quantity - count))
            }
        }
        if (missing.isNotEmpty()) {
            return Either.Left(CraftError.MissingMaterials(missing))
        }

        // Consume materials
        for (mat in recipe.materials) {
            repeat(mat.quantity) {
                items.removeFromInventoryById(player.sessionId, mat.itemId)
            }
        }

        // Calculate output quantity with station bonus
        val stationBonusApplied = recipe.stationType != null && roomStation == recipe.stationType
        val bonusQty = if (stationBonusApplied) {
            if (recipe.stationBonus > 0) recipe.stationBonus else config.stationBonusQuantity
        } else {
            0
        }
        val totalQuantity = recipe.outputQuantity + bonusQty

        // Create output items
        repeat(totalQuantity) {
            val instance = items.createFromTemplate(recipe.outputItemId)
            if (instance != null) {
                items.addToInventory(player.sessionId, instance)
            }
        }

        // Award XP and check level up
        val leveledUp = addSkillXp(player, recipe.skill, recipe.xpReward.toLong())
        val currentState = player.craftingSkills.getOrDefault(recipe.skill, CraftingSkillState())

        return Either.Right(
            CraftResult(
                recipe = recipe,
                quantityProduced = totalQuantity,
                xpAwarded = recipe.xpReward,
                leveledUp = leveledUp,
                newLevel = currentState.level,
                stationBonusApplied = stationBonusApplied,
            ),
        )
    }

    fun tickNodeRespawns() {
        val now = clock.millis()
        nodeDepletedUntil.entries.removeIf { (_, until) -> now >= until }
    }

    fun isNodeDepleted(nodeId: String): Boolean {
        val until = nodeDepletedUntil[nodeId] ?: return false
        return clock.millis() < until
    }

    fun xpForLevel(level: Int): Long =
        (config.baseXpPerLevel * level.toDouble().pow(config.xpExponent)).toLong()

    /** Adds XP to a skill. Returns true if the player leveled up. */
    private fun addSkillXp(player: PlayerState, skill: CraftingSkill, xp: Long): Boolean {
        val state = player.craftingSkills.getOrPut(skill) { CraftingSkillState() }
        if (state.level >= config.maxSkillLevel) return false

        val newXp = state.xp + xp
        val xpNeeded = xpForLevel(state.level)
        return if (newXp >= xpNeeded) {
            val newLevel = (state.level + 1).coerceAtMost(config.maxSkillLevel)
            player.craftingSkills[skill] = CraftingSkillState(level = newLevel, xp = newXp - xpNeeded)
            log.debug { "Player ${player.name} leveled up $skill to $newLevel" }
            true
        } else {
            player.craftingSkills[skill] = state.copy(xp = newXp)
            false
        }
    }

    fun getSkillLevel(player: PlayerState, skill: CraftingSkill): Int =
        player.craftingSkills.getOrDefault(skill, CraftingSkillState()).level

    fun getSkillState(player: PlayerState, skill: CraftingSkill): CraftingSkillState =
        player.craftingSkills.getOrDefault(skill, CraftingSkillState())

    fun maxSkillLevel(): Int = config.maxSkillLevel

    fun allRecipes(): Collection<RecipeDef> = craftingRegistry.allRecipes()

    fun recipesForSkill(skill: CraftingSkill): List<RecipeDef> = craftingRegistry.recipesForSkill(skill)

    fun nodesInRoom(roomId: RoomId): List<GatheringNodeDef> = gatheringRegistry.nodesInRoom(roomId)

    fun clear() {
        nodeDepletedUntil.clear()
    }
}

sealed class Either<out L, out R> {
    data class Left<L>(
        val value: L,
    ) : Either<L, Nothing>()

    data class Right<R>(
        val value: R,
    ) : Either<Nothing, R>()
}
