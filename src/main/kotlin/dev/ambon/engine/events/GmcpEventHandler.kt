package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.AchievementRegistry
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.GuildSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import io.github.oshai.kotlinlogging.KLogger

class GmcpEventHandler(
    private val gmcpSessions: MutableMap<SessionId, MutableSet<String>>,
    private val players: PlayerRegistry,
    private val world: World,
    private val items: ItemRegistry,
    private val mobs: MobRegistry,
    private val abilitySystem: AbilitySystem,
    private val statusEffectSystem: StatusEffectSystem,
    private val achievementRegistry: AchievementRegistry,
    private val groupSystem: GroupSystem,
    private val guildSystem: GuildSystem? = null,
    private val gmcpEmitter: GmcpEmitter,
    private val logger: KLogger,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    suspend fun onGmcpReceived(ev: InboundEvent.GmcpReceived) {
        metrics.onGmcpHandlerEvent()
        val sid = ev.sessionId
        when (ev.gmcpPackage) {
            "Core.Hello" -> {
                logger.debug { "GMCP Core.Hello from session=$sid data=${ev.jsonData}" }
            }

            "Core.Supports.Set" -> {
                val packages = parseGmcpPackageList(ev.jsonData)
                val supported = gmcpSessions.getOrPut(sid) { mutableSetOf() }
                supported.addAll(packages)
                logger.debug { "GMCP supports set for session=$sid packages=$packages" }

                val player = players.get(sid) ?: return
                val room = world.rooms[player.roomId] ?: return
                gmcpEmitter.sendFullCharacterSync(
                    sid,
                    player,
                    items,
                    abilitySystem,
                    statusEffectSystem,
                    achievementRegistry,
                    groupSystem,
                    players,
                    guildSystem,
                )
                gmcpEmitter.sendRoomInfo(sid, room)
                gmcpEmitter.sendRoomPlayers(sid, players.playersInRoom(player.roomId).toList())
                gmcpEmitter.sendRoomMobs(sid, mobs.mobsInRoom(player.roomId))
                gmcpEmitter.sendRoomItems(sid, items.itemsInRoom(player.roomId))
            }

            "Core.Supports.Remove" -> {
                val packages = parseGmcpPackageList(ev.jsonData)
                gmcpSessions[sid]?.removeAll(packages.toSet())
            }

            "Core.Ping" -> {
                gmcpEmitter.sendCorePing(sid)
            }
        }
    }

    private fun parseGmcpPackageList(json: String): List<String> {
        val content = json.trim().removePrefix("[").removeSuffix("]")
        if (content.isBlank()) return emptyList()
        return content
            .split(",")
            .map { it.trim().removeSurrounding("\"").trim() }
            .map { it.substringBefore(' ') }
            .filter { it.isNotBlank() }
    }
}
