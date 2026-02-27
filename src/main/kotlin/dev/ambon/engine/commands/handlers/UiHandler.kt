package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.PhaseResult
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class UiHandler(
    router: CommandRouter,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val combat: CombatSystem? = null,
    private val onPhase: (suspend (SessionId, String?) -> PhaseResult)? = null,
) {
    init {
        router.on<Command.Noop> { sid, _ -> outbound.send(OutboundEvent.SendPrompt(sid)) }
        router.on<Command.Unknown> { sid, _ ->
            outbound.send(OutboundEvent.SendText(sid, "Huh?"))
            outbound.send(OutboundEvent.SendPrompt(sid))
        }
        router.on<Command.Invalid> { sid, cmd ->
            outbound.send(OutboundEvent.SendText(sid, "Invalid command: ${cmd.command}"))
            if (cmd.usage != null) {
                outbound.send(OutboundEvent.SendText(sid, "Usage: ${cmd.usage}"))
            } else {
                outbound.send(OutboundEvent.SendText(sid, "Try 'help' for a list of commands."))
            }
            outbound.send(OutboundEvent.SendPrompt(sid))
        }
        router.on<Command.Help> { sid, _ -> handleHelp(sid) }
        router.on<Command.Quit> { sid, _ -> outbound.send(OutboundEvent.Close(sid, "Goodbye!")) }
        router.on<Command.AnsiOn> { sid, _ -> handleAnsiOn(sid) }
        router.on<Command.AnsiOff> { sid, _ -> handleAnsiOff(sid) }
        router.on<Command.Clear> { sid, _ ->
            outbound.send(OutboundEvent.ClearScreen(sid))
            outbound.send(OutboundEvent.SendPrompt(sid))
        }
        router.on<Command.Colors> { sid, _ ->
            outbound.send(OutboundEvent.ShowAnsiDemo(sid))
            outbound.send(OutboundEvent.SendPrompt(sid))
        }
        router.on<Command.Phase> { sid, cmd -> handlePhase(sid, cmd) }
    }

    private suspend fun handleHelp(sessionId: SessionId) {
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
                    spells/abilities/skills
                    effects/buffs/debuffs
                    score/sc
                    gold/balance
                    list/shop
                    buy <item>
                    sell <item>
                    quest log/list
                    quest info <name>
                    quest abandon <name>
                    accept <quest>
                    achievements/ach
                    group invite <player>
                    group accept
                    group leave
                    group kick <player>
                    group list (or just 'group')
                    gtell/gt <message>
                    title <titleName>
                    title clear
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

    private suspend fun handleAnsiOn(sessionId: SessionId) {
        outbound.send(OutboundEvent.SetAnsi(sessionId, true))
        players.setAnsiEnabled(sessionId, true)
        outbound.send(OutboundEvent.SendInfo(sessionId, "ANSI enabled"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleAnsiOff(sessionId: SessionId) {
        outbound.send(OutboundEvent.SetAnsi(sessionId, false))
        players.setAnsiEnabled(sessionId, false)
        outbound.send(OutboundEvent.SendInfo(sessionId, "ANSI disabled"))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handlePhase(
        sessionId: SessionId,
        cmd: Command.Phase,
    ) {
        if (combat?.isInCombat(sessionId) == true) {
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
}
