package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class GroupHandler(
    router: CommandRouter,
    ctx: EngineContext,
    private val groupSystem: GroupSystem? = null,
) {
    private val outbound = ctx.outbound

    init {
        router.on<Command.GroupCmd.Invite> { sid, cmd -> handleGroupCmd(sid, cmd) }
        router.on<Command.GroupCmd.Accept> { sid, _ -> handleGroupCmd(sid, Command.GroupCmd.Accept) }
        router.on<Command.GroupCmd.Leave> { sid, _ -> handleGroupCmd(sid, Command.GroupCmd.Leave) }
        router.on<Command.GroupCmd.Kick> { sid, cmd -> handleGroupCmd(sid, cmd) }
        router.on<Command.GroupCmd.List> { sid, _ -> handleGroupCmd(sid, Command.GroupCmd.List) }
        router.on<Command.Gtell> { sid, cmd -> handleGtell(sid, cmd) }
    }

    private suspend fun handleGroupCmd(
        sessionId: SessionId,
        cmd: Command.GroupCmd,
    ) {
        if (groupSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Groups are not available."))
            return
        }
        val err =
            when (cmd) {
                is Command.GroupCmd.Invite -> groupSystem.invite(sessionId, cmd.target)
                Command.GroupCmd.Accept -> groupSystem.accept(sessionId)
                Command.GroupCmd.Leave -> groupSystem.leave(sessionId)
                is Command.GroupCmd.Kick -> groupSystem.kick(sessionId, cmd.target)
                Command.GroupCmd.List -> groupSystem.list(sessionId)
            }
        outbound.sendIfError(sessionId, err)
    }

    private suspend fun handleGtell(
        sessionId: SessionId,
        cmd: Command.Gtell,
    ) {
        if (groupSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Groups are not available."))
            return
        }
        outbound.sendIfError(sessionId, groupSystem.gtell(sessionId, cmd.message))
    }
}
