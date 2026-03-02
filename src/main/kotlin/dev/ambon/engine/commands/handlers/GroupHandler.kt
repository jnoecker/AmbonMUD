package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on

class GroupHandler(
    ctx: EngineContext,
    private val groupSystem: GroupSystem? = null,
) : CommandHandler {
    private val outbound = ctx.outbound

    override fun register(router: CommandRouter) {
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
        if (!requireSystem(sessionId, groupSystem != null, "Groups", outbound)) return
        val gs = groupSystem!!
        val err =
            when (cmd) {
                is Command.GroupCmd.Invite -> gs.invite(sessionId, cmd.target)
                Command.GroupCmd.Accept -> gs.accept(sessionId)
                Command.GroupCmd.Leave -> gs.leave(sessionId)
                is Command.GroupCmd.Kick -> gs.kick(sessionId, cmd.target)
                Command.GroupCmd.List -> gs.list(sessionId)
            }
        outbound.sendIfError(sessionId, err)
    }

    private suspend fun handleGtell(
        sessionId: SessionId,
        cmd: Command.Gtell,
    ) {
        if (!requireSystem(sessionId, groupSystem != null, "Groups", outbound)) return
        outbound.sendIfError(sessionId, groupSystem!!.gtell(sessionId, cmd.message))
    }
}
