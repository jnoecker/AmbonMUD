package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GuildSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on

class GuildHandler(
    ctx: EngineContext,
    private val guildSystem: GuildSystem? = null,
) : CommandHandler {
    private val outbound = ctx.outbound

    override fun register(router: CommandRouter) {
        router.on<Command.Guild.Create> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Disband> { sid, _ -> handleGuildCmd(sid, Command.Guild.Disband) }
        router.on<Command.Guild.Invite> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Accept> { sid, _ -> handleGuildCmd(sid, Command.Guild.Accept) }
        router.on<Command.Guild.Leave> { sid, _ -> handleGuildCmd(sid, Command.Guild.Leave) }
        router.on<Command.Guild.Kick> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Promote> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Demote> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Motd> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Roster> { sid, _ -> handleGuildCmd(sid, Command.Guild.Roster) }
        router.on<Command.Guild.Info> { sid, _ -> handleGuildCmd(sid, Command.Guild.Info) }
        router.on<Command.Gchat> { sid, cmd -> handleGchat(sid, cmd) }
    }

    private suspend fun handleGuildCmd(
        sessionId: SessionId,
        cmd: Command.Guild,
    ) {
        if (!requireSystem(sessionId, guildSystem != null, "Guilds", outbound)) return
        val gs = guildSystem!!
        val err =
            when (cmd) {
                is Command.Guild.Create -> gs.create(sessionId, cmd.name, cmd.tag)
                Command.Guild.Disband -> gs.disband(sessionId)
                is Command.Guild.Invite -> gs.invite(sessionId, cmd.target)
                Command.Guild.Accept -> gs.accept(sessionId)
                Command.Guild.Leave -> gs.leave(sessionId)
                is Command.Guild.Kick -> gs.kick(sessionId, cmd.target)
                is Command.Guild.Promote -> gs.promote(sessionId, cmd.target)
                is Command.Guild.Demote -> gs.demote(sessionId, cmd.target)
                is Command.Guild.Motd -> gs.setMotd(sessionId, cmd.message)
                Command.Guild.Roster -> gs.roster(sessionId)
                Command.Guild.Info -> gs.info(sessionId)
            }
        outbound.sendIfError(sessionId, err)
    }

    private suspend fun handleGchat(
        sessionId: SessionId,
        cmd: Command.Gchat,
    ) {
        if (!requireSystem(sessionId, guildSystem != null, "Guilds", outbound)) return
        outbound.sendIfError(sessionId, guildSystem!!.gchat(sessionId, cmd.message))
    }
}
