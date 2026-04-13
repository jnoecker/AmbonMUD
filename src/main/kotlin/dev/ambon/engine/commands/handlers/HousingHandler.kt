package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class HousingHandler(
    private val ctx: EngineContext,
    private val housingSystem: HousingSystem? = null,
) : CommandHandler {
    private val outbound = ctx.outbound
    private val players = ctx.players
    private val world = ctx.world
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.House.Status> { sid, _ -> handleStatus(sid) }
        router.on<Command.House.ListTemplates> { sid, _ -> handleListTemplates(sid) }
        router.on<Command.House.Buy> { sid, _ -> handleBuy(sid) }
        router.on<Command.House.Expand> { sid, cmd -> handleExpand(sid, cmd) }
        router.on<Command.House.SetTitle> { sid, cmd -> handleSetTitle(sid, cmd) }
        router.on<Command.House.SetDescription> { sid, cmd -> handleSetDescription(sid, cmd) }
        router.on<Command.House.Invite> { sid, cmd -> handleInvite(sid, cmd) }
        router.on<Command.House.Kick> { sid, cmd -> handleKick(sid, cmd) }
        router.on<Command.House.Guests> { sid, _ -> handleGuests(sid) }
    }

    private suspend fun handleStatus(sessionId: SessionId) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        val status = hs.houseStatus(sessionId)
        if (status == null) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You don't own a house. Visit a housing broker to purchase one."))
            return
        }
        outbound.send(OutboundEvent.SendText(sessionId, "${status.ownerName}'s House"))
        outbound.send(OutboundEvent.SendInfo(sessionId, "Rooms (${status.rooms.size}):"))
        for (room in status.rooms) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  ${room.title} [${room.templateId}]"))
        }
    }

    private suspend fun requireBroker(sessionId: SessionId): Boolean {
        val me = players.get(sessionId) ?: return false
        val room = world.rooms[me.roomId]
        if (room == null || !room.housingBroker) {
            outbound.send(OutboundEvent.SendError(sessionId, "You need to be at a housing broker to do that."))
            return false
        }
        return true
    }

    private suspend fun handleListTemplates(sessionId: SessionId) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        if (!requireBroker(sessionId)) return
        val me = players.get(sessionId) ?: return
        val pid = me.playerId ?: return
        val house = hs.getHouse(pid)

        outbound.send(OutboundEvent.SendText(sessionId, "Available Room Templates"))
        for ((_, template) in hs.templates) {
            val owned = house?.rooms?.any { it.templateId == template.id } == true
            val label = if (owned) " [owned]" else ""
            val cost = if (template.cost > 0) "${template.cost} gold" else "free"
            outbound.send(OutboundEvent.SendInfo(sessionId, "  ${template.title} (${template.id}) - $cost$label"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "    ${template.description}"))
        }
    }

    private suspend fun handleBuy(sessionId: SessionId) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        if (!requireBroker(sessionId)) return
        val err = hs.createHouse(sessionId)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        } else {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Congratulations! You are now a homeowner."))
            outbound.send(OutboundEvent.SendInfo(sessionId, "Use 'recall' to visit your house, or 'house status' to see your rooms."))
        }
    }

    private suspend fun handleExpand(sessionId: SessionId, cmd: Command.House.Expand) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        val template = hs.templates[cmd.templateId]
        val err = hs.expandHouse(sessionId, cmd.templateId, cmd.direction)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        } else {
            val name = template?.title ?: cmd.templateId
            outbound.send(OutboundEvent.SendInfo(sessionId, "You've added a $name to the ${cmd.direction.name.lowercase()}."))
        }
    }

    private suspend fun handleSetTitle(sessionId: SessionId, cmd: Command.House.SetTitle) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        val err = hs.setRoomTitle(sessionId, cmd.text)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        } else {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Room title updated."))
        }
    }

    private suspend fun handleSetDescription(sessionId: SessionId, cmd: Command.House.SetDescription) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        val err = hs.setRoomDescription(sessionId, cmd.text)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        } else {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Room description updated."))
        }
    }

    private suspend fun handleInvite(sessionId: SessionId, cmd: Command.House.Invite) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        val me = players.get(sessionId) ?: return

        val err = hs.invitePlayer(sessionId, cmd.playerName)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
            return
        }

        val target = players.getByName(cmd.playerName) ?: return
        val entryRoomId = hs.entryRoomId(me.name)

        movePlayerWithNotify(
            sessionId = target.sessionId,
            from = target.roomId,
            to = entryRoomId,
            departMsg = "vanishes in a shimmer of light.",
            arriveMsg = "appears in a shimmer of light.",
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
        )

        outbound.send(OutboundEvent.SendInfo(sessionId, "You've invited ${target.name} to your house."))
        outbound.send(OutboundEvent.SendInfo(target.sessionId, "${me.name} has invited you to their house."))
        ctx.sendLook(target.sessionId)
        outbound.send(OutboundEvent.SendPrompt(target.sessionId))
    }

    private suspend fun handleKick(sessionId: SessionId, cmd: Command.House.Kick) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        val err = hs.kickVisitor(sessionId, cmd.playerName)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        } else {
            outbound.send(OutboundEvent.SendInfo(sessionId, "${cmd.playerName} has been removed from your house."))
        }
    }

    private suspend fun handleGuests(sessionId: SessionId) {
        val hs = requireSystemOrNull(sessionId, housingSystem, "Housing", outbound) ?: return
        val guests = hs.guestList(sessionId)
        if (guests.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You have no visitors."))
        } else {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Visitors: ${guests.joinToString(", ")}"))
        }
    }
}
