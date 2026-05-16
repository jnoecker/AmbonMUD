package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.ids.idZone
import dev.ambon.domain.world.ScalingMode
import dev.ambon.domain.world.World
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
    private val world = ctx.world
    private val players = ctx.players
    private val outbound = ctx.outbound

    override fun register(router: CommandRouter) {
        router.on<Command.Time> { sid, _ -> handleTime(sid) }
        router.on<Command.Areas> { sid, cmd -> handleAreas(sid, cmd) }
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
            val weatherId = weatherSystem.weatherForZone(zone)
            val weatherDef = weatherSystem.typeDefinition(weatherId)
            val weatherLabel = weatherDef?.let { "${it.displayName} — ${it.description}" } ?: weatherId
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Weather: $weatherLabel"))
        }

        val activeEvents = worldEventSystem.activeEvents()
        if (activeEvents.isNotEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Active Events:"))
            for ((_, def) in activeEvents) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "    ${def.displayName}: ${def.description}"))
            }
        }
    }

    private suspend fun handleAreas(sessionId: SessionId, cmd: Command.Areas) {
        val ranges = computeZoneLevelRanges(world)
        val filtered = ranges.filter { (_, range) ->
            val lo = cmd.minLevel
            val hi = cmd.maxLevel
            when {
                range == null -> lo == null && hi == null
                lo != null && hi != null -> range.last >= lo && range.first <= hi
                lo != null -> range.last >= lo
                hi != null -> range.first <= hi
                else -> true
            }
        }.toList().sortedWith(
            compareBy({ it.second?.first ?: Int.MAX_VALUE }, { it.first }),
        )

        val header = when {
            cmd.minLevel != null && cmd.maxLevel != null && cmd.minLevel != cmd.maxLevel ->
                "[ Areas — level ${cmd.minLevel}-${cmd.maxLevel} ]"
            cmd.minLevel != null && cmd.maxLevel != null ->
                "[ Areas — level ${cmd.minLevel} ]"
            cmd.minLevel != null -> "[ Areas — level >= ${cmd.minLevel} ]"
            cmd.maxLevel != null -> "[ Areas — level <= ${cmd.maxLevel} ]"
            else -> "[ Areas ]"
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, header))
        if (filtered.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  (no matching areas)"))
            return
        }
        for ((zone, range) in filtered) {
            val levelStr = when {
                range == null -> "—"
                range.first == range.last -> "lvl ${range.first}"
                else -> "lvl ${range.first}-${range.last}"
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "  $zone  ($levelStr)"))
        }
    }

    /**
     * Returns each zone's effective level range. Prefers the authored BOUNDED
     * scaling range; otherwise infers from the min/max level across the zone's
     * mob templates. Zones with no level signal map to null.
     */
    private fun computeZoneLevelRanges(world: World): Map<String, IntRange?> {
        val zones = world.zones()
        val mobLevelsByZone = HashMap<String, MutableList<Int>>()
        for ((id, template) in world.mobTemplates) {
            val zone = idZone(id.value)
            if (template.level > 0) {
                mobLevelsByZone.getOrPut(zone) { mutableListOf() }.add(template.level)
            }
        }
        return zones.associateWith { zone ->
            val scaling = world.zoneScaling(zone)
            val authored = if (scaling.mode == ScalingMode.BOUNDED) scaling.levelRange else null
            authored ?: mobLevelsByZone[zone]?.let { IntRange(it.min(), it.max()) }
        }
    }
}
