package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dungeon.DungeonManager
import dev.ambon.engine.events.OutboundEvent

class DungeonHandler(
    private val ctx: EngineContext,
    private val dungeonManager: DungeonManager? = null,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound

    override fun register(router: CommandRouter) {
        router.on<Command.DungeonLeave> { sid, _ -> handleDungeonLeave(sid) }
    }

    private suspend fun handleDungeonLeave(sessionId: SessionId) {
        val dm = dungeonManager
        if (dm == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "Dungeons are not available."))
            return
        }

        players.withPlayer(sessionId) { me ->
            val returnRoom = dm.removePlayer(sessionId)
            if (returnRoom == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "You are not in a dungeon."))
                return
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "You leave the dungeon."))
            players.moveTo(sessionId, returnRoom)
            ctx.sendLook(sessionId)
        }
    }
}
