package dev.ambon.engine.commands.handlers

import dev.ambon.config.BoatConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.BoatSystem
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent

/**
 * Handles the boat-dock kiosk commands: `voyages` (list the dock's authored routes + fares) and
 * `sail <destination|#>` (pay the flat author-set fare to travel). Sailing is blocked in combat;
 * otherwise the gold fare is the only gate. Fares are paid on every trip — there is no ownership.
 */
class BoatHandler(
    private val ctx: EngineContext,
    private val dialogueSystem: DialogueSystem? = null,
    private val onPlayerMoved: (suspend (SessionId, dev.ambon.domain.ids.RoomId) -> Unit)? = null,
    private val markVitalsDirty: ((SessionId) -> Unit)? = null,
) : CommandHandler {
    private val players = ctx.players
    private val world = ctx.world
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val metrics = ctx.metrics
    private val boat: BoatSystem? = ctx.boatSystem
    private val config: BoatConfig = ctx.boatConfig

    override fun register(router: CommandRouter) {
        router.on<Command.Boat.List> { sid, _ -> handleList(sid) }
        router.on<Command.Boat.Travel> { sid, cmd -> handleTravel(sid, cmd) }
    }

    private suspend fun requireBoatDock(sessionId: SessionId, me: PlayerState): Boolean {
        if (boat?.isBoatDock(me.roomId) != true) {
            outbound.send(OutboundEvent.SendError(sessionId, config.messages.notAtDock))
            return false
        }
        return true
    }

    private suspend fun handleList(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        val boat = this.boat ?: return
        if (!requireBoatDock(sessionId, me)) return

        val destinations = boat.destinationsFrom(me.roomId)
        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Harbor Master ]"))
        if (destinations.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  ${config.messages.noRoutes}"))
            emitBoatState(sessionId, me, destinations)
            return
        }
        outbound.send(
            OutboundEvent.SendInfo(sessionId, "  You have ${me.gold} gold. Routes you can sail:"),
        )
        destinations.forEachIndexed { index, dest ->
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "    ${index + 1}. ${dest.name} (${dest.zone}) — ${dest.price} gold",
                ),
            )
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Use 'sail <name>' or 'sail <#>' to travel."))
        emitBoatState(sessionId, me, destinations)
    }

    private suspend fun handleTravel(sessionId: SessionId, cmd: Command.Boat.Travel) {
        val me = players.get(sessionId) ?: return
        val boat = this.boat ?: return
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, config.messages.combatBlocked))
            return
        }
        if (!requireBoatDock(sessionId, me)) return

        val destinations = boat.destinationsFrom(me.roomId)
        if (destinations.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, config.messages.noRoutes))
            return
        }

        val target = resolveDestination(cmd.destination.trim(), destinations)
        if (target == null) {
            outbound.send(OutboundEvent.SendError(sessionId, config.messages.unknownDestination))
            return
        }
        if (target.roomId == me.roomId) {
            outbound.send(OutboundEvent.SendError(sessionId, config.messages.alreadyHere))
            return
        }
        if (me.gold < target.price) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    config.messages.notEnoughGold
                        .replace("{cost}", target.price.toString())
                        .replace("{gold}", me.gold.toString()),
                ),
            )
            return
        }

        me.gold -= target.price
        markVitalsDirty?.invoke(sessionId)
        metrics.onGameEvent("boat", "travel")

        outbound.send(
            OutboundEvent.SendText(sessionId, config.messages.depart.replace("{dest}", target.name)),
        )
        val from = me.roomId
        movePlayerWithNotify(
            sessionId,
            from,
            target.roomId,
            config.messages.departNotice,
            config.messages.arriveNotice,
            players,
            outbound,
            gmcpEmitter,
            dialogueSystem,
            world = world,
        )
        onPlayerMoved?.invoke(sessionId, target.roomId)
        outbound.send(
            OutboundEvent.SendText(
                sessionId,
                config.messages.arrival
                    .replace("{dest}", target.name)
                    .replace("{cost}", target.price.toString()),
            ),
        )
        ctx.sendLook(sessionId)
    }

    /**
     * Resolves a `sail` argument against the current route list. Tries, in order: a 1-based list
     * index, an exact room-id, an exact (case-insensitive) name, then a name substring.
     */
    private fun resolveDestination(
        arg: String,
        destinations: List<BoatSystem.Destination>,
    ): BoatSystem.Destination? {
        if (arg.isEmpty()) return null
        arg.toIntOrNull()?.let { idx ->
            return destinations.getOrNull(idx - 1)
        }
        destinations.firstOrNull { it.roomId.value.equals(arg, ignoreCase = true) }?.let { return it }
        destinations.firstOrNull { it.name.equals(arg, ignoreCase = true) }?.let { return it }
        return destinations.firstOrNull { it.name.contains(arg, ignoreCase = true) }
    }

    private suspend fun emitBoatState(
        sessionId: SessionId,
        me: PlayerState,
        destinations: List<BoatSystem.Destination>,
    ) {
        gmcpEmitter?.sendBoatState(
            sessionId = sessionId,
            playerGold = me.gold,
            destinations = destinations,
            origin = boat?.originAt(me.roomId),
        )
    }
}
