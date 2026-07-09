package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

/**
 * `travel <zone:room>` — mount map-click fast travel. All validation and the
 * scheduled ride itself live in [dev.ambon.engine.MountTravelSystem]; this
 * handler only bridges the command.
 */
class MountTravelHandler(
    private val ctx: EngineContext,
) : CommandHandler {
    override fun register(router: CommandRouter) {
        router.on<Command.Travel> { sid, cmd -> handleTravel(sid, cmd) }
    }

    private suspend fun handleTravel(
        sessionId: SessionId,
        cmd: Command.Travel,
    ) {
        val system = ctx.mountTravelSystem
        if (system == null) {
            ctx.outbound.send(OutboundEvent.SendError(sessionId, "Travel is not available on this server."))
            return
        }
        system.requestTravel(sessionId, cmd.destination)
    }
}
