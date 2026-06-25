package dev.ambon.engine.commands.handlers

import dev.ambon.config.FlightConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.FlightSystem
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent

/**
 * Handles the flight-master kiosk commands: `flights` (list discovered destinations + fares)
 * and `fly <destination|#>` (pay gold to fast-travel). Flying is blocked in combat; otherwise
 * the gold fare — scaled by travel distance in [FlightSystem] — is the only gate.
 */
class FlightHandler(
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
    private val flight: FlightSystem? = ctx.flightSystem
    private val config: FlightConfig = ctx.flightConfig

    override fun register(router: CommandRouter) {
        router.on<Command.Flight.List> { sid, _ -> handleList(sid) }
        router.on<Command.Flight.Travel> { sid, cmd -> handleTravel(sid, cmd) }
    }

    private suspend fun requireFlightMaster(sessionId: SessionId, me: PlayerState): Boolean {
        if (flight?.isFlightMaster(me.roomId) != true) {
            outbound.send(OutboundEvent.SendError(sessionId, config.messages.notAtFlightMaster))
            return false
        }
        return true
    }

    private suspend fun handleList(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        val flight = this.flight ?: return
        if (!requireFlightMaster(sessionId, me)) return

        val destinations = flight.destinationsFrom(me.roomId, me.discoveredFlightPoints)
        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Flight Master ]"))
        if (destinations.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  ${config.messages.noDestinations}"))
            emitFlightState(sessionId, me, destinations)
            return
        }
        outbound.send(
            OutboundEvent.SendInfo(sessionId, "  You have ${me.gold} gold. Destinations you can fly to:"),
        )
        destinations.forEachIndexed { index, dest ->
            val reach = dest.distance?.let { "$it rooms" } ?: "distant"
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "    ${index + 1}. ${dest.name} (${dest.zone}) — $reach, ${dest.cost} gold",
                ),
            )
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Use 'fly <name>' or 'fly <#>' to travel."))
        emitFlightState(sessionId, me, destinations)
    }

    private suspend fun handleTravel(sessionId: SessionId, cmd: Command.Flight.Travel) {
        val me = players.get(sessionId) ?: return
        val flight = this.flight ?: return
        if (combat.isInCombat(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, config.messages.combatBlocked))
            return
        }
        if (!requireFlightMaster(sessionId, me)) return

        val destinations = flight.destinationsFrom(me.roomId, me.discoveredFlightPoints)
        if (destinations.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, config.messages.noDestinations))
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
        if (me.gold < target.cost) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    config.messages.notEnoughGold
                        .replace("{cost}", target.cost.toString())
                        .replace("{gold}", me.gold.toString()),
                ),
            )
            return
        }

        me.gold -= target.cost
        markVitalsDirty?.invoke(sessionId)
        metrics.onGameEvent("flight", "travel")

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
                    .replace("{cost}", target.cost.toString()),
            ),
        )
        ctx.sendLook(sessionId)
    }

    /**
     * Resolves a `fly` argument against the current destination list. Tries, in order: a 1-based
     * list index, an exact room-id, an exact (case-insensitive) name, then a name substring.
     */
    private fun resolveDestination(
        arg: String,
        destinations: List<FlightSystem.Destination>,
    ): FlightSystem.Destination? {
        if (arg.isEmpty()) return null
        arg.toIntOrNull()?.let { idx ->
            return destinations.getOrNull(idx - 1)
        }
        destinations.firstOrNull { it.roomId.value.equals(arg, ignoreCase = true) }?.let { return it }
        destinations.firstOrNull { it.name.equals(arg, ignoreCase = true) }?.let { return it }
        return destinations.firstOrNull { it.name.contains(arg, ignoreCase = true) }
    }

    private suspend fun emitFlightState(
        sessionId: SessionId,
        me: PlayerState,
        destinations: List<FlightSystem.Destination>,
    ) {
        gmcpEmitter?.sendFlightState(
            sessionId = sessionId,
            playerGold = me.gold,
            destinations = destinations,
        )
    }
}
