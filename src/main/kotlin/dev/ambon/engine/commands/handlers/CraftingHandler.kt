package dev.ambon.engine.commands.handlers

import dev.ambon.domain.crafting.CraftingSkill
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.crafting.CraftError
import dev.ambon.engine.crafting.CraftingSystem
import dev.ambon.engine.crafting.Either
import dev.ambon.engine.crafting.GatherError
import dev.ambon.engine.events.OutboundEvent

class CraftingHandler(
    ctx: EngineContext,
    private val craftingSystem: CraftingSystem? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
) : CommandHandler {
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val world = ctx.world
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Gather> { sid, cmd -> handleGather(sid, cmd) }
        router.on<Command.Craft> { sid, cmd -> handleCraft(sid, cmd) }
        router.on<Command.Recipes> { sid, cmd -> handleRecipes(sid, cmd) }
        router.on<Command.CraftSkills> { sid, _ -> handleCraftSkills(sid) }
    }

    private suspend fun handleGather(sessionId: SessionId, cmd: Command.Gather) {
        if (craftingSystem == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "Crafting is not available."))
            return
        }
        players.withPlayer(sessionId) { me ->
            val result = craftingSystem.gather(me, cmd.keyword, me.roomId, items)
            when (result) {
                is Either.Left -> when (val err = result.value) {
                    is GatherError.NoNodeFound -> outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "There is nothing to gather here matching '${cmd.keyword}'.",
                        ),
                    )
                    is GatherError.SkillTooLow -> outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "Your skill is too low (need ${err.required}, have ${err.current}).",
                        ),
                    )
                    is GatherError.NodeDepleted -> outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "That resource is depleted. It will respawn in ${err.respawnInSeconds}s.",
                        ),
                    )
                    is GatherError.OnCooldown ->
                        outbound.send(OutboundEvent.SendText(sessionId, "You must wait before gathering again."))
                }
                is Either.Right -> {
                    val r = result.value
                    val itemNames = r.itemsGathered.entries.joinToString(", ") { (id, qty) ->
                        val template = items.getTemplate(id)
                        val name = template?.displayName ?: id.value
                        if (qty > 1) "$name x$qty" else name
                    }
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "You gather from ${r.node.displayName}: $itemNames",
                        ),
                    )
                    outbound.send(
                        OutboundEvent.SendInfo(sessionId, "[${r.node.skill.name} +${r.xpAwarded} XP]"),
                    )
                    if (r.leveledUp) {
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "** Your ${r.node.skill.name} skill has increased to ${r.newLevel}! **",
                            ),
                        )
                    }
                    markVitalsDirty(sessionId)
                    syncItemsGmcp(sessionId, items, gmcpEmitter)
                }
            }
        }
    }

    private suspend fun handleCraft(sessionId: SessionId, cmd: Command.Craft) {
        if (craftingSystem == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "Crafting is not available."))
            return
        }
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId]
            val result = craftingSystem.craft(me, cmd.recipeKeyword, me.roomId, items, room?.station)
            when (result) {
                is Either.Left -> when (val err = result.value) {
                    is CraftError.RecipeNotFound -> outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "Unknown recipe '${cmd.recipeKeyword}'. Type 'recipes' to see available recipes.",
                        ),
                    )
                    is CraftError.SkillTooLow -> outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "Your skill is too low (need ${err.required}, have ${err.current}).",
                        ),
                    )
                    is CraftError.LevelTooLow -> outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "You need to be level ${err.required} to craft this (you are level ${err.current}).",
                        ),
                    )
                    is CraftError.MissingMaterials -> {
                        outbound.send(OutboundEvent.SendText(sessionId, "You are missing materials:"))
                        for ((itemId, qty) in err.missing) {
                            val template = items.getTemplate(itemId)
                            val name = template?.displayName ?: itemId.value
                            outbound.send(OutboundEvent.SendText(sessionId, "  - $name x$qty"))
                        }
                    }
                }
                is Either.Right -> {
                    val r = result.value
                    val outputName = items.getTemplate(r.recipe.outputItemId)?.displayName ?: r.recipe.outputItemId.value
                    val qty = if (r.quantityProduced > 1) " x${r.quantityProduced}" else ""
                    outbound.send(OutboundEvent.SendText(sessionId, "You craft $outputName$qty."))
                    if (r.stationBonusApplied) {
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "(Station bonus: +${r.quantityProduced - r.recipe.outputQuantity} extra)",
                            ),
                        )
                    }
                    outbound.send(
                        OutboundEvent.SendInfo(sessionId, "[${r.recipe.skill.name} +${r.xpAwarded} XP]"),
                    )
                    if (r.leveledUp) {
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "** Your ${r.recipe.skill.name} skill has increased to ${r.newLevel}! **",
                            ),
                        )
                    }
                    markVitalsDirty(sessionId)
                    syncItemsGmcp(sessionId, items, gmcpEmitter)
                }
            }
        }
    }

    private suspend fun handleRecipes(sessionId: SessionId, cmd: Command.Recipes) {
        if (craftingSystem == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "Crafting is not available."))
            return
        }
        players.withPlayer(sessionId) { me ->
            val allRecipes = if (cmd.filter != null) {
                val filterLower = cmd.filter.lowercase()
                val bySkill = CraftingSkill.entries.firstOrNull { it.name.lowercase() == filterLower }
                if (bySkill != null) {
                    craftingSystem.recipesForSkill(bySkill)
                } else {
                    craftingSystem.allRecipes().filter {
                        it.displayName.lowercase().contains(filterLower) ||
                            it.id.substringAfter(':').lowercase().contains(filterLower)
                    }
                }
            } else {
                craftingSystem.allRecipes()
            }

            if (allRecipes.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "No recipes found."))
                return
            }

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Crafting Recipes ]"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  %-25s %-12s %5s %5s".format("Recipe", "Skill", "Req", "Lvl")))
            for (recipe in allRecipes.sortedWith(compareBy({ it.skill }, { it.skillRequired }))) {
                val skillState = craftingSystem.getSkillState(me, recipe.skill)
                val meetsSkill = skillState.level >= recipe.skillRequired
                val meetsLevel = me.level >= recipe.levelRequired
                val marker = if (meetsSkill && meetsLevel) " " else "*"
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        " $marker%-25s %-12s %5d %5d".format(
                            recipe.displayName,
                            recipe.skill.name,
                            recipe.skillRequired,
                            recipe.levelRequired,
                        ),
                    ),
                )
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "  (* = requirements not met)"))
        }
    }

    private suspend fun handleCraftSkills(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Crafting Professions ]"))
            val maxLevel = craftingSystem?.maxSkillLevel() ?: 100
            for (skill in CraftingSkill.entries) {
                val state = me.craftingSkills.getOrDefault(skill, CraftingSkillState())
                val xpNeeded = craftingSystem?.xpForLevel(state.level) ?: 0L
                val bar = if (state.level >= maxLevel) {
                    "MAX"
                } else {
                    "${state.xp}/$xpNeeded XP"
                }
                val label = if (skill.isGathering) "(Gathering)" else "(Crafting)"
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  %-12s %3d/%d  %s  %s".format(skill.name, state.level, maxLevel, bar, label),
                    ),
                )
            }
        }
    }
}
