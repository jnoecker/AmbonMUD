package dev.ambon.engine.commands.handlers

import dev.ambon.config.SkillPointsConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.items.ItemUseEffect
import dev.ambon.engine.EquipmentSlotRegistry
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.TradeSystem
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.healHp
import dev.ambon.engine.healMana
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.metrics.GameMetrics

class ItemHandler(
    ctx: EngineContext,
    private val questSystem: QuestSystem? = null,
    private val abilitySystem: AbilitySystem? = null,
    private val dialogueSystem: DialogueSystem? = null,
    private val tradeSystem: TradeSystem? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val markStatsDirty: (SessionId) -> Unit = {},
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val progression: PlayerProgression = PlayerProgression(),
    private val housingSystem: HousingSystem? = null,
    private val skillPointsConfig: SkillPointsConfig = SkillPointsConfig(),
) : CommandHandler {
    private val players = ctx.players
    private val items = ctx.items
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val classRegistry = ctx.classRegistry
    private val equipSlots: EquipmentSlotRegistry? = ctx.equipmentSlotRegistry

    override fun register(router: CommandRouter) {
        router.on<Command.Inventory> { sid, _ -> handleInventory(sid) }
        router.on<Command.Equipment> { sid, _ -> handleEquipment(sid) }
        router.on<Command.Wear> { sid, cmd -> handleWear(sid, cmd) }
        router.on<Command.Remove> { sid, cmd -> handleRemove(sid, cmd) }
        router.on<Command.Get> { sid, cmd -> handleGet(sid, cmd) }
        router.on<Command.Drop> { sid, cmd -> handleDrop(sid, cmd) }
        router.on<Command.Use> { sid, cmd -> handleUse(sid, cmd) }
        router.on<Command.QuickHeal> { sid, _ -> handleQuickPotion(sid, PotionKind.HP) }
        router.on<Command.QuickMana> { sid, _ -> handleQuickPotion(sid, PotionKind.MANA) }
        router.on<Command.Give> { sid, cmd -> handleGive(sid, cmd) }
    }

    internal enum class PotionKind { HP, MANA }

    private suspend fun handleQuickPotion(
        sessionId: SessionId,
        kind: PotionKind,
    ) {
        if (isBlocked(sessionId, allowInCombat = true)) return
        players.withPlayer(sessionId) { me ->
            val (current, max, label) = when (kind) {
                PotionKind.HP -> Triple(me.hp, me.maxHp, "HP")
                PotionKind.MANA -> Triple(me.mana, me.maxMana, "mana")
            }
            val missing = max - current
            if (missing <= 0) {
                outbound.send(OutboundEvent.SendError(sessionId, "Your $label is already full."))
                return
            }
            val inventory = items.inventory(me.sessionId)
            val candidates = inventory.filter { instance ->
                val effect = instance.item.onUse ?: return@filter false
                instance.item.consumable && healAmountFor(effect, kind) > 0
            }
            if (candidates.isEmpty()) {
                val potionWord = if (kind == PotionKind.HP) "healing potion" else "mana potion"
                outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying any $potionWord."))
                return
            }
            val chosen = selectBestPotion(candidates, missing, kind)
            val result = items.useItemById(me.sessionId, chosen.id)
            applyUseResult(sessionId, result)
        }
    }

    /**
     * Picks the least powerful potion that fully restores [missing]; if none qualify, picks the most powerful.
     * Ties broken by inventory order (stable).
     */
    internal fun selectBestPotion(
        candidates: List<ItemInstance>,
        missing: Int,
        kind: PotionKind,
    ): ItemInstance {
        val sufficient = candidates.filter { healAmountFor(it.item.onUse!!, kind) >= missing }
        return if (sufficient.isNotEmpty()) {
            sufficient.minBy { healAmountFor(it.item.onUse!!, kind) }
        } else {
            candidates.maxBy { healAmountFor(it.item.onUse!!, kind) }
        }
    }

    private fun healAmountFor(
        effect: ItemUseEffect,
        kind: PotionKind,
    ): Int = if (kind == PotionKind.HP) effect.healHp else effect.healMana

    /**
     * Returns true (and sends an error) if the player is in a state that blocks the item command.
     * Dialogue and trade always block. Combat blocks unless [allowInCombat] is true (e.g. potions).
     */
    private suspend fun isBlocked(
        sessionId: SessionId,
        allowInCombat: Boolean = false,
    ): Boolean {
        if (!allowInCombat && combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You can't do that while in combat."))
            return true
        }
        if (dialogueSystem?.isInConversation(sessionId) == true) {
            outbound.send(OutboundEvent.SendError(sessionId, "You can't do that while in conversation."))
            return true
        }
        if (tradeSystem?.isInTrade(sessionId) == true) {
            outbound.send(OutboundEvent.SendError(sessionId, "You can't do that while trading."))
            return true
        }
        return false
    }

    private suspend fun handleInventory(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val inv = items.inventory(me.sessionId)
            if (inv.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "You are carrying: nothing"))
            } else {
                val list = inv.map { it.item.displayName }.sorted().joinToString(", ")
                outbound.send(OutboundEvent.SendInfo(sessionId, "You are carrying: $list"))
            }
        }
    }

    private suspend fun handleEquipment(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val equipped = items.equipment(me.sessionId)
            if (equipped.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "You are wearing: nothing"))
            } else {
                val slots = equipSlots?.allSlots() ?: equipped.keys.toList()
                val line =
                    slots.joinToString(", ") { slot ->
                        val item = equipped[slot]?.item?.displayName ?: "none"
                        "${slot.label()}: $item"
                    }
                outbound.send(OutboundEvent.SendInfo(sessionId, "You are wearing: $line"))
            }
        }
    }

    private suspend fun handleWear(
        sessionId: SessionId,
        cmd: Command.Wear,
    ) {
        if (isBlocked(sessionId)) return
        players.withPlayer(sessionId) { me ->
            when (val result = items.equipFromInventory(me.sessionId, cmd.keyword)) {
                is ItemRegistry.EquipResult.Equipped -> {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "You wear ${result.item.item.displayName} on your ${result.slot.label()}.",
                        ),
                    )
                    afterEquipChange(sessionId, combat, items, gmcpEmitter, markStatsDirty)
                }
                is ItemRegistry.EquipResult.Swapped -> {
                    val previousNote =
                        if (result.previousDissolved) {
                            "${result.previousItem.item.displayName} dissolves back into your Arcanum and you wear"
                        } else {
                            "You remove ${result.previousItem.item.displayName} and wear"
                        }
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "$previousNote ${result.item.item.displayName} on your ${result.slot.label()}.",
                        ),
                    )
                    afterEquipChange(sessionId, combat, items, gmcpEmitter, markStatsDirty)
                }
                is ItemRegistry.EquipResult.NotFound ->
                    outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.keyword}'."))
                is ItemRegistry.EquipResult.NotWearable ->
                    outbound.send(OutboundEvent.SendError(sessionId, "${result.item.item.displayName} cannot be worn."))
            }
        }
    }

    private suspend fun handleRemove(
        sessionId: SessionId,
        cmd: Command.Remove,
    ) {
        if (isBlocked(sessionId)) return
        val slot = ItemSlot.parse(cmd.slot)
        if (slot == null || (equipSlots != null && !equipSlots.isValid(slot))) {
            val validSlots = equipSlots?.slotNames()?.joinToString("|") ?: "head|body|hand"
            outbound.send(OutboundEvent.SendError(sessionId, "remove <$validSlots>"))
            return
        }
        players.withPlayer(sessionId) { me ->
            when (val result = items.unequip(me.sessionId, slot)) {
                is ItemRegistry.UnequipResult.Unequipped -> {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "You remove ${result.item.item.displayName} from your ${result.slot.label()}.",
                        ),
                    )
                    afterEquipChange(sessionId, combat, items, gmcpEmitter, markStatsDirty)
                }
                is ItemRegistry.UnequipResult.Dissolved -> {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "${result.item.item.displayName} dissolves back into the pages of your Arcanum.",
                        ),
                    )
                    afterEquipChange(sessionId, combat, items, gmcpEmitter, markStatsDirty)
                }
                is ItemRegistry.UnequipResult.SlotEmpty ->
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "You are not wearing anything on your ${result.slot.label()}.",
                        ),
                    )
            }
        }
    }

    private suspend fun handleGet(
        sessionId: SessionId,
        cmd: Command.Get,
    ) {
        if (isBlocked(sessionId)) return
        players.withPlayer(sessionId) { me ->
            if (housingSystem != null && housingSystem.isHouseRoom(me.roomId) && !housingSystem.isInOwnHouse(sessionId)) {
                outbound.send(OutboundEvent.SendError(sessionId, "You can't take items from someone else's house."))
                return
            }
            val roomId = me.roomId
            val target = items.peekRoomItem(roomId, cmd.keyword)
            if (target == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see '${cmd.keyword}' here."))
                return
            }
            if (!target.item.takeable) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You can't take ${target.item.displayName}.",
                    ),
                )
                return
            }
            val moved = items.takeFromRoom(me.sessionId, roomId, cmd.keyword)
            if (moved == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't see '${cmd.keyword}' here."))
                return
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "You pick up ${moved.item.displayName}."))
            gmcpEmitter?.sendCharItemsAdd(sessionId, moved)
            syncRoomItemsGmcp(roomId)
            questSystem?.onItemCollected(sessionId, moved)
        }
    }

    private suspend fun handleDrop(
        sessionId: SessionId,
        cmd: Command.Drop,
    ) {
        if (isBlocked(sessionId)) return
        players.withPlayer(sessionId) { me ->
            val roomId = me.roomId
            // Check vault capacity in house rooms
            if (housingSystem != null && housingSystem.isHouseRoom(roomId)) {
                val capacity = housingSystem.vaultCapacity(sessionId)
                if (capacity > 0 && items.itemsInRoom(roomId).size >= capacity) {
                    outbound.send(OutboundEvent.SendError(sessionId, "This room is full. It can hold at most $capacity items."))
                    return
                }
            }
            val carried = items.peekInventoryItem(me.sessionId, cmd.keyword)
            if (carried != null && carried.item.isBound()) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You can't drop ${carried.item.displayName} — it's a ${carried.item.boundKind()}.",
                    ),
                )
                return
            }
            val moved = items.dropToRoom(me.sessionId, roomId, cmd.keyword)
            if (moved == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.keyword}'."))
                return
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "You drop ${moved.item.displayName}."))
            // Warn about non-persistent drops in house rooms without vault storage
            if (housingSystem != null && housingSystem.isHouseRoom(roomId) && housingSystem.vaultCapacity(sessionId) == 0) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "(Items left here won't survive a restart.)"))
            }
            gmcpEmitter?.sendCharItemsRemove(sessionId, moved)
            syncRoomItemsGmcp(roomId)
        }
    }

    private suspend fun handleUse(
        sessionId: SessionId,
        cmd: Command.Use,
    ) {
        if (isBlocked(sessionId, allowInCombat = true)) return
        players.withPlayer(sessionId) { me ->
            val result = items.useItem(me.sessionId, cmd.keyword)
            if (result is ItemRegistry.UseResult.NotFound) {
                outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying or wearing '${cmd.keyword}'."))
                return
            }
            applyUseResult(sessionId, result)
        }
    }

    private suspend fun applyUseResult(
        sessionId: SessionId,
        result: ItemRegistry.UseResult,
    ) {
        when (result) {
            is ItemRegistry.UseResult.Used -> {
                val effect = result.item.item.onUse
                if (effect == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "${result.item.item.displayName} cannot be used."))
                    return
                }
                outbound.send(OutboundEvent.SendInfo(sessionId, "You use ${result.item.item.displayName}."))
                players.withPlayer(sessionId) { me ->
                    if (effect.healHp > 0) {
                        val previousHp = me.hp
                        me.healHp(effect.healHp)
                        val healed = (me.hp - previousHp).coerceAtLeast(0)
                        if (healed > 0) {
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You recover $healed HP."))
                            markVitalsDirty(sessionId)
                        } else {
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You are already at full health."))
                        }
                    }
                    if (effect.healMana > 0) {
                        val previousMana = me.mana
                        me.healMana(effect.healMana)
                        val restored = (me.mana - previousMana).coerceAtLeast(0)
                        if (restored > 0) {
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You recover $restored mana."))
                            markVitalsDirty(sessionId)
                        } else {
                            outbound.send(OutboundEvent.SendInfo(sessionId, "Your mana is already full."))
                        }
                    }
                }
                if (effect.grantXp > 0L) {
                    grantScaledItemXp(sessionId, effect.grantXp)
                }
                if (result.consumed) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "${result.item.item.displayName} is consumed."))
                    afterEquipChange(sessionId, combat, items, gmcpEmitter, markStatsDirty)
                } else if (result.remainingCharges != null) {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "${result.item.item.displayName} has ${result.remainingCharges} charge(s) remaining.",
                        ),
                    )
                }
            }
            is ItemRegistry.UseResult.NotFound ->
                outbound.send(OutboundEvent.SendError(sessionId, "That item is no longer available."))
            is ItemRegistry.UseResult.NotUsable ->
                outbound.send(OutboundEvent.SendError(sessionId, "${result.item.item.displayName} cannot be used."))
            is ItemRegistry.UseResult.NoCharges ->
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "${result.item.item.displayName} has no charges remaining.",
                    ),
                )
        }
    }

    private suspend fun handleGive(
        sessionId: SessionId,
        cmd: Command.Give,
    ) {
        if (isBlocked(sessionId)) return
        players.withPlayer(sessionId) { me ->
            val targetSid = requirePlayerOnline(sessionId, cmd.playerName, players, outbound) ?: return
            if (targetSid == sessionId) {
                outbound.send(OutboundEvent.SendError(sessionId, "You cannot give items to yourself."))
                return
            }
            players.withPlayer(targetSid) { target ->
                if (!requireSameRoom(sessionId, me, target, outbound)) return
                val owned = items.peekOwnedItem(me.sessionId, cmd.keyword)
                if (owned != null && owned.item.isBound()) {
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "You can't give away ${owned.item.displayName} — it's a ${owned.item.boundKind()}.",
                        ),
                    )
                    return
                }
                when (val result = items.giveToPlayer(me.sessionId, targetSid, cmd.keyword)) {
                    is ItemRegistry.GiveResult.Given -> {
                        if (result.location == ItemRegistry.HeldItemLocation.EQUIPPED) {
                            afterEquipChange(sessionId, combat, items, gmcpEmitter, markStatsDirty)
                        }
                        outbound.send(OutboundEvent.SendInfo(sessionId, "You give ${result.item.item.displayName} to ${target.name}."))
                        outbound.send(OutboundEvent.SendInfo(targetSid, "${me.name} gives you ${result.item.item.displayName}."))
                        gmcpEmitter?.sendCharItemsRemove(sessionId, result.item)
                        gmcpEmitter?.sendCharItemsAdd(targetSid, result.item)
                    }
                    is ItemRegistry.GiveResult.NotFound ->
                        outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying or wearing '${cmd.keyword}'."))
                }
            }
        }
    }

    private suspend fun grantScaledItemXp(
        sessionId: SessionId,
        rawXp: Long,
    ) {
        val scaledXp = progression.scaledXp(rawXp)
        if (scaledXp <= 0L) return

        players.withPlayer(sessionId) { player ->
            val equipCha = items.equipmentBonuses(sessionId, classRegistry?.get(player.playerClass)).stats["CHA"]
            val adjustedXp = progression.applyCharismaXpBonus(player.stats["CHA"] + equipCha, scaledXp)

            val result = players.grantXp(sessionId, adjustedXp, progression) ?: return
            metrics.onXpAwarded(adjustedXp, "item_use")
            outbound.send(OutboundEvent.SendInfo(sessionId, "You gain $adjustedXp XP."))

            if (result.levelsGained <= 0) return
            metrics.onLevelUp()

            val hpStatValue = player.stats["CON"]
            val manaStatValue = player.stats["INT"]
            val levelUpMessage = progression.buildLevelUpMessage(result, hpStatValue, manaStatValue, player.playerClass)
            outbound.send(OutboundEvent.SendText(sessionId, levelUpMessage))

            val (classHpPerLevel, classManaPerLevel) = progression.resolveClassScaling(player.playerClass)
            val newMaxHp = progression.maxHpForLevel(result.newLevel, hpStatValue, classHpPerLevel)
            val oldMaxHp = progression.maxHpForLevel(result.previousLevel, hpStatValue, classHpPerLevel)
            val hpGained = (newMaxHp - oldMaxHp).coerceAtLeast(0)
            val newMaxMana = progression.maxManaForLevel(result.newLevel, manaStatValue, classManaPerLevel)
            val oldMaxMana = progression.maxManaForLevel(result.previousLevel, manaStatValue, classManaPerLevel)
            val manaGained = (newMaxMana - oldMaxMana).coerceAtLeast(0)

            var autoLearnedNames: List<String> = emptyList()
            var availablePoints = 0
            if (abilitySystem != null) {
                val autoLearned = abilitySystem.recomputeKnownAbilities(sessionId, result.newLevel, player.unlockedClasses)
                autoLearnedNames = autoLearned.map { it.displayName }
                if (autoLearned.isNotEmpty()) {
                    val names = autoLearned.joinToString { it.displayName }
                    val verb = if (autoLearned.size == 1) "is" else "are"
                    outbound.send(OutboundEvent.SendText(sessionId, "$names $verb now yours automatically."))
                }
                availablePoints = abilitySystem.availableSkillPoints(
                    level = result.newLevel,
                    spentPoints = abilitySystem.spentSkillPoints(player.learnedAbilityIds),
                    interval = skillPointsConfig.interval,
                )
                if (availablePoints > 0) {
                    val pointWord = if (availablePoints == 1) "skill point" else "skill points"
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "You have $availablePoints $pointWord available! Visit a class trainer to learn new abilities.",
                        ),
                    )
                }
                gmcpEmitter?.sendCharSkills(
                    sessionId,
                    abilitySystem.knownAbilities(sessionId),
                    cooldownRemainingMs = { abilityId -> abilitySystem.cooldownRemainingMs(sessionId, abilityId) },
                    player = player,
                    manaCostFor = { ability -> abilitySystem.computeManaCost(player, ability) },
                )
            }

            gmcpEmitter?.sendCharGain(
                sessionId,
                "levelUp",
                result.newLevel.toLong(),
                newLevel = result.newLevel,
                hpGained = hpGained,
                manaGained = manaGained,
            )
            gmcpEmitter?.sendCharLevelUp(
                sessionId,
                previousLevel = result.previousLevel,
                newLevel = result.newLevel,
                levelsGained = result.levelsGained.coerceAtLeast(1),
                hpGained = hpGained,
                manaGained = manaGained,
                newAbilities = autoLearnedNames,
                skillPointsAvailable = availablePoints,
                isMilestone = result.newLevel == 10 || result.newLevel == 25 || result.newLevel == 50 || result.newLevel == 100,
            )
        }
    }

    private suspend fun syncRoomItemsGmcp(roomId: RoomId) {
        gmcpEmitter?.broadcastRoomItems(roomId, items.itemsInRoom(roomId), players)
    }
}
