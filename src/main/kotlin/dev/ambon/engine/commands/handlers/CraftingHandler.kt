package dev.ambon.engine.commands.handlers

import dev.ambon.domain.arcanum.ArcanumSource
import dev.ambon.domain.crafting.CraftingQuality
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.crafting.RecipeDef
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.crafting.CraftError
import dev.ambon.engine.crafting.CraftingSkillRegistry
import dev.ambon.engine.crafting.CraftingSystem
import dev.ambon.engine.crafting.Either
import dev.ambon.engine.crafting.GatherError
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.events.OutboundEvent

class CraftingHandler(
    private val ctx: EngineContext,
    private val craftingSystem: CraftingSystem? = null,
    private val craftingSkillRegistry: CraftingSkillRegistry? = null,
    private val gatheringRegistry: GatheringRegistry? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val onItemCrafted: (suspend (SessionId) -> Unit)? = null,
    private val onItemGathered: (suspend (SessionId, String) -> Unit)? = null,
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
        router.on<Command.Specialize> { sid, cmd -> handleSpecialize(sid, cmd) }
    }

    private suspend fun handleGather(sessionId: SessionId, cmd: Command.Gather) {
        val cs = requireSystemOrNull(sessionId, craftingSystem, "Crafting", outbound) ?: return
        players.withPlayer(sessionId) { me ->
            val result = cs.gather(me, cmd.keyword, me.roomId, items)
            when (result) {
                is Either.Left -> when (val err = result.value) {
                    is GatherError.NoNodeFound -> sendGatherError(
                        sessionId,
                        "There is nothing to gather here matching '${cmd.keyword}'.",
                        code = "no_node",
                    )
                    is GatherError.SkillTooLow -> {
                        sendSkillTooLow(sessionId, err.required, err.current)
                        gmcpEmitter?.sendUiFeedback(
                            sessionId,
                            type = "error",
                            message = "Your skill is too low (need ${err.required}, have ${err.current}).",
                            code = "skill_too_low",
                            scope = "crafting",
                        )
                    }
                    is GatherError.NodeDepleted -> sendGatherError(
                        sessionId,
                        "That resource is depleted. It will respawn in ${err.respawnInSeconds}s.",
                        code = "node_depleted",
                    )
                    is GatherError.OnCooldown -> {
                        val seconds = ((err.remainingMs + 999) / 1000).coerceAtLeast(1)
                        sendGatherError(
                            sessionId,
                            "You must wait ${seconds}s before gathering again.",
                            code = "on_cooldown",
                        )
                        gmcpEmitter?.sendCraftingCooldown(sessionId, "gather", me.gatherCooldownUntilMs)
                    }
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
                    if (r.rareItemsGathered.isNotEmpty()) {
                        val rareNames = r.rareItemsGathered.entries.joinToString(", ") { (id, qty) ->
                            val template = items.getTemplate(id)
                            val name = template?.displayName ?: id.value
                            if (qty > 1) "$name x$qty" else name
                        }
                        outbound.send(
                            OutboundEvent.SendInfo(sessionId, "** Rare find: $rareNames! **"),
                        )
                    }
                    sendCraftingXp(sessionId, r.node.skill, r.xpAwarded, r.leveledUp, r.newLevel)
                    val totalQuantity = r.itemsGathered.values.sum() + r.rareItemsGathered.values.sum()
                    gmcpEmitter?.sendCraftingResult(
                        sessionId,
                        "gather",
                        r.node.skill,
                        r.xpAwarded,
                        r.leveledUp,
                        r.newLevel,
                        itemName = itemNames,
                        quantity = totalQuantity,
                        rareFind = r.rareItemsGathered.isNotEmpty(),
                    )
                    gmcpEmitter?.sendCraftingCooldown(sessionId, "gather", me.gatherCooldownUntilMs)
                    onItemGathered?.invoke(sessionId, r.node.skill)
                    // Gathered yields count as discoveries for an Akathavae's Arcanum.
                    for (gatheredId in r.itemsGathered.keys + r.rareItemsGathered.keys) {
                        ctx.akathavaeSystem?.recordItemDiscovery(sessionId, gatheredId, ArcanumSource.GATHERED)
                    }
                    notifyNewDiscoveries(sessionId, me, cs)
                    emitCraftingSkills(sessionId, me)
                }
            }
        }
    }

    private suspend fun handleCraft(sessionId: SessionId, cmd: Command.Craft) {
        val cs = requireSystemOrNull(sessionId, craftingSystem, "Crafting", outbound) ?: return
        players.withPlayer(sessionId) { me ->
            val room = world.rooms[me.roomId]
            val result = cs.craft(me, cmd.recipeKeyword, me.roomId, items, room?.station)
            when (result) {
                is Either.Left -> when (val err = result.value) {
                    is CraftError.RecipeNotFound -> sendCraftError(
                        sessionId,
                        "Unknown recipe '${cmd.recipeKeyword}'. Type 'recipes' to see available recipes.",
                        code = "recipe_not_found",
                    )
                    is CraftError.NotDiscovered -> sendCraftError(
                        sessionId,
                        "You haven't discovered that recipe yet. Keep leveling your skills!",
                        code = "not_discovered",
                    )
                    is CraftError.SkillTooLow -> {
                        sendSkillTooLow(sessionId, err.required, err.current)
                        gmcpEmitter?.sendUiFeedback(
                            sessionId,
                            type = "error",
                            message = "Your skill is too low (need ${err.required}, have ${err.current}).",
                            code = "skill_too_low",
                            scope = "crafting",
                        )
                    }
                    is CraftError.LevelTooLow -> sendCraftError(
                        sessionId,
                        "You need to be level ${err.required} to craft this (you are level ${err.current}).",
                        code = "level_too_low",
                    )
                    is CraftError.MissingMaterials -> {
                        outbound.send(OutboundEvent.SendError(sessionId, "You are missing materials:"))
                        val missingSummary = err.missing.joinToString(", ") { (itemId, qty) ->
                            val name = items.getTemplate(itemId)?.displayName ?: itemId.value
                            "$name x$qty"
                        }
                        for ((itemId, qty) in err.missing) {
                            val template = items.getTemplate(itemId)
                            val name = template?.displayName ?: itemId.value
                            outbound.send(OutboundEvent.SendError(sessionId, "  - $name x$qty"))
                        }
                        gmcpEmitter?.sendUiFeedback(
                            sessionId,
                            type = "error",
                            message = "Missing materials: $missingSummary",
                            code = "missing_materials",
                            scope = "crafting",
                        )
                    }
                }
                is Either.Right -> {
                    val r = result.value
                    val baseName = items.getTemplate(r.recipe.outputItemId)?.displayName ?: r.recipe.outputItemId.value
                    val qualityPrefix = if (r.quality != CraftingQuality.NORMAL) "${r.quality.displayPrefix} " else ""
                    val outputName = "$qualityPrefix$baseName"
                    val qty = if (r.quantityProduced > 1) " x${r.quantityProduced}" else ""
                    outbound.send(OutboundEvent.SendText(sessionId, "You craft $outputName$qty."))
                    if (r.quality != CraftingQuality.NORMAL) {
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "** ${r.quality.displayPrefix} quality! **",
                            ),
                        )
                    }
                    if (r.stationBonusApplied) {
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "(Station bonus: +${r.quantityProduced - r.recipe.outputQuantity} extra)",
                            ),
                        )
                    }
                    sendCraftingXp(sessionId, r.recipe.skill, r.xpAwarded, r.leveledUp, r.newLevel)
                    gmcpEmitter?.sendCraftingResult(
                        sessionId,
                        "craft",
                        r.recipe.skill,
                        r.xpAwarded,
                        r.leveledUp,
                        r.newLevel,
                        itemName = outputName,
                        quantity = r.quantityProduced,
                        quality = r.quality.name.lowercase(),
                    )
                    onItemCrafted?.invoke(sessionId)
                    // A newly crafted item counts as a discovery for an Akathavae's Arcanum.
                    ctx.akathavaeSystem?.recordItemDiscovery(sessionId, r.recipe.outputItemId, ArcanumSource.CRAFTED)
                    notifyNewDiscoveries(sessionId, me, cs)
                    emitCraftingSkills(sessionId, me)
                }
            }
        }
    }

    private suspend fun handleRecipes(sessionId: SessionId, cmd: Command.Recipes) {
        val cs = requireSystemOrNull(sessionId, craftingSystem, "Crafting", outbound) ?: return
        players.withPlayer(sessionId) { me ->
            notifyNewDiscoveries(sessionId, me, cs)
            val allRecipes = if (cmd.filter != null) {
                val filterLower = cmd.filter.lowercase()
                val isSkill = craftingSkillRegistry?.isValid(filterLower) == true
                if (isSkill) {
                    cs.recipesForSkill(filterLower)
                } else {
                    cs.allRecipes().filter {
                        it.displayName.lowercase().contains(filterLower) ||
                            it.id.substringAfter(':').lowercase().contains(filterLower)
                    }
                }
            } else {
                cs.allRecipes()
            }

            if (allRecipes.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "No recipes found."))
                return
            }

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Crafting Recipes ]"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  %-25s %-12s %5s %5s".format("Recipe", "Skill", "Req", "Lvl")))
            for (recipe in allRecipes.sortedWith(compareBy({ it.skill }, { it.skillRequired }))) {
                val discovered = recipe.id in me.discoveredRecipes
                val skillState = cs.getSkillState(me, recipe.skill)
                val meetsSkill = skillState.level >= recipe.skillRequired
                val meetsLevel = me.level >= recipe.levelRequired
                val marker = when {
                    !discovered -> "?"
                    meetsSkill && meetsLevel -> " "
                    else -> "*"
                }
                val displayName = if (discovered) recipe.displayName else "???"
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        " $marker%-25s %-12s %5d %5d".format(
                            displayName,
                            craftingSkillRegistry?.get(recipe.skill)?.displayName ?: recipe.skill,
                            recipe.skillRequired,
                            recipe.levelRequired,
                        ),
                    ),
                )
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "  (* = requirements not met, ? = undiscovered)"))
            emitRecipes(sessionId, allRecipes.filter { it.id in me.discoveredRecipes })
        }
    }

    private suspend fun handleCraftSkills(sessionId: SessionId) {
        val cs = craftingSystem
        players.withPlayer(sessionId) { me ->
            if (cs != null) notifyNewDiscoveries(sessionId, me, cs)
            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Crafting Professions ]"))
            val maxLevel = craftingSystem?.maxSkillLevel() ?: 100
            val skillDefs = craftingSkillRegistry?.allDefinitions() ?: emptyList()
            for (skillDef in skillDefs) {
                val state = me.craftingSkills.getOrDefault(skillDef.id, CraftingSkillState())
                val xpNeeded = craftingSystem?.xpForLevel(state.level) ?: 0L
                val bar = if (state.level >= maxLevel) {
                    "MAX"
                } else {
                    "${state.xp}/$xpNeeded XP"
                }
                val label = if (skillDef.isGathering) "(Gathering)" else "(Crafting)"
                val specTag = if (me.craftingSpecialization == skillDef.id) " [SPEC]" else ""
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  %-12s %3d/%d  %s  %s%s".format(
                            skillDef.displayName,
                            state.level,
                            maxLevel,
                            bar,
                            label,
                            specTag,
                        ),
                    ),
                )
            }
            if (me.craftingSpecialization != null) {
                val specName = craftingSkillRegistry?.get(me.craftingSpecialization!!)?.displayName
                    ?: me.craftingSpecialization
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  Specialization: $specName (+${craftingSystem?.specializationXpBonusPct() ?: 25}% XP)",
                    ),
                )
            }
            emitCraftingSkills(sessionId, me)
        }
    }

    private suspend fun emitCraftingSkills(sessionId: SessionId, me: dev.ambon.engine.PlayerState) {
        val maxLevel = craftingSystem?.maxSkillLevel() ?: 100
        val skillDefs = craftingSkillRegistry?.allDefinitions() ?: emptyList()
        gmcpEmitter?.sendCraftingSkills(
            sessionId,
            skillDefs.map { skillDef ->
                val state = me.craftingSkills.getOrDefault(skillDef.id, CraftingSkillState())
                GmcpEmitter.CraftingSkillPayload(
                    id = skillDef.id,
                    name = skillDef.displayName,
                    level = state.level,
                    xp = state.xp,
                    xpToNext = craftingSystem?.xpForLevel(state.level) ?: 0L,
                    maxLevel = maxLevel,
                    type = if (skillDef.isGathering) "gathering" else "crafting",
                )
            },
        )
    }

    private suspend fun emitRecipes(sessionId: SessionId, recipes: Collection<RecipeDef>) {
        gmcpEmitter?.sendCraftingRecipes(
            sessionId,
            recipes.map { recipe ->
                GmcpEmitter.CraftingRecipePayload(
                    id = recipe.id,
                    name = recipe.displayName,
                    skill = craftingSkillRegistry?.get(recipe.skill)?.displayName ?: recipe.skill,
                    skillRequired = recipe.skillRequired,
                    levelRequired = recipe.levelRequired,
                    materials = recipe.materials.map { mat ->
                        GmcpEmitter.CraftingMaterialPayload(
                            name = items.getTemplate(mat.itemId)?.displayName ?: mat.itemId.value,
                            quantity = mat.quantity,
                        )
                    },
                    outputName = items.getTemplate(recipe.outputItemId)?.displayName ?: recipe.outputItemId.value,
                    outputQuantity = recipe.outputQuantity,
                )
            },
        )
    }

    private suspend fun handleSpecialize(sessionId: SessionId, cmd: Command.Specialize) {
        players.withPlayer(sessionId) { me ->
            if (cmd.skill == null) {
                // Show current specialization
                val current = me.craftingSpecialization
                if (current != null) {
                    val name = craftingSkillRegistry?.get(current)?.displayName ?: current
                    val bonusPct = craftingSystem?.specializationXpBonusPct() ?: 25
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Your specialization: $name (+$bonusPct% XP bonus)"))
                } else {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "You have no crafting specialization."))
                }
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "Usage: specialize <skill> — choose a crafting skill to specialize in."),
                )
                val skillDefs = craftingSkillRegistry?.allDefinitions() ?: emptyList()
                if (skillDefs.isNotEmpty()) {
                    val names = skillDefs.joinToString(", ") { it.displayName.lowercase() }
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Available skills: $names"))
                }
                return
            }

            val skillId = cmd.skill.lowercase()
            val skillDef = craftingSkillRegistry?.get(skillId)
            if (skillDef == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "Unknown crafting skill '${cmd.skill}'."))
                return
            }

            if (me.craftingSpecialization == skillId) {
                outbound.send(
                    OutboundEvent.SendError(sessionId, "You are already specialized in ${skillDef.displayName}."),
                )
                return
            }

            me.craftingSpecialization = skillId
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "** You are now specialized in ${skillDef.displayName}! " +
                        "(+${craftingSystem?.specializationXpBonusPct() ?: 25}% XP bonus) **",
                ),
            )
            emitCraftingSkills(sessionId, me)
        }
    }

    private suspend fun notifyNewDiscoveries(sessionId: SessionId, me: PlayerState, cs: CraftingSystem) {
        val newRecipes = cs.discoverNewRecipes(me)
        for (recipe in newRecipes) {
            outbound.send(
                OutboundEvent.SendInfo(sessionId, "** New recipe discovered: ${recipe.displayName}! **"),
            )
        }
    }

    private suspend fun sendSkillTooLow(sessionId: SessionId, required: Int, current: Int) {
        outbound.send(OutboundEvent.SendError(sessionId, "Your skill is too low (need $required, have $current)."))
    }

    private suspend fun sendGatherError(sessionId: SessionId, message: String, code: String) {
        outbound.send(OutboundEvent.SendError(sessionId, message))
        gmcpEmitter?.sendUiFeedback(sessionId, type = "error", message = message, code = code, scope = "crafting")
    }

    private suspend fun sendCraftError(sessionId: SessionId, message: String, code: String) {
        outbound.send(OutboundEvent.SendError(sessionId, message))
        gmcpEmitter?.sendUiFeedback(sessionId, type = "error", message = message, code = code, scope = "crafting")
    }

    private suspend fun sendCraftingXp(
        sessionId: SessionId,
        skill: String,
        xpAwarded: Int,
        leveledUp: Boolean,
        newLevel: Int,
    ) {
        val skillName = craftingSkillRegistry?.get(skill)?.displayName ?: skill
        outbound.send(OutboundEvent.SendInfo(sessionId, "[$skillName +$xpAwarded XP]"))
        if (leveledUp) {
            outbound.send(
                OutboundEvent.SendInfo(sessionId, "** Your $skillName skill has increased to $newLevel! **"),
            )
        }
        markVitalsDirty(sessionId)
        syncItemsGmcp(sessionId, items, gmcpEmitter)
    }
}
