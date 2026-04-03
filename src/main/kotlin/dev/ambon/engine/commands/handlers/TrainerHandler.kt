package dev.ambon.engine.commands.handlers

import dev.ambon.config.MulticlassConfig
import dev.ambon.config.SkillPointsConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.TrainerDefinition
import dev.ambon.engine.PlayerState
import dev.ambon.engine.TrainerRegistry
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class TrainerHandler(
    private val ctx: EngineContext,
    private val abilitySystem: AbilitySystem,
    private val trainerRegistry: TrainerRegistry,
    private val skillPointsConfig: SkillPointsConfig = SkillPointsConfig(),
    private val multiclassConfig: MulticlassConfig = MulticlassConfig(),
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val prestigeSkillPointBonus: (Int) -> Int = { 0 },
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Train.List> { sid, _ -> handleTrainList(sid) }
        router.on<Command.Train.Learn> { sid, cmd -> handleTrainLearn(sid, cmd.keyword) }
        router.on<Command.Train.Unlock> { sid, _ -> handleTrainUnlock(sid) }
    }

    /** Returns the trainer at [me]'s current room, or null (sending an error) if there is none. */
    private suspend fun findTrainer(sessionId: SessionId, me: PlayerState): TrainerDefinition? {
        val trainer = trainerRegistry.trainerInRoom(me.roomId)
        if (trainer == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "There is no trainer here."))
        }
        return trainer
    }

    private suspend fun handleTrainList(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val trainer = findTrainer(sessionId, me) ?: return
            val available = abilitySystem.availableSkillPoints(
                level = me.level,
                learnedCount = me.learnedAbilityIds.size,
                interval = skillPointsConfig.interval,
                prestigeBonus = prestigeSkillPointBonus(me.prestigeLevel),
            )
            val classUnlocked = me.unlockedClasses.any { it.equals(trainer.className, ignoreCase = true) }

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ ${trainer.name} — ${trainer.className} Trainer ]"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Skill points available: $available"))

            if (!classUnlocked) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  ** ${trainer.className} class locked. Use 'train unlock' to pay " +
                            "${multiclassConfig.goldCost} gold (requires level ${multiclassConfig.minLevel}). **",
                    ),
                )
                gmcpEmitter?.sendTrainerList(sessionId, trainer, emptyList(), available, classUnlocked, multiclassConfig)
                return
            }

            val trainable = abilitySystem.trainableAbilities(trainer.className, me.level, me.learnedAbilityIds)
            val learned = abilitySystem.knownAbilities(sessionId)
                .filter { it.requiredClass.equals(trainer.className, ignoreCase = true) }

            // Group by tree for display
            val trees = abilitySystem.registry.treesForClass(trainer.className)
            if (trees.isNotEmpty()) {
                for (tree in trees.sorted()) {
                    val treeName = tree.substringAfter("_").replaceFirstChar { it.uppercaseChar() }
                    outbound.send(OutboundEvent.SendInfo(sessionId, "  === $treeName ==="))
                    val treeAbilities = abilitySystem.registry.abilitiesInTree(tree)
                    for (ability in treeAbilities) {
                        val isLearned = ability.id.value in me.learnedAbilityIds
                        val prereqMet = abilitySystem.registry.isPrerequisiteMet(
                            ability.id,
                            me.learnedAbilityIds,
                        )
                        val levelMet = ability.levelRequired <= me.level
                        val mark = when {
                            isLearned -> "[*]"
                            !prereqMet || !levelMet -> "[X]"
                            else -> "[ ]"
                        }
                        val prereqSuffix = if (ability.prerequisites.isNotEmpty() && !isLearned) {
                            val prereqNames = ability.prerequisites.mapNotNull { pid ->
                                abilitySystem.registry.get(pid)?.displayName
                            }
                            val lockLabel = if (!prereqMet) " [LOCKED]" else ""
                            " - Requires: ${prereqNames.joinToString(", ")}$lockLabel"
                        } else {
                            ""
                        }
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "    $mark ${ability.displayName} (Tier ${ability.tier})$prereqSuffix",
                            ),
                        )
                    }
                }
                // Show any untree'd abilities
                val untreeAbilities = trainable.filter { it.tree.isBlank() }
                if (untreeAbilities.isNotEmpty()) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "  === General ==="))
                    for (ability in untreeAbilities) {
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "    [ ] ${ability.displayName} (Lvl ${ability.levelRequired})",
                            ),
                        )
                    }
                }
            } else if (trainable.isEmpty()) {
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "  No abilities available to learn here at your level."),
                )
            } else {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  %-30s %5s %s".format("Ability", "Lvl", "Description"),
                    ),
                )
                for (ability in trainable) {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "  %-30s %5d %s".format(ability.displayName, ability.levelRequired, ability.description),
                        ),
                    )
                }
            }
            if (trainable.isNotEmpty()) {
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "  Use 'train learn <ability>' to spend a skill point."),
                )
            }

            if (learned.isNotEmpty()) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  Already learned: ${learned.joinToString { it.displayName }}",
                    ),
                )
            }

            gmcpEmitter?.sendTrainerList(sessionId, trainer, trainable, available, classUnlocked, multiclassConfig)
        }
    }

    private suspend fun handleTrainLearn(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val trainer = findTrainer(sessionId, me) ?: return
            if (!me.unlockedClasses.any { it.equals(trainer.className, ignoreCase = true) }) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You must unlock the ${trainer.className} class first. Use 'train unlock'.",
                    ),
                )
                return
            }

            val trainable = abilitySystem.trainableAbilities(trainer.className, me.level, me.learnedAbilityIds)
            val target = trainable.firstOrNull {
                it.id.value.equals(keyword, ignoreCase = true) ||
                    it.displayName.equals(keyword, ignoreCase = true) ||
                    it.id.value.startsWith(keyword.lowercase()) ||
                    it.displayName.lowercase().contains(keyword.lowercase())
            }
            if (target == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No trainable ability matching '$keyword' found here."))
                return
            }

            val error = abilitySystem.learnAbility(
                sessionId = sessionId,
                abilityId = AbilityId(target.id.value),
                level = me.level,
                unlockedClasses = me.unlockedClasses,
                skillPointInterval = skillPointsConfig.interval,
                learnedIds = me.learnedAbilityIds,
                prestigeBonus = prestigeSkillPointBonus(me.prestigeLevel),
            )
            if (error != null) {
                outbound.send(OutboundEvent.SendError(sessionId, error))
                return
            }

            // Persist the new learned set to player state
            me.learnedAbilityIds.add(target.id.value)
            players.persistPlayer(sessionId)

            val remaining = abilitySystem.availableSkillPoints(
                level = me.level,
                learnedCount = me.learnedAbilityIds.size,
                interval = skillPointsConfig.interval,
                prestigeBonus = prestigeSkillPointBonus(me.prestigeLevel),
            )
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You learn ${target.displayName}! ($remaining skill points remaining)",
                ),
            )

            gmcpEmitter?.sendCharSkills(sessionId, abilitySystem.knownAbilities(sessionId)) { id ->
                abilitySystem.cooldownRemainingMs(sessionId, id)
            }
            gmcpEmitter?.sendTrainerList(
                sessionId,
                trainer,
                abilitySystem.trainableAbilities(trainer.className, me.level, me.learnedAbilityIds),
                remaining,
                true,
                multiclassConfig,
            )
        }
    }

    private suspend fun handleTrainUnlock(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val trainer = findTrainer(sessionId, me) ?: return
            if (me.unlockedClasses.any { it.equals(trainer.className, ignoreCase = true) }) {
                outbound.send(
                    OutboundEvent.SendError(sessionId, "You have already unlocked the ${trainer.className} class."),
                )
                return
            }
            if (me.level < multiclassConfig.minLevel) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You must be at least level ${multiclassConfig.minLevel} to unlock a new class.",
                    ),
                )
                return
            }
            val cost = multiclassConfig.goldCost
            if (!me.isStaff && me.gold < cost) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You need $cost gold to unlock the ${trainer.className} class.",
                    ),
                )
                return
            }
            if (!me.isStaff) me.gold -= cost
            markVitalsDirty(sessionId)
            me.unlockedClasses.add(trainer.className.uppercase())
            players.persistPlayer(sessionId)

            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You unlock the ${trainer.className} class! " +
                        "You may now learn ${trainer.className} abilities here.",
                ),
            )
            gmcpEmitter?.sendCharClasses(sessionId, me.unlockedClasses, me.playerClass)
        }
    }
}
