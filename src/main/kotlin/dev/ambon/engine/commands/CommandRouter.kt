package dev.ambon.engine.commands

import dev.ambon.bus.OutboundBus
import dev.ambon.config.EconomyConfig
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.broadcastToRoom
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.ZoneInstance
import kotlin.math.roundToInt

/**
 * Result of a phase (layer-switch) request.
 */
sealed interface PhaseResult {
    /** Instancing is not enabled on this engine. */
    data object NotEnabled : PhaseResult

    /** Here are the instances of the player's current zone. */
    data class InstanceList(
        val currentEngineId: String,
        val instances: List<ZoneInstance>,
    ) : PhaseResult

    /** Handoff to a different instance was initiated. */
    data object Initiated : PhaseResult

    /** Already on the requested instance or no other instance available. */
    data class NoOp(
        val reason: String,
    ) : PhaseResult
}

class CommandRouter(
    private val world: World,
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val combat: CombatSystem,
    private val outbound: OutboundBus,
    private val progression: PlayerProgression = PlayerProgression(),
    private val abilitySystem: AbilitySystem? = null,
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val onShutdown: suspend () -> Unit = {},
    private val onMobSmited: (MobId) -> Unit = {},
    private val onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
    private val interEngineBus: InterEngineBus? = null,
    private val engineId: String = "",
    private val onRemoteWho: (suspend (SessionId) -> Unit)? = null,
    private val playerLocationIndex: PlayerLocationIndex? = null,
    private val gmcpEmitter: GmcpEmitter? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val onPhase: (suspend (SessionId, String?) -> PhaseResult)? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val shopRegistry: ShopRegistry? = null,
    private val economyConfig: EconomyConfig = EconomyConfig(),
    private val dialogueSystem: DialogueSystem? = null,
) {
    private var adminSpawnSeq = 0

    suspend fun handle(
        sessionId: SessionId,
        cmd: Command,
    ) {
        when (cmd) {
            Command.Noop -> {
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Help -> {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        """
                        Commands:
                            help/?
                            look/l (or look <direction>)
                            n/s/e/w/u/d
                            exits/ex
                            say <msg> or '<msg>
                            emote <msg>
                            pose <msg>
                            who
                            tell/t <player> <msg>
                            whisper/wh <player> <msg>
                            gossip/gs <msg>
                            shout/sh <msg>
                            ooc <msg>
                            inventory/inv/i
                            equipment/eq
                            wear/equip <item>
                            remove/unequip <slot>
                            get/take/pickup <item>
                            drop <item>
                            use <item>
                            give <item> <player>
                            talk <npc>
                            kill <mob>
                            flee
                            cast/c <spell> [target]
                            spells/abilities
                            effects/buffs/debuffs
                            score/sc
                            gold/balance
                            list/shop
                            buy <item>
                            sell <item>
                            ansi on/off
                            colors
                            clear
                            quit/exit
                        Staff commands (requires staff flag):
                            goto <zone:room | room | zone:>
                            transfer <player> <room>
                            spawn <mob-template>
                            smite <player|mob>
                            kick <player>
                            dispel <player|mob>
                            shutdown
                        """.trimIndent(),
                    ),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Look -> {
                sendLook(sessionId)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Move -> {
                if (combat.isInCombat(sessionId)) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You are in combat. Try 'flee'."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                if (statusEffects?.hasPlayerEffect(sessionId, EffectType.ROOT) == true) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You are rooted and cannot move!"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val me = players.get(sessionId) ?: return
                val from = me.roomId
                val room = world.rooms[from] ?: return
                val to = room.exits[cmd.dir]
                if (to == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You can't go that way."))
                } else if (room.remoteExits.contains(cmd.dir) || !world.rooms.containsKey(to)) {
                    // Cross-zone exit — hand off to the owning engine if sharding is active
                    if (onCrossZoneMove != null) {
                        onCrossZoneMove.invoke(sessionId, to)
                        return // handoff manages its own prompt/cleanup
                    } else {
                        outbound.send(OutboundEvent.SendText(sessionId, "The way shimmers but does not yield."))
                    }
                } else {
                    val oldMembers = players.playersInRoom(from).filter { it.sessionId != me.sessionId }
                    for (other in oldMembers) {
                        outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} leaves."))
                        gmcpEmitter?.sendRoomRemovePlayer(other.sessionId, me.name)
                    }
                    dialogueSystem?.onPlayerMoved(sessionId)
                    players.moveTo(sessionId, to)
                    val newMembers = players.playersInRoom(to).filter { it.sessionId != me.sessionId }
                    for (other in newMembers) {
                        outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} enters."))
                        gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
                    }
                    sendLook(sessionId)
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Quit -> {
                outbound.send(OutboundEvent.Close(sessionId, "Goodbye!"))
            }

            Command.AnsiOn -> {
                outbound.send(OutboundEvent.SetAnsi(sessionId, true))
                players.setAnsiEnabled(sessionId, true)
                outbound.send(OutboundEvent.SendInfo(sessionId, "ANSI enabled"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.AnsiOff -> {
                outbound.send(OutboundEvent.SetAnsi(sessionId, false))
                players.setAnsiEnabled(sessionId, false)
                outbound.send(OutboundEvent.SendInfo(sessionId, "ANSI disabled"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Clear -> {
                outbound.send(OutboundEvent.ClearScreen(sessionId))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Colors -> {
                outbound.send(OutboundEvent.ShowAnsiDemo(sessionId))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Say -> {
                val me = players.get(sessionId) ?: return
                val roomId = me.roomId
                val members = players.playersInRoom(roomId)

                // Sender feedback
                outbound.send(OutboundEvent.SendText(sessionId, "You say: ${cmd.message}"))

                // Everyone else in the room
                for (other in members) {
                    if (other == me) continue
                    outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} says: ${cmd.message}"))
                }

                for (member in members) {
                    gmcpEmitter?.sendCommChannel(member.sessionId, "say", me.name, cmd.message)
                }

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Emote -> {
                val me = players.get(sessionId) ?: return
                val roomId = me.roomId
                val members = players.playersInRoom(roomId)

                // Everyone in the room
                for (other in members) {
                    outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} ${cmd.message}"))
                }

                // Send a prompt only to the emoting user. */
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Who -> {
                val list =
                    players
                        .allPlayers()
                        .sortedBy { it.name }
                        .joinToString(separator = ", ") { it.name }

                outbound.send(OutboundEvent.SendInfo(sessionId, "Online: $list"))
                if (onRemoteWho != null) {
                    onRemoteWho.invoke(sessionId)
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Phase -> {
                if (combat.isInCombat(sessionId)) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You can't switch layers while in combat!"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                if (onPhase == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Layering is not enabled on this server."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                when (val result = onPhase.invoke(sessionId, cmd.targetHint)) {
                    is PhaseResult.NotEnabled -> {
                        outbound.send(OutboundEvent.SendError(sessionId, "Layering is not enabled on this server."))
                    }
                    is PhaseResult.InstanceList -> {
                        val lines =
                            buildString {
                                appendLine("Zone instances for '${result.instances.firstOrNull()?.zone ?: "unknown"}':")
                                for (inst in result.instances) {
                                    val marker = if (inst.engineId == result.currentEngineId) " <- you are here" else ""
                                    appendLine(
                                        "  ${inst.engineId}: ${inst.playerCount}/${inst.capacity} players$marker",
                                    )
                                }
                                append("Use 'phase <instance>' to switch.")
                            }
                        outbound.send(OutboundEvent.SendText(sessionId, lines))
                    }
                    is PhaseResult.Initiated -> {
                        // Handoff message already sent by HandoffManager
                    }
                    is PhaseResult.NoOp -> {
                        outbound.send(OutboundEvent.SendText(sessionId, result.reason))
                    }
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Tell -> {
                val me = players.get(sessionId) ?: return
                val targetSid = players.findSessionByName(cmd.target)
                if (targetSid == null) {
                    // Not found locally — try remote delivery via inter-engine bus
                    if (interEngineBus != null) {
                        val tell =
                            InterEngineMessage.TellMessage(
                                fromName = me.name,
                                toName = cmd.target,
                                text = cmd.message,
                            )
                        val targetEngineId = playerLocationIndex?.lookupEngineId(cmd.target)
                        if (targetEngineId != null && targetEngineId != engineId) {
                            interEngineBus.sendTo(targetEngineId, tell)
                        } else {
                            interEngineBus.broadcast(tell)
                        }
                        outbound.send(OutboundEvent.SendText(sessionId, "You tell ${cmd.target}: ${cmd.message}"))
                    } else {
                        outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.target}"))
                    }
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                if (targetSid == sessionId) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "You tell yourself: ${cmd.message}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                outbound.send(OutboundEvent.SendText(sessionId, "You tell ${cmd.target}: ${cmd.message}"))
                outbound.send(OutboundEvent.SendText(targetSid, "${me.name} tells you: ${cmd.message}"))
                gmcpEmitter?.sendCommChannel(sessionId, "tell", me.name, cmd.message)
                gmcpEmitter?.sendCommChannel(targetSid, "tell", me.name, cmd.message)

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Gossip -> {
                val me = players.get(sessionId) ?: return
                for (p in players.allPlayers()) {
                    if (p.sessionId == sessionId) {
                        outbound.send(OutboundEvent.SendText(sessionId, "You gossip: ${cmd.message}"))
                    } else {
                        outbound.send(OutboundEvent.SendText(p.sessionId, "[GOSSIP] ${me.name}: ${cmd.message}"))
                    }
                    gmcpEmitter?.sendCommChannel(p.sessionId, "gossip", me.name, cmd.message)
                }
                // Broadcast to other engines
                interEngineBus?.broadcast(
                    InterEngineMessage.GlobalBroadcast(
                        broadcastType = BroadcastType.GOSSIP,
                        senderName = me.name,
                        text = cmd.message,
                        sourceEngineId = engineId,
                    ),
                )

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Whisper -> {
                val me = players.get(sessionId) ?: return
                val targetSid = players.findSessionByName(cmd.target)
                if (targetSid == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.target}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                if (targetSid == sessionId) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "You whisper to yourself: ${cmd.message}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val target = players.get(targetSid) ?: return
                if (target.roomId != me.roomId) {
                    outbound.send(OutboundEvent.SendError(sessionId, "${target.name} is not here."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                outbound.send(OutboundEvent.SendText(sessionId, "You whisper to ${target.name}: ${cmd.message}"))
                outbound.send(OutboundEvent.SendText(targetSid, "${me.name} whispers to you: ${cmd.message}"))
                gmcpEmitter?.sendCommChannel(sessionId, "whisper", me.name, cmd.message)
                gmcpEmitter?.sendCommChannel(targetSid, "whisper", me.name, cmd.message)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Shout -> {
                val me = players.get(sessionId) ?: return
                val zone = me.roomId.zone
                outbound.send(OutboundEvent.SendText(sessionId, "You shout: ${cmd.message}"))
                for (p in players.playersInZone(zone)) {
                    if (p.sessionId != sessionId) {
                        outbound.send(OutboundEvent.SendText(p.sessionId, "[SHOUT] ${me.name}: ${cmd.message}"))
                    }
                    gmcpEmitter?.sendCommChannel(p.sessionId, "shout", me.name, cmd.message)
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Ooc -> {
                val me = players.get(sessionId) ?: return
                for (p in players.allPlayers()) {
                    if (p.sessionId == sessionId) {
                        outbound.send(OutboundEvent.SendText(sessionId, "You say OOC: ${cmd.message}"))
                    } else {
                        outbound.send(OutboundEvent.SendText(p.sessionId, "[OOC] ${me.name}: ${cmd.message}"))
                    }
                    gmcpEmitter?.sendCommChannel(p.sessionId, "ooc", me.name, cmd.message)
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Pose -> {
                val me = players.get(sessionId) ?: return
                if (!cmd.message.contains(me.name, ignoreCase = true)) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Your pose must include your name (${me.name})."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val roomId = me.roomId
                val members = players.playersInRoom(roomId)
                for (other in members) {
                    outbound.send(OutboundEvent.SendText(other.sessionId, cmd.message))
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Exits -> {
                val r = room(currentRoomId(sessionId))
                outbound.send(OutboundEvent.SendInfo(sessionId, exitsLine(r)))

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.LookDir -> {
                val r = room(currentRoomId(sessionId))
                val targetId = r.exits[cmd.dir]
                if (targetId == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "You see nothing that way."))
                } else {
                    val target = world.rooms[targetId]
                    if (target == null || r.remoteExits.contains(cmd.dir)) {
                        outbound.send(OutboundEvent.SendText(sessionId, "You see a shimmering passage."))
                    } else {
                        outbound.send(OutboundEvent.SendText(sessionId, target.title))
                    }
                }

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Inventory -> {
                val me = players.get(sessionId) ?: return
                val inv = items.inventory(me.sessionId)
                if (inv.isEmpty()) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "You are carrying: nothing"))
                } else {
                    val list = inv.map { it.item.displayName }.sorted().joinToString(", ")
                    outbound.send(OutboundEvent.SendInfo(sessionId, "You are carrying: $list"))
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Equipment -> {
                val me = players.get(sessionId) ?: return
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
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Wear -> {
                val me = players.get(sessionId) ?: return
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

                    is ItemRegistry.EquipResult.NotFound -> {
                        outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.keyword}'."))
                    }

                    is ItemRegistry.EquipResult.NotWearable -> {
                        outbound.send(
                            OutboundEvent.SendError(
                                sessionId,
                                "${result.item.item.displayName} cannot be worn.",
                            ),
                        )
                    }

                    is ItemRegistry.EquipResult.SlotOccupied -> {
                        outbound.send(
                            OutboundEvent.SendError(
                                sessionId,
                                "You are already wearing ${result.item.item.displayName} on your ${result.slot.label()}.",
                            ),
                        )
                    }
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Remove -> {
                val me = players.get(sessionId) ?: return
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

                    is ItemRegistry.UnequipResult.SlotEmpty -> {
                        outbound.send(
                            OutboundEvent.SendError(
                                sessionId,
                                "You are not wearing anything on your ${result.slot.label()}.",
                            ),
                        )
                    }
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Get -> {
                val me = players.get(sessionId) ?: return
                val roomId = me.roomId

                val moved = items.takeFromRoom(me.sessionId, roomId, cmd.keyword)
                if (moved == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "You don't see '${cmd.keyword}' here."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }

                outbound.send(OutboundEvent.SendInfo(sessionId, "You pick up ${moved.item.displayName}."))
                gmcpEmitter?.sendCharItemsAdd(sessionId, moved)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Drop -> {
                val me = players.get(sessionId) ?: return
                val roomId = me.roomId

                val moved = items.dropToRoom(me.sessionId, roomId, cmd.keyword)
                if (moved == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.keyword}'."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }

                outbound.send(OutboundEvent.SendInfo(sessionId, "You drop ${moved.item.displayName}."))
                gmcpEmitter?.sendCharItemsRemove(sessionId, moved)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Use -> {
                val me = players.get(sessionId) ?: return
                when (val result = items.useItem(me.sessionId, cmd.keyword)) {
                    is ItemRegistry.UseResult.Used -> {
                        val effect = result.item.item.onUse
                        if (effect == null) {
                            outbound.send(OutboundEvent.SendError(sessionId, "${result.item.item.displayName} cannot be used."))
                            outbound.send(OutboundEvent.SendPrompt(sessionId))
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

                    is ItemRegistry.UseResult.NotFound -> {
                        outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying or wearing '${cmd.keyword}'."))
                    }

                    is ItemRegistry.UseResult.NotUsable -> {
                        outbound.send(OutboundEvent.SendError(sessionId, "${result.item.item.displayName} cannot be used."))
                    }

                    is ItemRegistry.UseResult.NoCharges -> {
                        outbound.send(
                            OutboundEvent.SendError(
                                sessionId,
                                "${result.item.item.displayName} has no charges remaining.",
                            ),
                        )
                    }
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Give -> {
                val me = players.get(sessionId) ?: return
                val targetSid = players.findSessionByName(cmd.playerName)
                if (targetSid == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.playerName}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                if (targetSid == sessionId) {
                    outbound.send(OutboundEvent.SendError(sessionId, "You cannot give items to yourself."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }

                val target = players.get(targetSid) ?: return
                if (target.roomId != me.roomId) {
                    outbound.send(OutboundEvent.SendError(sessionId, "${target.name} is not here."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
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

                    is ItemRegistry.GiveResult.NotFound -> {
                        outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying or wearing '${cmd.keyword}'."))
                    }
                }

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Kill -> {
                dialogueSystem?.endConversation(sessionId)
                val err = combat.startCombat(sessionId, cmd.target)
                if (err != null) {
                    outbound.send(OutboundEvent.SendError(sessionId, err))
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Cast -> {
                if (abilitySystem == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Abilities are not available."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val err = abilitySystem.cast(sessionId, cmd.spellName, cmd.target)
                if (err != null) {
                    outbound.send(OutboundEvent.SendError(sessionId, err))
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Spells -> {
                if (abilitySystem == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Abilities are not available."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val known = abilitySystem.knownAbilities(sessionId)
                if (known.isEmpty()) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "You don't know any spells yet."))
                } else {
                    val me = players.get(sessionId) ?: return
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Known spells (Mana: ${me.mana}/${me.maxMana}):"))
                    for (a in known) {
                        val remainingMs = abilitySystem.cooldownRemainingMs(sessionId, a.id)
                        val cdText =
                            if (remainingMs > 0) {
                                val remainingSec = ((remainingMs + 999) / 1000).coerceAtLeast(1)
                                "${remainingSec}s remaining"
                            } else if (a.cooldownMs > 0) {
                                "${a.cooldownMs / 1000}s cooldown"
                            } else {
                                "no cooldown"
                            }
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "  ${a.displayName}  — ${a.manaCost} mana, $cdText — ${a.description}",
                            ),
                        )
                    }
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Effects -> {
                if (statusEffects == null) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "No active effects."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val effects = statusEffects.activePlayerEffects(sessionId)
                if (effects.isEmpty()) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "No active effects."))
                } else {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Active effects:"))
                    for (e in effects) {
                        val remainingSec = ((e.remainingMs + 999) / 1000).coerceAtLeast(1)
                        val stacksText = if (e.stacks > 1) " (x${e.stacks})" else ""
                        outbound.send(
                            OutboundEvent.SendInfo(
                                sessionId,
                                "  ${e.name}$stacksText [${e.type}] — ${remainingSec}s remaining",
                            ),
                        )
                    }
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Dispel -> {
                if (!requireStaff(sessionId)) return
                if (statusEffects == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Status effects are not available."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                // Try player name first
                val targetSid = players.findSessionByName(cmd.target)
                if (targetSid != null) {
                    statusEffects.removeAllFromPlayer(targetSid)
                    val targetName = players.get(targetSid)?.name ?: cmd.target
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Dispelled all effects from $targetName."))
                    outbound.send(OutboundEvent.SendText(targetSid, "All your effects have been dispelled."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                // Try mob in room
                val me = players.get(sessionId) ?: return
                val mob = combat.findMobInRoom(me.roomId, cmd.target)
                if (mob != null) {
                    statusEffects.removeAllFromMob(mob.id)
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Dispelled all effects from ${mob.name}."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                outbound.send(OutboundEvent.SendError(sessionId, "No player or mob named '${cmd.target}'."))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Score -> {
                val me = players.get(sessionId) ?: return
                val equipped = items.equipment(sessionId)

                val attackBonus = equipped.values.sumOf { it.item.damage }
                val dmgMin = combat.minDamage + attackBonus
                val dmgMax = combat.maxDamage + attackBonus

                val xpLine =
                    run {
                        val into = progression.xpIntoLevel(me.xpTotal)
                        val span = progression.xpToNextLevel(me.xpTotal)
                        if (span == null) "MAXED" else "${"%,d".format(into)} / ${"%,d".format(span)}"
                    }

                val armorTotal = equipped.values.sumOf { it.item.armor }
                val armorDetail =
                    if (armorTotal > 0) {
                        val parts =
                            ItemSlot.entries
                                .filter { slot -> equipped[slot]?.item?.armor?.let { it > 0 } == true }
                                .joinToString(", ") { slot -> "${slot.label()}: ${equipped[slot]!!.item.displayName}" }
                        "+$armorTotal ($parts)"
                    } else {
                        "+0"
                    }

                val raceName =
                    dev.ambon.domain.Race
                        .fromString(me.race)
                        ?.displayName ?: me.race
                val className =
                    PlayerClass
                        .fromString(me.playerClass)
                        ?.displayName ?: me.playerClass

                outbound.send(OutboundEvent.SendInfo(sessionId, "[ ${me.name} — Level ${me.level} $raceName $className ]"))
                outbound.send(OutboundEvent.SendInfo(sessionId, "  HP  : ${me.hp} / ${me.maxHp}      XP : $xpLine"))
                outbound.send(OutboundEvent.SendInfo(sessionId, "  Mana: ${me.mana} / ${me.maxMana}"))
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  STR: ${formatStat(me.strength, equipped.values.sumOf { it.item.strength })}  " +
                            "DEX: ${formatStat(me.dexterity, equipped.values.sumOf { it.item.dexterity })}  " +
                            "CON: ${formatStat(me.constitution, equipped.values.sumOf { it.item.constitution })}",
                    ),
                )
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  INT: ${formatStat(me.intelligence, equipped.values.sumOf { it.item.intelligence })}  " +
                            "WIS: ${formatStat(me.wisdom, equipped.values.sumOf { it.item.wisdom })}  " +
                            "CHA: ${formatStat(me.charisma, equipped.values.sumOf { it.item.charisma })}",
                    ),
                )
                outbound.send(OutboundEvent.SendInfo(sessionId, "  Dmg : $dmgMin–$dmgMax          Armor: $armorDetail"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Flee -> {
                val err = combat.flee(sessionId)
                if (err != null) {
                    outbound.send(OutboundEvent.SendError(sessionId, err))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                }
            }

            is Command.Goto -> {
                if (!requireStaff(sessionId)) return
                val me = players.get(sessionId) ?: return
                val targetRoomId = resolveGotoArg(cmd.arg, me.roomId.zone)
                if (targetRoomId == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No such room: ${cmd.arg}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                if (!world.rooms.containsKey(targetRoomId)) {
                    // Room not in our world — try cross-zone goto
                    if (onCrossZoneMove != null) {
                        onCrossZoneMove.invoke(sessionId, targetRoomId)
                        return
                    }
                    outbound.send(OutboundEvent.SendError(sessionId, "No such room: ${cmd.arg}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                players.moveTo(sessionId, targetRoomId)
                sendLook(sessionId)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Transfer -> {
                if (!requireStaff(sessionId)) return
                val me = players.get(sessionId) ?: return
                val targetSid = players.findSessionByName(cmd.playerName)
                if (targetSid == null) {
                    // Not found locally — try remote transfer
                    if (interEngineBus != null) {
                        interEngineBus.broadcast(
                            InterEngineMessage.TransferRequest(
                                staffName = me.name,
                                targetPlayerName = cmd.playerName,
                                targetRoomId = cmd.arg,
                            ),
                        )
                        outbound.send(OutboundEvent.SendInfo(sessionId, "Transfer request sent to other engines."))
                    } else {
                        outbound.send(OutboundEvent.SendError(sessionId, "Player not found: ${cmd.playerName}"))
                    }
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val targetPlayer = players.get(targetSid) ?: return
                val targetRoomId = resolveGotoArg(cmd.arg, targetPlayer.roomId.zone)
                if (targetRoomId == null || !world.rooms.containsKey(targetRoomId)) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No such room: ${cmd.arg}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                players.moveTo(targetSid, targetRoomId)
                outbound.send(OutboundEvent.SendText(targetSid, "You are transported by a divine hand."))
                sendLook(targetSid)
                outbound.send(OutboundEvent.SendPrompt(targetSid))
                outbound.send(OutboundEvent.SendInfo(sessionId, "Transferred ${targetPlayer.name} to ${targetRoomId.value}."))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Spawn -> {
                if (!requireStaff(sessionId)) return
                val me = players.get(sessionId) ?: return
                val template = findMobTemplate(cmd.templateArg)
                if (template == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No mob template found: ${cmd.templateArg}"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val seq = ++adminSpawnSeq
                val zone = template.id.value.substringBefore(':', template.id.value)
                val local = template.id.value.substringAfter(':', template.id.value)
                val newMobId = MobId("$zone:${local}_adm_$seq")
                mobs.upsert(
                    MobState(
                        id = newMobId,
                        name = template.name,
                        roomId = me.roomId,
                        hp = template.maxHp,
                        maxHp = template.maxHp,
                        minDamage = template.minDamage,
                        maxDamage = template.maxDamage,
                        armor = template.armor,
                        xpReward = template.xpReward,
                        drops = template.drops,
                    ),
                )
                outbound.send(OutboundEvent.SendInfo(sessionId, "${template.name} appears."))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            Command.Shutdown -> {
                if (!requireStaff(sessionId)) return
                val me = players.get(sessionId) ?: return
                for (p in players.allPlayers()) {
                    outbound.send(
                        OutboundEvent.SendText(p.sessionId, "[SYSTEM] ${me.name} has initiated a server shutdown. Goodbye!"),
                    )
                }
                // Broadcast shutdown to other engines
                interEngineBus?.broadcast(
                    InterEngineMessage.GlobalBroadcast(
                        broadcastType = BroadcastType.SHUTDOWN,
                        senderName = me.name,
                        text = "${me.name} has initiated a server shutdown. Goodbye!",
                        sourceEngineId = engineId,
                    ),
                )
                onShutdown()
            }

            is Command.Smite -> {
                if (!requireStaff(sessionId)) return
                val me = players.get(sessionId) ?: return

                // Try player name first (online only, cannot smite self)
                val targetSid = players.findSessionByName(cmd.target)
                if (targetSid != null && targetSid != sessionId) {
                    val targetPlayer = players.get(targetSid) ?: return
                    combat.endCombatFor(targetSid)
                    targetPlayer.hp = 1
                    players.moveTo(targetSid, world.startRoom)
                    outbound.send(
                        OutboundEvent.SendText(targetSid, "A divine hand strikes you down. You awaken at the start, bruised and humbled."),
                    )
                    sendLook(targetSid)
                    outbound.send(OutboundEvent.SendPrompt(targetSid))
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Smote ${targetPlayer.name}."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }

                // Try mob in current room (partial name match)
                val targetMob = combat.findMobInRoom(me.roomId, cmd.target)
                if (targetMob == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No player or mob named '${cmd.target}'."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                combat.onMobRemovedExternally(targetMob.id)
                dialogueSystem?.onMobRemoved(targetMob.id)
                items.removeMobItems(targetMob.id)
                mobs.remove(targetMob.id)
                onMobSmited(targetMob.id)
                broadcastToRoom(players, outbound, me.roomId, "${targetMob.name} is struck down by divine wrath.")
                for (p in players.playersInRoom(me.roomId)) {
                    gmcpEmitter?.sendRoomRemoveMob(p.sessionId, targetMob.id.value)
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Kick -> {
                if (!requireStaff(sessionId)) return
                val targetSid = players.findSessionByName(cmd.playerName)
                if (targetSid == null) {
                    // Not found locally — try remote kick
                    if (interEngineBus != null) {
                        interEngineBus.broadcast(
                            InterEngineMessage.KickRequest(targetPlayerName = cmd.playerName),
                        )
                        outbound.send(OutboundEvent.SendInfo(sessionId, "Kick request sent to other engines."))
                    } else {
                        outbound.send(OutboundEvent.SendError(sessionId, "Player not found: ${cmd.playerName}"))
                    }
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                if (targetSid == sessionId) {
                    outbound.send(OutboundEvent.SendError(sessionId, "You cannot kick yourself."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                outbound.send(OutboundEvent.Close(targetSid, "Kicked by staff."))
                outbound.send(OutboundEvent.SendInfo(sessionId, "${cmd.playerName} has been kicked."))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Invalid -> {
                outbound.send(OutboundEvent.SendText(sessionId, "Invalid command: ${cmd.command}"))
                if (cmd.usage != null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "Usage: ${cmd.usage}"))
                } else {
                    outbound.send(OutboundEvent.SendText(sessionId, "Try 'help' for a list of commands."))
                }

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Balance -> {
                val me = players.get(sessionId) ?: return
                outbound.send(OutboundEvent.SendInfo(sessionId, "You have ${me.gold} gold."))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.ShopList -> {
                val me = players.get(sessionId) ?: return
                val shop = shopRegistry?.shopInRoom(me.roomId)
                if (shop == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val shopItems = shopRegistry.shopItems(shop)
                if (shopItems.isEmpty()) {
                    outbound.send(OutboundEvent.SendInfo(sessionId, "${shop.name} has nothing for sale."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val header = "[ ${shop.name} ]"
                outbound.send(OutboundEvent.SendInfo(sessionId, header))
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  %-30s %8s %8s".format("Item", "Buy", "Sell"),
                    ),
                )
                for ((_, item) in shopItems) {
                    val buyPrice = (item.basePrice * economyConfig.buyMultiplier).roundToInt()
                    val sellPrice = (item.basePrice * economyConfig.sellMultiplier).roundToInt()
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "  %-30s %5d gp %5d gp".format(item.displayName, buyPrice, sellPrice),
                        ),
                    )
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Buy -> {
                val me = players.get(sessionId) ?: return
                val shop = shopRegistry?.shopInRoom(me.roomId)
                if (shop == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val keyword = cmd.keyword.lowercase()
                val shopItems = shopRegistry.shopItems(shop)
                val match =
                    shopItems.firstOrNull { (_, item) ->
                        item.keyword.lowercase() == keyword ||
                            item.displayName.lowercase().contains(keyword)
                    }
                if (match == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "The shop doesn't sell '$keyword'."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val (itemId, item) = match
                val buyPrice = (item.basePrice * economyConfig.buyMultiplier).roundToInt().toLong()
                if (me.gold < buyPrice) {
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "You can't afford ${item.displayName} ($buyPrice gold).",
                        ),
                    )
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val newItem = items.createFromTemplate(itemId)
                if (newItem == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "That item is out of stock."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                me.gold -= buyPrice
                items.addToInventory(sessionId, newItem)
                markVitalsDirty(sessionId)
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "You buy ${item.displayName} for $buyPrice gold.",
                    ),
                )
                gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Sell -> {
                val me = players.get(sessionId) ?: return
                val shop = shopRegistry?.shopInRoom(me.roomId)
                if (shop == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val keyword = cmd.keyword
                val inv = items.inventory(sessionId)
                val lowerKeyword = keyword.lowercase()
                val invItem =
                    inv.firstOrNull { instance ->
                        val nameMatch = instance.item.displayName.contains(lowerKeyword, ignoreCase = true)
                        instance.item.keyword.equals(keyword, ignoreCase = true) || nameMatch
                    }
                if (invItem == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You don't have '$keyword'."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val sellPrice = (invItem.item.basePrice * economyConfig.sellMultiplier).roundToInt().toLong()
                if (sellPrice <= 0L) {
                    outbound.send(
                        OutboundEvent.SendText(sessionId, "${invItem.item.displayName} is worthless."),
                    )
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val removed = items.removeFromInventory(sessionId, invItem.item.keyword)
                if (removed == null) {
                    outbound.send(OutboundEvent.SendText(sessionId, "You don't have '$keyword'."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                me.gold += sellPrice
                markVitalsDirty(sessionId)
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "You sell ${removed.item.displayName} for $sellPrice gold.",
                    ),
                )
                gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Talk -> {
                if (dialogueSystem == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Nobody here wants to talk."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val err = dialogueSystem.startConversation(sessionId, cmd.target)
                if (err != null) {
                    outbound.send(OutboundEvent.SendError(sessionId, err))
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.DialogueChoice -> {
                if (dialogueSystem?.isInConversation(sessionId) != true) {
                    outbound.send(OutboundEvent.SendText(sessionId, "Huh?"))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                val err = dialogueSystem.selectChoice(sessionId, cmd.optionNumber)
                if (err != null) {
                    outbound.send(OutboundEvent.SendError(sessionId, err))
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Unknown -> {
                outbound.send(OutboundEvent.SendText(sessionId, "Huh?"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }
        }
    }

    private suspend fun requireStaff(sessionId: SessionId): Boolean {
        val me = players.get(sessionId) ?: return false
        if (!me.isStaff) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are not staff."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return false
        }
        return true
    }

    private fun resolveGotoArg(
        arg: String,
        currentZone: String,
    ): RoomId? =
        if (':' in arg) {
            val zone = arg.substringBefore(':').trim()
            val local = arg.substringAfter(':').trim()
            val effectiveZone = zone.ifEmpty { currentZone }
            if (local.isEmpty()) {
                // "zone:" or ":" — find start room for that zone
                if (world.startRoom.zone == effectiveZone) {
                    world.startRoom
                } else {
                    world.rooms.keys.firstOrNull { it.zone == effectiveZone }
                }
            } else {
                runCatching { RoomId("$effectiveZone:$local") }.getOrNull()
            }
        } else {
            runCatching { RoomId("$currentZone:$arg") }.getOrNull()
        }

    private fun findMobTemplate(arg: String): MobSpawn? {
        val trimmed = arg.trim()
        return if (':' in trimmed) {
            world.mobSpawns.firstOrNull { it.id.value.equals(trimmed, ignoreCase = true) }
        } else {
            val lowerLocal = trimmed.lowercase()
            world.mobSpawns.firstOrNull {
                it.id.value
                    .substringAfter(':', it.id.value)
                    .lowercase() == lowerLocal
            }
        }
    }

    private suspend fun grantScaledItemXp(
        sessionId: SessionId,
        rawXp: Long,
    ) {
        val scaledXp = progression.scaledXp(rawXp)
        if (scaledXp <= 0L) return

        val player = players.get(sessionId) ?: return
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

    private suspend fun sendLook(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        val roomId = me.roomId
        val room = world.rooms[roomId] ?: return

        outbound.send(OutboundEvent.SendText(sessionId, room.title))
        outbound.send(OutboundEvent.SendText(sessionId, room.description))

        val exits = if (room.exits.isEmpty()) "none" else room.exits.keys.joinToString(", ") { it.name.lowercase() }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Exits: $exits"))

        // Items
        val here = items.itemsInRoom(roomId)
        if (here.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Items here: none"))
        } else {
            val list = here.map { it.item.displayName }.sorted().joinToString(", ")
            outbound.send(OutboundEvent.SendInfo(sessionId, "Items here: $list"))
        }

        val roomPlayers =
            players
                .playersInRoom(roomId)
                .map { it.name }
                .sorted()

        val roomMobs =
            mobs
                .mobsInRoom(roomId)
                .map { it.name }
                .sorted()

        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                if (roomPlayers.isEmpty()) "Players here: none" else "Players here: ${roomPlayers.joinToString(", ")}",
            ),
        )

        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                if (roomMobs.isEmpty()) "You see: nothing" else "You see: ${roomMobs.joinToString(", ")}",
            ),
        )

        gmcpEmitter?.sendRoomInfo(sessionId, room)
        gmcpEmitter?.sendRoomPlayers(sessionId, players.playersInRoom(roomId).toList())
        gmcpEmitter?.sendRoomMobs(sessionId, mobs.mobsInRoom(roomId))
    }

    private fun currentRoomId(sessionId: SessionId): RoomId =
        requireNotNull(players.get(sessionId)) { "No player for sessionId=$sessionId" }.roomId

    private fun room(roomId: RoomId) = world.rooms.getValue(roomId)

    private fun formatStat(
        base: Int,
        equipBonus: Int,
    ): String = if (equipBonus > 0) "$base (+$equipBonus)" else "$base"

    private fun exitsLine(r: Room): String =
        if (r.exits.isEmpty()) {
            "Exits: none"
        } else {
            val names =
                r.exits.keys
                    .sortedBy { it.name } // stable order; adjust if you want N,E,S,W
                    .joinToString(", ") { it.name.lowercase() }
            "Exits: $names"
        }
}
