package dev.ambon.engine.commands.handlers

import dev.ambon.config.CommandsConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.PhaseResult
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class UiHandler(
    ctx: EngineContext,
    private val onPhase: (suspend (SessionId, String?) -> PhaseResult)? = null,
    private val commandsConfig: CommandsConfig = CommandsConfig(),
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val combat = ctx.combat

    override fun register(router: CommandRouter) {
        router.on<Command.Noop> { _, _ -> }
        router.on<Command.Unknown> { sid, _ ->
            outbound.send(OutboundEvent.SendText(sid, "Huh?"))
        }
        router.on<Command.Invalid> { sid, cmd ->
            outbound.send(OutboundEvent.SendText(sid, "Invalid command: ${cmd.command}"))
            if (cmd.usage != null) {
                outbound.send(OutboundEvent.SendText(sid, "Usage: ${cmd.usage}"))
            } else {
                outbound.send(OutboundEvent.SendText(sid, "Try 'help' for a list of commands."))
            }
        }
        router.on<Command.Help> { sid, _ -> handleHelp(sid) }
        router.on<Command.Quit> { sid, _ -> outbound.send(OutboundEvent.Close(sid, "Goodbye!")) }
        router.on<Command.AnsiOn> { sid, _ -> handleAnsiOn(sid) }
        router.on<Command.AnsiOff> { sid, _ -> handleAnsiOff(sid) }
        router.on<Command.Clear> { sid, _ ->
            outbound.send(OutboundEvent.ClearScreen(sid))
        }
        router.on<Command.Colors> { sid, _ ->
            outbound.send(OutboundEvent.ShowAnsiDemo(sid))
        }
        router.on<Command.Phase> { sid, cmd -> handlePhase(sid, cmd) }
    }

    private suspend fun handleHelp(sessionId: SessionId) {
        val isStaff = players.get(sessionId)?.isStaff == true
        outbound.send(
            OutboundEvent.SendInfo(sessionId, commandsConfig.generateHelp(isStaff)),
        )
    }

    private suspend fun handleAnsiOn(sessionId: SessionId) {
        outbound.send(OutboundEvent.SetAnsi(sessionId, true))
        players.setAnsiEnabled(sessionId, true)
        outbound.send(OutboundEvent.SendInfo(sessionId, "ANSI enabled"))
    }

    private suspend fun handleAnsiOff(sessionId: SessionId) {
        outbound.send(OutboundEvent.SetAnsi(sessionId, false))
        players.setAnsiEnabled(sessionId, false)
        outbound.send(OutboundEvent.SendInfo(sessionId, "ANSI disabled"))
    }

    private suspend fun handlePhase(
        sessionId: SessionId,
        cmd: Command.Phase,
    ) {
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendText(sessionId, "You can't switch layers while in combat!"))
            return
        }
        if (onPhase == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Layering is not enabled on this server."))
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
    }
}
