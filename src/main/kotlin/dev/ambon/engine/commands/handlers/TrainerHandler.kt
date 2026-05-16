package dev.ambon.engine.commands.handlers

import dev.ambon.config.MulticlassConfig
import dev.ambon.config.RespecConfig
import dev.ambon.config.SkillPointsConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.TrainerDefinition
import dev.ambon.engine.PlayerState
import dev.ambon.engine.TrainerRegistry
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock

class TrainerHandler(
    private val ctx: EngineContext,
    private val abilitySystem: AbilitySystem,
    private val trainerRegistry: TrainerRegistry,
    private val skillPointsConfig: SkillPointsConfig = SkillPointsConfig(),
    private val multiclassConfig: MulticlassConfig = MulticlassConfig(),
    private val respecConfig: RespecConfig = RespecConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val prestigeSkillPointBonus: (Int) -> Int = { 0 },
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Train.List> { sid, _ -> handleTrainList(sid) }
        router.on<Command.Train.Learn> { sid, cmd -> handleTrainLearn(sid, cmd.keyword) }
        router.on<Command.Train.Unlock> { sid, cmd -> handleTrainUnlock(sid, cmd.className) }
        router.on<Command.Train.Reset> { sid, _ -> handleTrainReset(sid) }
    }

    /** Returns the trainer at [me]'s current room, or null (sending an error) if there is none. */
    private suspend fun findTrainer(sessionId: SessionId, me: PlayerState): TrainerDefinition? {
        val trainer = trainerRegistry.trainerInRoom(me.roomId)
        if (trainer == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "There is no trainer here."))
        }
        return trainer
    }

    /** Returns true if the player has unlocked [className] (case-insensitive). */
    private fun PlayerState.hasUnlocked(className: String): Boolean =
        unlockedClasses.any { it.equals(className, ignoreCase = true) }

    /** Pretty title label for a trainer — "Master Grizelda — Warrior/Rogue Trainer" for multi-class. */
    private fun trainerTitle(trainer: TrainerDefinition): String {
        val classLabel = if (trainer.isMultiClass) {
            trainer.classNames.joinToString("/") { it.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }
        } else {
            trainer.primaryClass
        }
        return "[ ${trainer.name} — $classLabel Trainer ]"
    }

    private suspend fun handleTrainList(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val trainer = findTrainer(sessionId, me) ?: return
            val available = abilitySystem.availableSkillPoints(
                level = me.level,
                spentPoints = abilitySystem.spentSkillPoints(me.learnedAbilityIds),
                interval = skillPointsConfig.interval,
                prestigeBonus = prestigeSkillPointBonus(me.prestigeLevel),
            )

            outbound.send(OutboundEvent.SendInfo(sessionId, trainerTitle(trainer)))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Skill points available: $available"))

            // Render one section per class this trainer teaches
            for (className in trainer.classNames) {
                val classUnlocked = me.hasUnlocked(className)
                val prettyClass = className.lowercase().replaceFirstChar { it.uppercaseChar() }

                outbound.send(OutboundEvent.SendInfo(sessionId, ""))
                outbound.send(OutboundEvent.SendInfo(sessionId, "  --- $prettyClass ---"))

                if (!classUnlocked) {
                    val unlockArg = if (trainer.isMultiClass) " ${className.lowercase()}" else ""
                    val atLimit = me.unlockedClasses.size >= multiclassConfig.maxClasses
                    val lockMsg = if (atLimit) {
                        "  ** $className class locked. You have reached the limit of " +
                            "${multiclassConfig.maxClasses} unlocked classes. **"
                    } else {
                        val cost = multiclassConfig.costFor(me.unlockedClasses.size)
                        "  ** $className class locked. Use 'train unlock$unlockArg' to pay " +
                            "$cost gold (requires level ${multiclassConfig.minLevel}). **"
                    }
                    outbound.send(OutboundEvent.SendInfo(sessionId, lockMsg))
                    continue
                }

                renderClassAbilities(sessionId, me, className)
            }

            gmcpEmitter?.sendTrainerList(sessionId, trainer, me, available, abilitySystem, multiclassConfig)
        }
    }

    /** Renders the ability list for a single class the player has unlocked. */
    private suspend fun renderClassAbilities(sessionId: SessionId, me: PlayerState, className: String) {
        val knownIds = abilitySystem.knownAbilityIds(sessionId)
        val trainable = abilitySystem.trainableAbilities(className, me.level, knownIds)
        val learned = abilitySystem.knownAbilities(sessionId)
            .filter { it.requiredClass.equals(className, ignoreCase = true) }

        val trees = abilitySystem.registry.treesForClass(className)
        if (trees.isNotEmpty()) {
            for (tree in trees.sorted()) {
                val treeName = tree.substringAfter("_").replaceFirstChar { it.uppercaseChar() }
                outbound.send(OutboundEvent.SendInfo(sessionId, "    === $treeName ==="))
                val treeAbilities = abilitySystem.registry.abilitiesInTree(tree)
                for (ability in treeAbilities) {
                    val isLearned = ability.id.value in knownIds
                    val prereqMet = abilitySystem.registry.isPrerequisiteMet(
                        ability.id,
                        knownIds,
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
                            "      $mark ${ability.displayName} " +
                                "(Tier ${ability.tier}, ${skillPointCostLabel(ability.skillPointCost)})$prereqSuffix",
                        ),
                    )
                }
            }
            // Show any untree'd abilities
            val untreeAbilities = trainable.filter { it.tree.isBlank() }
            if (untreeAbilities.isNotEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "    === General ==="))
                for (ability in untreeAbilities) {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "      [ ] ${ability.displayName} " +
                                "(Lvl ${ability.levelRequired}, ${skillPointCostLabel(ability.skillPointCost)})",
                        ),
                    )
                }
            }
        } else if (trainable.isEmpty()) {
            outbound.send(
                OutboundEvent.SendInfo(sessionId, "    No abilities available to learn here at your level."),
            )
        } else {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "    %-30s %5s %6s %s".format("Ability", "Lvl", "Cost", "Description"),
                ),
            )
            for (ability in trainable) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "    %-30s %5d %6s %s".format(
                            ability.displayName,
                            ability.levelRequired,
                            ability.skillPointCost,
                            ability.description,
                        ),
                    ),
                )
            }
        }
        if (trainable.isNotEmpty()) {
            outbound.send(
                OutboundEvent.SendInfo(sessionId, "    Use 'train learn <ability>' to spend skill points on abilities."),
            )
        }

        if (learned.isNotEmpty()) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "    Already learned: ${learned.joinToString { it.displayName }}",
                ),
            )
        }
    }

    private suspend fun handleTrainLearn(
        sessionId: SessionId,
        keyword: String,
    ) {
        players.withPlayer(sessionId) { me ->
            val trainer = findTrainer(sessionId, me) ?: return
            // Which of the trainer's classes are available to this player right now?
            val unlockedHere = trainer.classNames.filter { me.hasUnlocked(it) }
            if (unlockedHere.isEmpty()) {
                val unlockHint = if (trainer.isMultiClass) {
                    "Use 'train unlock <class>' to unlock one of: ${trainer.classNames.joinToString()}"
                } else {
                    "Use 'train unlock' to unlock the ${trainer.primaryClass} class."
                }
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You haven't unlocked any of this trainer's classes. $unlockHint",
                    ),
                )
                return
            }

            // Search for a matching ability across all unlocked classes this trainer teaches.
            // Include level-locked abilities so we can return a clear "requires level N" error
            // rather than a confusing "no matching ability" message.
            val knownIds = abilitySystem.knownAbilityIds(sessionId)
            val allCandidates: List<Pair<String, AbilityDefinition>> = unlockedHere.flatMap { cls ->
                abilitySystem.registry.abilitiesForClass(cls)
                    .filter { it.id.value !in knownIds }
                    .map { cls to it }
            }
            val matched = allCandidates.firstOrNull { (_, ability) ->
                ability.id.value.equals(keyword, ignoreCase = true) ||
                    ability.displayName.equals(keyword, ignoreCase = true)
            }
                ?: allCandidates.firstOrNull { (_, ability) ->
                    ability.id.value.startsWith(keyword.lowercase()) ||
                        ability.displayName.lowercase().contains(keyword.lowercase())
                }

            if (matched == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No trainable ability matching '$keyword' found here."))
                return
            }

            val matchedAbility = matched.second
            if (matchedAbility.levelRequired > me.level) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You need to be level ${matchedAbility.levelRequired} to learn ${matchedAbility.displayName}.",
                    ),
                )
                return
            }
            val target = matched

            val ability = target.second

            val error = abilitySystem.learnAbility(
                sessionId = sessionId,
                abilityId = AbilityId(ability.id.value),
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
            me.learnedAbilityIds.add(ability.id.value)
            val autoLearned = abilitySystem.recomputeKnownAbilities(sessionId, me.level, me.unlockedClasses)
            players.persistPlayer(sessionId)

            val remaining = abilitySystem.availableSkillPoints(
                level = me.level,
                spentPoints = abilitySystem.spentSkillPoints(me.learnedAbilityIds),
                interval = skillPointsConfig.interval,
                prestigeBonus = prestigeSkillPointBonus(me.prestigeLevel),
            )
            val pointWord = if (ability.skillPointCost == 1) "skill point" else "skill points"
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You learn ${ability.displayName} for ${ability.skillPointCost} $pointWord! ($remaining skill points remaining)",
                ),
            )
            sendAutoLearnedMessage(sessionId, autoLearned.filter { it.id != ability.id })

            gmcpEmitter?.sendCharSkills(sessionId, abilitySystem.knownAbilities(sessionId)) { id ->
                abilitySystem.cooldownRemainingMs(sessionId, id)
            }
            gmcpEmitter?.sendTrainerList(sessionId, trainer, me, remaining, abilitySystem, multiclassConfig)
        }
    }

    private suspend fun handleTrainUnlock(sessionId: SessionId, classArg: String?) {
        players.withPlayer(sessionId) { me ->
            val trainer = findTrainer(sessionId, me) ?: return

            // Decide which class to unlock
            val targetClass = when {
                classArg != null -> {
                    val match = trainer.classNames.firstOrNull { it.equals(classArg, ignoreCase = true) }
                    if (match == null) {
                        outbound.send(
                            OutboundEvent.SendError(
                                sessionId,
                                "This trainer doesn't teach '$classArg'. Available: ${trainer.classNames.joinToString()}",
                            ),
                        )
                        return
                    }
                    match
                }
                trainer.isMultiClass -> {
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "This trainer teaches multiple classes. Use 'train unlock <class>' — options: " +
                                trainer.classNames.joinToString(),
                        ),
                    )
                    return
                }
                else -> trainer.primaryClass
            }

            if (me.hasUnlocked(targetClass)) {
                outbound.send(
                    OutboundEvent.SendError(sessionId, "You have already unlocked the $targetClass class."),
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
            if (me.unlockedClasses.size >= multiclassConfig.maxClasses) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You have already unlocked the maximum of ${multiclassConfig.maxClasses} classes.",
                    ),
                )
                return
            }
            val cost = multiclassConfig.costFor(me.unlockedClasses.size)
            if (!me.isStaff && me.gold < cost) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You need $cost gold to unlock the $targetClass class.",
                    ),
                )
                return
            }
            if (!me.isStaff) me.gold -= cost
            markVitalsDirty(sessionId)
            me.unlockedClasses.add(targetClass.uppercase())
            val autoLearned = abilitySystem.recomputeKnownAbilities(sessionId, me.level, me.unlockedClasses)
            players.persistPlayer(sessionId)

            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "You unlock the $targetClass class! You may now learn $targetClass abilities here.",
                ),
            )
            sendAutoLearnedMessage(sessionId, autoLearned)
            gmcpEmitter?.sendCharClasses(sessionId, me.unlockedClasses, me.playerClass)
            val available = abilitySystem.availableSkillPoints(
                level = me.level,
                spentPoints = abilitySystem.spentSkillPoints(me.learnedAbilityIds),
                interval = skillPointsConfig.interval,
                prestigeBonus = prestigeSkillPointBonus(me.prestigeLevel),
            )
            gmcpEmitter?.sendTrainerList(sessionId, trainer, me, available, abilitySystem, multiclassConfig)
        }
    }

    private suspend fun handleTrainReset(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val trainer = findTrainer(sessionId, me) ?: return

            if (!respecConfig.enabled) {
                outbound.send(OutboundEvent.SendError(sessionId, "Ability respec is not available."))
                return
            }

            if (me.learnedAbilityIds.isEmpty()) {
                outbound.send(OutboundEvent.SendError(sessionId, "You have no learned abilities to reset."))
                return
            }

            // Check cooldown
            if (respecConfig.cooldownMs > 0 && me.lastRespecAtMs > 0) {
                val elapsed = clock.millis() - me.lastRespecAtMs
                if (elapsed < respecConfig.cooldownMs) {
                    val remainingSec = ((respecConfig.cooldownMs - elapsed) / 1000).coerceAtLeast(1)
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "You must wait $remainingSec seconds before resetting abilities again.",
                        ),
                    )
                    return
                }
            }

            // Check gold
            val cost = respecConfig.goldCost
            if (!me.isStaff && me.gold < cost) {
                outbound.send(
                    OutboundEvent.SendError(sessionId, "You need $cost gold to reset your abilities."),
                )
                return
            }

            // Deduct gold
            if (!me.isStaff) me.gold -= cost
            markVitalsDirty(sessionId)

            // Clear abilities
            val resetCount = me.learnedAbilityIds.size
            me.learnedAbilityIds.clear()
            abilitySystem.clearLearnedAbilities(sessionId)
            abilitySystem.recomputeKnownAbilities(sessionId, me.level, me.unlockedClasses)
            me.lastRespecAtMs = clock.millis()
            players.persistPlayer(sessionId)

            val available = abilitySystem.availableSkillPoints(
                level = me.level,
                spentPoints = 0,
                interval = skillPointsConfig.interval,
                prestigeBonus = prestigeSkillPointBonus(me.prestigeLevel),
            )

            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "Your abilities have been reset! $resetCount abilities removed. " +
                        "You now have $available skill points to spend.",
                ),
            )

            // Emit GMCP updates
            gmcpEmitter?.sendCharSkills(sessionId, abilitySystem.knownAbilities(sessionId)) { id ->
                abilitySystem.cooldownRemainingMs(sessionId, id)
            }
            gmcpEmitter?.sendCharVitals(sessionId, me)
            gmcpEmitter?.sendTrainerList(sessionId, trainer, me, available, abilitySystem, multiclassConfig)
        }
    }

    private fun skillPointCostLabel(cost: Int): String =
        if (cost == 0) {
            "Auto"
        } else {
            "Cost $cost"
        }

    private suspend fun sendAutoLearnedMessage(
        sessionId: SessionId,
        abilities: List<AbilityDefinition>,
    ) {
        if (abilities.isEmpty()) return
        val names = abilities.joinToString { it.displayName }
        val verb = if (abilities.size == 1) "is" else "are"
        outbound.send(OutboundEvent.SendText(sessionId, "$names $verb now yours automatically."))
    }
}
