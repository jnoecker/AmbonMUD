package dev.ambon.engine.commands.handlers

import dev.ambon.config.CommandsConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.ZoneInstanceEntry
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.PhaseResult
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class UiHandler(
    private val ctx: EngineContext,
    private val onPhase: (suspend (SessionId, String?) -> PhaseResult)? = null,
    private val commandsConfig: CommandsConfig = CommandsConfig(),
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val combat = ctx.combat
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Noop> { _, _ -> }
        router.on<Command.Unknown> { sid, cmd ->
            outbound.send(OutboundEvent.SendError(sid, "Huh?"))
            sendTypedInputFeedback(
                sessionId = sid,
                message = "That typed input isn't recognized here. Use the on-screen controls when available.",
                code = "UNRECOGNIZED_TYPED_INPUT",
                command = extractCommandKeyword(cmd.raw),
            )
        }
        router.on<Command.Invalid> { sid, cmd ->
            outbound.send(OutboundEvent.SendError(sid, "Invalid command: ${cmd.command}"))
            if (cmd.usage != null) {
                outbound.send(OutboundEvent.SendError(sid, "Usage: ${cmd.usage}"))
            } else {
                outbound.send(OutboundEvent.SendError(sid, "Try 'help' for a list of commands."))
            }
            sendTypedInputFeedback(
                sessionId = sid,
                message = "That typed input isn't supported in this format. Use the on-screen controls when available.",
                code = "UNSUPPORTED_TYPED_INPUT",
                command = extractCommandKeyword(cmd.command),
            )
        }
        router.on<Command.Help> { sid, _ -> handleHelp(sid) }
        router.on<Command.Quit> { sid, _ -> outbound.send(OutboundEvent.Close(sid, "Goodbye!")) }
        router.on<Command.AnsiOn> { sid, _ -> handleAnsiOn(sid) }
        router.on<Command.AnsiOff> { sid, _ -> handleAnsiOff(sid) }
        router.on<Command.ScreenReaderOn> { sid, _ -> handleScreenReader(sid) { true } }
        router.on<Command.ScreenReaderOff> { sid, _ -> handleScreenReader(sid) { false } }
        router.on<Command.ScreenReaderToggle> { sid, _ -> handleScreenReader(sid) { current -> !current } }
        router.on<Command.AutolootOn> { sid, _ -> handleAutoloot(sid, ToggleAction.ON) }
        router.on<Command.AutolootOff> { sid, _ -> handleAutoloot(sid, ToggleAction.OFF) }
        router.on<Command.AutolootStatus> { sid, _ -> handleAutoloot(sid, ToggleAction.STATUS) }
        router.on<Command.AutopeekOn> { sid, _ -> handleAutopeek(sid, ToggleAction.ON) }
        router.on<Command.AutopeekOff> { sid, _ -> handleAutopeek(sid, ToggleAction.OFF) }
        router.on<Command.AutopeekStatus> { sid, _ -> handleAutopeek(sid, ToggleAction.STATUS) }
        router.on<Command.Wimpy> { sid, cmd -> handleWimpy(sid, cmd) }
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

    private companion object {
        const val DEFAULT_WIMPY_PCT = 10
        const val MAX_WIMPY_PCT = 95
    }

    private enum class ToggleAction { ON, OFF, STATUS }

    private suspend fun handleAutoloot(
        sessionId: SessionId,
        action: ToggleAction,
    ) {
        val me = players.get(sessionId) ?: return
        when (action) {
            ToggleAction.ON -> {
                players.setAutolootEnabled(sessionId, true)
                gmcpEmitter?.sendCharName(sessionId, me)
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-loot enabled."))
            }
            ToggleAction.OFF -> {
                players.setAutolootEnabled(sessionId, false)
                gmcpEmitter?.sendCharName(sessionId, me)
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-loot disabled."))
            }
            ToggleAction.STATUS -> {
                val status = if (me.autolootEnabled) "ON" else "OFF"
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-loot is $status."))
            }
        }
    }

    private suspend fun handleAutopeek(
        sessionId: SessionId,
        action: ToggleAction,
    ) {
        val me = players.get(sessionId) ?: return
        when (action) {
            ToggleAction.ON -> {
                players.setAutopeekEnabled(sessionId, true)
                gmcpEmitter?.sendCharName(sessionId, me)
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-peek enabled."))
                ctx.sendLook(sessionId)
            }
            ToggleAction.OFF -> {
                players.setAutopeekEnabled(sessionId, false)
                gmcpEmitter?.sendCharName(sessionId, me)
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-peek disabled."))
                ctx.sendLook(sessionId)
            }
            ToggleAction.STATUS -> {
                val status = if (me.autopeekEnabled) "ON" else "OFF"
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-peek is $status."))
            }
        }
    }

    private suspend fun handleWimpy(
        sessionId: SessionId,
        cmd: Command.Wimpy,
    ) {
        val me = players.get(sessionId) ?: return
        val rawArg = cmd.arg
        if (rawArg.isNullOrBlank()) {
            val status = if (me.wimpyThresholdPct <= 0) "OFF" else "${me.wimpyThresholdPct}%"
            outbound.send(OutboundEvent.SendInfo(sessionId, "Wimpy: $status (auto-flee threshold)."))
            return
        }
        val arg = rawArg.trim().lowercase()
        val newPct = when (arg) {
            "off", "0", "no", "disable" -> 0
            "on", "default" -> DEFAULT_WIMPY_PCT
            else -> arg.toIntOrNull()
        }
        if (newPct == null || newPct !in 0..MAX_WIMPY_PCT) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Usage: wimpy [off | 0-$MAX_WIMPY_PCT]",
                ),
            )
            return
        }
        players.setWimpyThresholdPct(sessionId, newPct)
        gmcpEmitter?.sendCharName(sessionId, me)
        val msg =
            if (newPct == 0) {
                "Wimpy disabled — you will not auto-flee."
            } else {
                "Wimpy set to $newPct% — combat will break when your HP drops below that."
            }
        outbound.send(OutboundEvent.SendInfo(sessionId, msg))
    }

    /**
     * `screenreader on`/`off` set the mode idempotently (so UI switches can
     * drive it deterministically); bare `screenreader` toggles. The new state
     * rides the Char.Name GMCP packet so the web client mirrors it.
     */
    private suspend fun handleScreenReader(
        sessionId: SessionId,
        resolve: (current: Boolean) -> Boolean,
    ) {
        val me = players.get(sessionId) ?: return
        val enabled = resolve(me.screenReaderEnabled)
        outbound.send(OutboundEvent.SetScreenReader(sessionId, enabled))
        players.setScreenReaderEnabled(sessionId, enabled)
        gmcpEmitter?.sendCharName(sessionId, me)
        val status = if (enabled) "enabled" else "disabled"
        outbound.send(OutboundEvent.SendInfo(sessionId, "Screen reader mode $status."))
    }

    private suspend fun handlePhase(
        sessionId: SessionId,
        cmd: Command.Phase,
    ) {
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You can't switch layers while in combat!"))
            return
        }
        if (onPhase == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Layering is not enabled on this server."))
            return
        }
        when (val result = onPhase.invoke(sessionId, cmd.targetHint)) {
            is PhaseResult.NotEnabled -> {
                outbound.send(OutboundEvent.SendError(sessionId, "Layering is not enabled on this server."))
                gmcpEmitter?.clearZoneInstances(sessionId)
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
                gmcpEmitter?.sendZoneInstances(
                    sessionId,
                    zone = result.instances.firstOrNull()?.zone ?: "",
                    currentEngineId = result.currentEngineId,
                    instances = result.instances.map { i ->
                        ZoneInstanceEntry(
                            engineId = i.engineId,
                            playerCount = i.playerCount,
                            capacity = i.capacity,
                        )
                    },
                )
            }
            is PhaseResult.Initiated -> {
                // Handoff message already sent by HandoffManager
            }
            is PhaseResult.NoOp -> {
                outbound.send(OutboundEvent.SendText(sessionId, result.reason))
            }
        }
    }

    private suspend fun sendTypedInputFeedback(
        sessionId: SessionId,
        message: String,
        code: String,
        command: String?,
    ) {
        gmcpEmitter?.sendUiFeedback(
            sessionId = sessionId,
            type = "error",
            message = message,
            code = code,
            scope = "input",
            command = command,
        )
    }

    private fun extractCommandKeyword(raw: String): String? = raw.trim().substringBefore(' ').ifBlank { null }
}
