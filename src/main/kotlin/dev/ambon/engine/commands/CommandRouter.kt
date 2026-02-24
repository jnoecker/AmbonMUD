package dev.ambon.engine.commands

import dev.ambon.bus.OutboundBus
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
import dev.ambon.engine.PlayerState
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerLocationIndex

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
                            kill <mob>
                            flee
                            cast/c <spell> [target]
                            spells/abilities
                            score/sc
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
                    }
                    players.moveTo(sessionId, to)
                    val newMembers = players.playersInRoom(to).filter { it.sessionId != me.sessionId }
                    for (other in newMembers) {
                        outbound.send(OutboundEvent.SendText(other.sessionId, "${me.name} enters."))
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
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Shout -> {
                val me = players.get(sessionId) ?: return
                val zone = me.roomId.zone
                outbound.send(OutboundEvent.SendText(sessionId, "You shout: ${cmd.message}"))
                for (p in players.playersInZone(zone)) {
                    if (p.sessionId == sessionId) continue
                    outbound.send(OutboundEvent.SendText(p.sessionId, "[SHOUT] ${me.name}: ${cmd.message}"))
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
                    }

                    is ItemRegistry.GiveResult.NotFound -> {
                        outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying or wearing '${cmd.keyword}'."))
                    }
                }

                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }

            is Command.Kill -> {
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
                val lowerTarget = cmd.target.lowercase()
                val targetMob = mobs.mobsInRoom(me.roomId).firstOrNull { it.name.lowercase().contains(lowerTarget) }
                if (targetMob == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No player or mob named '${cmd.target}'."))
                    outbound.send(OutboundEvent.SendPrompt(sessionId))
                    return
                }
                combat.onMobRemovedExternally(targetMob.id)
                items.removeMobItems(targetMob.id)
                mobs.remove(targetMob.id)
                onMobSmited(targetMob.id)
                broadcastToRoom(me.roomId, "${targetMob.name} is struck down by divine wrath.")
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

    private suspend fun broadcastToRoom(
        roomId: RoomId,
        text: String,
        exclude: SessionId? = null,
    ) {
        for (p in players.playersInRoom(roomId)) {
            if (exclude != null && p.sessionId == exclude) continue
            outbound.send(OutboundEvent.SendText(p.sessionId, text))
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
        val totalCha = player.charisma + equipCha
        val chaBonus = totalCha - PlayerState.BASE_STAT
        val adjustedXp =
            if (chaBonus > 0) {
                val multiplier = 1.0 + chaBonus * 0.005
                (scaledXp * multiplier).toLong().coerceAtLeast(scaledXp)
            } else {
                scaledXp
            }

        val result = players.grantXp(sessionId, adjustedXp, progression) ?: return
        metrics.onXpAwarded(adjustedXp, "item_use")
        outbound.send(OutboundEvent.SendInfo(sessionId, "You gain $adjustedXp XP."))

        if (result.levelsGained <= 0) return
        metrics.onLevelUp()

        val pc = PlayerClass.fromString(player.playerClass)
        val classHpPerLevel = pc?.hpPerLevel ?: progression.hpPerLevel
        val classManaPerLevel = pc?.manaPerLevel ?: progression.manaPerLevel
        val oldMaxHp = progression.maxHpForLevel(result.previousLevel, player.constitution, classHpPerLevel)
        val newMaxHp = progression.maxHpForLevel(result.newLevel, player.constitution, classHpPerLevel)
        val hpGain = (newMaxHp - oldMaxHp).coerceAtLeast(0)
        val oldMaxMana = progression.maxManaForLevel(result.previousLevel, player.intelligence, classManaPerLevel)
        val newMaxMana = progression.maxManaForLevel(result.newLevel, player.intelligence, classManaPerLevel)
        val manaGain = (newMaxMana - oldMaxMana).coerceAtLeast(0)
        val bonusParts = mutableListOf<String>()
        if (hpGain > 0) bonusParts += "+$hpGain max HP"
        if (manaGain > 0) bonusParts += "+$manaGain max Mana"

        val levelUpMessage =
            if (bonusParts.isNotEmpty()) {
                "You reached level ${result.newLevel}! (${bonusParts.joinToString(", ")})"
            } else {
                "You reached level ${result.newLevel}!"
            }
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
