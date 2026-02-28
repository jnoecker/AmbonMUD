package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GuildSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

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
        if (guildSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Guilds are not available."))
            return
        }
        val err =
            when (cmd) {
                is Command.Guild.Create -> guildSystem.create(sessionId, cmd.name, cmd.tag)
                Command.Guild.Disband -> guildSystem.disband(sessionId)
                is Command.Guild.Invite -> guildSystem.invite(sessionId, cmd.target)
                Command.Guild.Accept -> guildSystem.accept(sessionId)
                Command.Guild.Leave -> guildSystem.leave(sessionId)
                is Command.Guild.Kick -> guildSystem.kick(sessionId, cmd.target)
                is Command.Guild.Promote -> guildSystem.promote(sessionId, cmd.target)
                is Command.Guild.Demote -> guildSystem.demote(sessionId, cmd.target)
                is Command.Guild.Motd -> guildSystem.setMotd(sessionId, cmd.message)
                Command.Guild.Roster -> guildSystem.roster(sessionId)
                Command.Guild.Info -> guildSystem.info(sessionId)
            }
        outbound.sendIfError(sessionId, err)
    }

    private suspend fun handleGchat(
        sessionId: SessionId,
        cmd: Command.Gchat,
    ) {
        if (guildSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Guilds are not available."))
            return
        }
        outbound.sendIfError(sessionId, guildSystem.gchat(sessionId, cmd.message))
    }
}
