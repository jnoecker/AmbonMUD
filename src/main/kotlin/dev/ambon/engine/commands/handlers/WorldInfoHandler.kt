package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.WeatherSystem
import dev.ambon.engine.WorldEventSystem
import dev.ambon.engine.WorldTimeSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class WorldInfoHandler(
    ctx: EngineContext,
    private val worldTimeSystem: WorldTimeSystem,
    private val weatherSystem: WeatherSystem,
    private val worldEventSystem: WorldEventSystem,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound

    override fun register(router: CommandRouter) {
        router.on<Command.Time> { sid, _ -> handleTime(sid) }
    }

    private suspend fun handleTime(sessionId: SessionId) {
        val period = worldTimeSystem.period()
        val hour = worldTimeSystem.gameHour()
        val minute = worldTimeSystem.gameMinute()
        val timeStr = "%d:%02d".format(hour, minute)

        outbound.send(OutboundEvent.SendInfo(sessionId, "[ World Status ]"))
        outbound.send(OutboundEvent.SendInfo(sessionId, "  Time: $timeStr ($period — ${period.description})"))

        val me = players.get(sessionId)
        if (me != null) {
            val zone = me.roomId.zone
            val weather = weatherSystem.weatherForZone(zone)
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Weather: ${weather.displayName} — ${weather.description}"))
        }

        val activeEvents = worldEventSystem.activeEvents()
        if (activeEvents.isNotEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Active Events:"))
            for ((_, def) in activeEvents) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "    ${def.displayName}: ${def.description}"))
            }
        }
    }
}
