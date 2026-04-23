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
    ctx: EngineContext,
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
        router.on<Command.ScreenReaderOn> { sid, _ -> handleScreenReaderToggle(sid, true) }
        router.on<Command.ScreenReaderOff> { sid, _ -> handleScreenReaderToggle(sid, false) }
        router.on<Command.AutolootOn> { sid, _ -> handleAutoloot(sid, AutolootAction.ON) }
        router.on<Command.AutolootOff> { sid, _ -> handleAutoloot(sid, AutolootAction.OFF) }
        router.on<Command.AutolootStatus> { sid, _ -> handleAutoloot(sid, AutolootAction.STATUS) }
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

    private enum class AutolootAction { ON, OFF, STATUS }

    private suspend fun handleAutoloot(
        sessionId: SessionId,
        action: AutolootAction,
    ) {
        val me = players.get(sessionId) ?: return
        when (action) {
            AutolootAction.ON -> {
                players.setAutolootEnabled(sessionId, true)
                players.get(sessionId)?.let { gmcpEmitter?.sendCharName(sessionId, it) }
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-loot enabled."))
            }
            AutolootAction.OFF -> {
                players.setAutolootEnabled(sessionId, false)
                players.get(sessionId)?.let { gmcpEmitter?.sendCharName(sessionId, it) }
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-loot disabled."))
            }
            AutolootAction.STATUS -> {
                val status = if (me.autolootEnabled) "ON" else "OFF"
                outbound.send(OutboundEvent.SendInfo(sessionId, "Auto-loot is $status."))
            }
        }
    }

    private suspend fun handleScreenReaderToggle(sessionId: SessionId, requestedOn: Boolean) {
        val me = players.get(sessionId) ?: return
        // "screenreader" (bare) maps to ScreenReaderOn; if already on, toggle off
        val enabled = if (requestedOn) !me.screenReaderEnabled else false
        outbound.send(OutboundEvent.SetScreenReader(sessionId, enabled))
        players.setScreenReaderEnabled(sessionId, enabled)
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
