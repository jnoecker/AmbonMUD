package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GuildHallSystem
import dev.ambon.engine.GuildSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class GuildHandler(
    private val ctx: EngineContext,
    private val guildSystem: GuildSystem? = null,
    private val guildHallSystem: GuildHallSystem? = null,
) : CommandHandler {
    private val outbound = ctx.outbound
    private val players = ctx.players
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Guild.Create> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Disband> { sid, _ -> handleGuildCmd(sid, Command.Guild.Disband) }
        router.on<Command.Guild.Invite> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Accept> { sid, _ -> handleGuildCmd(sid, Command.Guild.Accept) }
        router.on<Command.Guild.Decline> { sid, _ -> handleGuildCmd(sid, Command.Guild.Decline) }
        router.on<Command.Guild.Leave> { sid, _ -> handleGuildCmd(sid, Command.Guild.Leave) }
        router.on<Command.Guild.Kick> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Promote> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Demote> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Motd> { sid, cmd -> handleGuildCmd(sid, cmd) }
        router.on<Command.Guild.Roster> { sid, _ -> handleGuildCmd(sid, Command.Guild.Roster) }
        router.on<Command.Guild.Info> { sid, _ -> handleGuildCmd(sid, Command.Guild.Info) }
        router.on<Command.Guild.Hall> { sid, _ -> handleHallInfo(sid) }
        router.on<Command.Guild.HallBuy> { sid, _ -> handleHallBuy(sid) }
        router.on<Command.Guild.HallExpand> { sid, cmd -> handleHallExpand(sid, cmd) }
        router.on<Command.Guild.HallEnter> { sid, _ -> handleHallEnter(sid) }
        router.on<Command.Guild.HallLeave> { sid, _ -> handleHallLeave(sid) }
        router.on<Command.Gchat> { sid, cmd -> handleGchat(sid, cmd) }
    }

    private suspend fun handleGuildCmd(
        sessionId: SessionId,
        cmd: Command.Guild,
    ) {
        // Read-only commands (Info, Roster) are allowed for demo characters;
        // anything that mutates persistent state requires a real account.
        if (cmd !is Command.Guild.Info && cmd !is Command.Guild.Roster) {
            if (!requireClaimed(sessionId, players, outbound, "Guilds")) return
        }
        val gs = requireSystemOrNull(sessionId, guildSystem, "Guilds", outbound) ?: return
        val err =
            when (cmd) {
                is Command.Guild.Create -> gs.create(sessionId, cmd.name, cmd.tag)
                Command.Guild.Disband -> gs.disband(sessionId)
                is Command.Guild.Invite -> gs.invite(sessionId, cmd.target)
                Command.Guild.Accept -> gs.accept(sessionId)
                Command.Guild.Decline -> gs.decline(sessionId)
                Command.Guild.Leave -> gs.leave(sessionId)
                is Command.Guild.Kick -> gs.kick(sessionId, cmd.target)
                is Command.Guild.Promote -> gs.promote(sessionId, cmd.target)
                is Command.Guild.Demote -> gs.demote(sessionId, cmd.target)
                is Command.Guild.Motd -> gs.setMotd(sessionId, cmd.message)
                Command.Guild.Roster -> gs.roster(sessionId)
                Command.Guild.Info -> gs.info(sessionId)
                // Hall commands handled separately
                Command.Guild.Hall,
                Command.Guild.HallBuy,
                is Command.Guild.HallExpand,
                Command.Guild.HallEnter,
                Command.Guild.HallLeave,
                -> null
            }
        outbound.sendIfError(sessionId, err)
    }

    private suspend fun handleHallInfo(sessionId: SessionId) {
        val hs = requireSystemOrNull(sessionId, guildHallSystem, "Guild halls", outbound) ?: return
        outbound.sendIfError(sessionId, hs.showHallInfo(sessionId))
    }

    private suspend fun handleHallBuy(sessionId: SessionId) {
        val hs = requireSystemOrNull(sessionId, guildHallSystem, "Guild halls", outbound) ?: return
        outbound.sendIfError(sessionId, hs.buyHall(sessionId))
    }

    private suspend fun handleHallExpand(sessionId: SessionId, cmd: Command.Guild.HallExpand) {
        val hs = requireSystemOrNull(sessionId, guildHallSystem, "Guild halls", outbound) ?: return
        outbound.sendIfError(sessionId, hs.expandHall(sessionId, cmd.template))
    }

    private suspend fun handleHallEnter(sessionId: SessionId) {
        val hs = requireSystemOrNull(sessionId, guildHallSystem, "Guild halls", outbound) ?: return
        val me = players.get(sessionId) ?: return
        val guildId = me.guildId
        if (guildId == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are not in a guild."))
            return
        }
        val err = hs.enterHall(sessionId)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
            return
        }
        val entryRoomId = hs.hallEntryRoomId(guildId)
        movePlayerWithNotify(
            sessionId = sessionId,
            from = me.roomId,
            to = entryRoomId,
            departMsg = "vanishes in a shimmer of guild magic.",
            arriveMsg = "appears in a shimmer of guild magic.",
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
        )
        outbound.send(OutboundEvent.SendInfo(sessionId, "You step into the guild hall."))
        ctx.sendLook(sessionId)
    }

    private suspend fun handleHallLeave(sessionId: SessionId) {
        val hs = requireSystemOrNull(sessionId, guildHallSystem, "Guild halls", outbound) ?: return
        val me = players.get(sessionId) ?: return
        val from = me.roomId
        val origin = hs.resolveHallExit(sessionId)
        val dest = origin ?: me.recallRoomId ?: ctx.world.startRoom

        movePlayerWithNotify(
            sessionId = sessionId,
            from = from,
            to = dest,
            departMsg = "vanishes in a shimmer of guild magic.",
            arriveMsg = "appears in a shimmer of guild magic.",
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
        )
        outbound.send(OutboundEvent.SendInfo(sessionId, "You leave the guild hall and find yourself back where you came from."))
        ctx.sendLook(sessionId)
    }

    private suspend fun handleGchat(
        sessionId: SessionId,
        cmd: Command.Gchat,
    ) {
        val gs = requireSystemOrNull(sessionId, guildSystem, "Guilds", outbound) ?: return
        outbound.sendIfError(sessionId, gs.gchat(sessionId, cmd.message))
    }
}
