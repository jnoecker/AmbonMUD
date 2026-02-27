package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.metrics.GameMetrics

class ItemHandler(
    ctx: EngineContext,
    private val questSystem: QuestSystem? = null,
    private val abilitySystem: AbilitySystem? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val progression: PlayerProgression = PlayerProgression(),
) : CommandHandler {
    private val players = ctx.players
    private val items = ctx.items
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Inventory> { sid, _ -> handleInventory(sid) }
        router.on<Command.Equipment> { sid, _ -> handleEquipment(sid) }
        router.on<Command.Wear> { sid, cmd -> handleWear(sid, cmd) }
        router.on<Command.Remove> { sid, cmd -> handleRemove(sid, cmd) }
        router.on<Command.Get> { sid, cmd -> handleGet(sid, cmd) }
        router.on<Command.Drop> { sid, cmd -> handleDrop(sid, cmd) }
        router.on<Command.Use> { sid, cmd -> handleUse(sid, cmd) }
        router.on<Command.Give> { sid, cmd -> handleGive(sid, cmd) }
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
                val line =
                    ItemSlot.entries.joinToString(", ") { slot ->
                        val name = slot.label()
                        val item = equipped[slot]?.item?.displayName ?: "none"
                        "$name: $item"
                    }
                outbound.send(OutboundEvent.SendInfo(sessionId, "You are wearing: $line"))
            }
        }
    }

    private suspend fun handleWear(
        sessionId: SessionId,
        cmd: Command.Wear,
    ) {
        players.withPlayer(sessionId) { me ->
            when (val result = items.equipFromInventory(me.sessionId, cmd.keyword)) {
                is ItemRegistry.EquipResult.Equipped -> {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "You wear ${result.item.item.displayName} on your ${result.slot.label()}.",
                        ),
                    )
                    combat.syncPlayerDefense(sessionId)
                    gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
                }
                is ItemRegistry.EquipResult.NotFound ->
                    outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.keyword}'."))
                is ItemRegistry.EquipResult.NotWearable ->
                    outbound.send(OutboundEvent.SendError(sessionId, "${result.item.item.displayName} cannot be worn."))
                is ItemRegistry.EquipResult.SlotOccupied ->
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "You are already wearing ${result.item.item.displayName} on your ${result.slot.label()}.",
                        ),
                    )
            }
        }
    }

    private suspend fun handleRemove(
        sessionId: SessionId,
        cmd: Command.Remove,
    ) {
        players.withPlayer(sessionId) { me ->
            when (val result = items.unequip(me.sessionId, cmd.slot)) {
                is ItemRegistry.UnequipResult.Unequipped -> {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "You remove ${result.item.item.displayName} from your ${result.slot.label()}.",
                        ),
                    )
                    combat.syncPlayerDefense(sessionId)
                    gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
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
        players.withPlayer(sessionId) { me ->
            val roomId = me.roomId
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
        players.withPlayer(sessionId) { me ->
            val roomId = me.roomId
            val moved = items.dropToRoom(me.sessionId, roomId, cmd.keyword)
            if (moved == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.keyword}'."))
                return
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "You drop ${moved.item.displayName}."))
            gmcpEmitter?.sendCharItemsRemove(sessionId, moved)
            syncRoomItemsGmcp(roomId)
        }
    }

    private suspend fun handleUse(
        sessionId: SessionId,
        cmd: Command.Use,
    ) {
        players.withPlayer(sessionId) { me ->
            when (val result = items.useItem(me.sessionId, cmd.keyword)) {
                is ItemRegistry.UseResult.Used -> {
                    val effect = result.item.item.onUse
                    if (effect == null) {
                        outbound.send(OutboundEvent.SendError(sessionId, "${result.item.item.displayName} cannot be used."))
                        return
                    }
                    outbound.send(OutboundEvent.SendInfo(sessionId, "You use ${result.item.item.displayName}."))
                    if (effect.healHp > 0) {
                        val previousHp = me.hp
                        me.hp = (me.hp + effect.healHp).coerceAtMost(me.maxHp)
                        val healed = (me.hp - previousHp).coerceAtLeast(0)
                        if (healed > 0) {
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You recover $healed HP."))
                            markVitalsDirty(sessionId)
                        } else {
                            outbound.send(OutboundEvent.SendInfo(sessionId, "You are already at full health."))
                        }
                    }
                    if (effect.grantXp > 0L) {
                        grantScaledItemXp(sessionId, effect.grantXp)
                    }
                    if (result.consumed) {
                        outbound.send(OutboundEvent.SendInfo(sessionId, "${result.item.item.displayName} is consumed."))
                        if (result.location == ItemRegistry.HeldItemLocation.EQUIPPED) {
                            combat.syncPlayerDefense(sessionId)
                        }
                        gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(me.sessionId), items.equipment(me.sessionId))
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
                    outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying or wearing '${cmd.keyword}'."))
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
    }

    private suspend fun handleGive(
        sessionId: SessionId,
        cmd: Command.Give,
    ) {
        players.withPlayer(sessionId) { me ->
            val targetSid = players.findSessionByName(cmd.playerName)
            if (targetSid == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.playerName}"))
                return
            }
            if (targetSid == sessionId) {
                outbound.send(OutboundEvent.SendError(sessionId, "You cannot give items to yourself."))
                return
            }
            players.withPlayer(targetSid) { target ->
                if (target.roomId != me.roomId) {
                    outbound.send(OutboundEvent.SendError(sessionId, "${target.name} is not here."))
                    return
                }
                when (val result = items.giveToPlayer(me.sessionId, targetSid, cmd.keyword)) {
                    is ItemRegistry.GiveResult.Given -> {
                        if (result.location == ItemRegistry.HeldItemLocation.EQUIPPED) {
                            combat.syncPlayerDefense(sessionId)
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
            val equipCha = items.equipment(sessionId).values.sumOf { it.item.charisma }
            val adjustedXp = progression.applyCharismaXpBonus(player.charisma + equipCha, scaledXp)

            val result = players.grantXp(sessionId, adjustedXp, progression) ?: return
            metrics.onXpAwarded(adjustedXp, "item_use")
            outbound.send(OutboundEvent.SendInfo(sessionId, "You gain $adjustedXp XP."))

            if (result.levelsGained <= 0) return
            metrics.onLevelUp()

            val levelUpMessage = progression.buildLevelUpMessage(result, player.constitution, player.intelligence, player.playerClass)
            outbound.send(OutboundEvent.SendText(sessionId, levelUpMessage))

            if (abilitySystem != null) {
                val newAbilities = abilitySystem.syncAbilities(sessionId, result.newLevel, player.playerClass)
                for (ability in newAbilities) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You have learned ${ability.displayName}!"))
                }
            }
        }
    }

    private suspend fun syncRoomItemsGmcp(roomId: RoomId) {
        val emitter = gmcpEmitter ?: return
        val roomItems = items.itemsInRoom(roomId)
        for (player in players.playersInRoom(roomId)) {
            emitter.sendRoomItems(player.sessionId, roomItems)
        }
    }
}
