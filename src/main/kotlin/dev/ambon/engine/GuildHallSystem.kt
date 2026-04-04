package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.GuildHallsConfig
import dev.ambon.config.GuildRanksConfig
import dev.ambon.domain.guild.GuildHallRoom
import dev.ambon.domain.guild.GuildRecord
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.GuildRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/** Sentinel room-id local part used for the dynamic exit back to the world. */
const val GUILD_HALL_EXIT_LOCAL = "exit"

class GuildHallSystem(
    private val players: PlayerRegistry,
    private val guildRepo: GuildRepository,
    private val world: World,
    private val outbound: OutboundBus,
    private val config: GuildHallsConfig,
    private val rankConfig: GuildRanksConfig = GuildRanksConfig(),
    private val markPlayerDirty: suspend (SessionId) -> Unit = {},
    private val gmcpEmitter: GmcpEmitter? = null,
    private val guildSystem: GuildSystem,
) : GameSystem {
    /**
     * Tracks where each visitor entered from, so we can return them on leave.
     */
    private val visitorOrigins = mutableMapOf<SessionId, RoomId>()

    /** Tracks which guild hall each session is currently in (by guild id). */
    private val sessionInHall = mutableMapOf<SessionId, String>()

    // -------- lifecycle --------

    /**
     * Called after guild data is loaded. Materialises all guild halls in the World.
     */
    fun materializeAllHalls(guilds: Collection<GuildRecord>) {
        for (guild in guilds) {
            if (guild.hallRooms.isNotEmpty()) {
                materializeHall(guild)
            }
        }
        val count = guilds.count { it.hallRooms.isNotEmpty() }
        if (count > 0) {
            log.info { "Materialised $count guild hall(s)" }
        }
    }

    /**
     * On player login, if they were in a guild hall that no longer exists, relocate them.
     */
    fun onPlayerLogin(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        val roomId = ps.roomId
        if (!isGuildHallRoom(roomId)) return

        val guildId = roomId.zone.removePrefix("guild_hall_")
        val guild = guildSystem.getGuild(guildId)
        if (guild == null || guild.hallRooms.isEmpty()) {
            // Guild or hall no longer exists; relocate
            val dest = ps.recallRoomId ?: world.startRoom
            ps.roomId = dest
            log.info { "${ps.name} was stranded in a guild hall after reconnect; relocated to $dest" }
            return
        }

        // Verify player is still a guild member
        val pid = ps.playerId ?: return
        if (!guild.members.containsKey(pid)) {
            val dest = ps.recallRoomId ?: world.startRoom
            ps.roomId = dest
            log.info { "${ps.name} is no longer in guild $guildId; relocated from guild hall to $dest" }
            return
        }

        // Restore tracking
        val recallRoom = ps.recallRoomId ?: world.startRoom
        visitorOrigins[sessionId] = recallRoom
        sessionInHall[sessionId] = guildId
    }

    override suspend fun onPlayerDisconnected(sessionId: SessionId) {
        visitorOrigins.remove(sessionId)
        sessionInHall.remove(sessionId)
    }

    override fun remapSession(oldSid: SessionId, newSid: SessionId) {
        visitorOrigins.remapKey(oldSid, newSid)
        sessionInHall.remapKey(oldSid, newSid)
    }

    // -------- commands --------

    /**
     * Purchase a guild hall. Leader only, deducts gold, creates initial meeting_hall room.
     * Returns error message on failure, null on success.
     */
    suspend fun buyHall(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val guildId = ps.guildId ?: return "You are not in a guild."
        val guild = guildSystem.getGuild(guildId) ?: return "Guild not found."

        if (!rankConfig.hasPermission(ps.guildRank ?: "", "disband")) {
            return "Only the guild leader can purchase a guild hall."
        }

        if (guild.hallRooms.isNotEmpty()) return "Your guild already has a hall."

        if (ps.gold < config.purchaseCost) {
            return "You need ${config.purchaseCost} gold to purchase a guild hall. You have ${ps.gold}."
        }

        val meetingHallTemplate = config.templates["meeting_hall"]
            ?: return "Guild halls are not configured (no meeting_hall template)."

        ps.gold -= config.purchaseCost
        markPlayerDirty(sessionId)

        val entryRoom = GuildHallRoom(
            id = "room_0",
            template = "meeting_hall",
            title = meetingHallTemplate.title,
            description = meetingHallTemplate.description,
        )
        val updated = guild.copy(hallRooms = listOf(entryRoom))
        guildRepo.save(updated)
        guildSystem.updateGuildCache(updated)
        materializeHall(updated)

        outbound.send(OutboundEvent.SendInfo(sessionId, "Your guild now has a hall! Use 'guild hall enter' to visit."))
        broadcastToGuild(updated, "A grand guild hall has been established! Use 'guild hall enter' to visit.", excludeSid = sessionId)
        sendHallGmcp(sessionId, updated)
        return null
    }

    /**
     * Expand the guild hall by adding a room from [template].
     * Leader/officer only, deducts gold. Returns error or null.
     */
    suspend fun expandHall(sessionId: SessionId, template: String): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val guildId = ps.guildId ?: return "You are not in a guild."
        val guild = guildSystem.getGuild(guildId) ?: return "Guild not found."

        val rank = ps.guildRank ?: return "You are not in a guild."
        if (!rankConfig.hasPermission(rank, "set_motd")) {
            return "Only officers and the leader can expand the guild hall."
        }

        if (guild.hallRooms.isEmpty()) return "Your guild doesn't have a hall. Use 'guild hall buy' first."

        if (guild.hallRooms.size >= config.maxRooms) {
            return "Your guild hall already has the maximum of ${config.maxRooms} rooms."
        }

        val tmplConfig = config.templates[template]
            ?: return "Unknown room template '$template'. Available: ${config.templates.keys.joinToString(", ")}."

        if (guild.hallRooms.any { it.template == template }) {
            return "Your guild hall already has a ${tmplConfig.title}."
        }

        if (ps.gold < config.roomCost) {
            return "You need ${config.roomCost} gold to expand the guild hall. You have ${ps.gold}."
        }

        ps.gold -= config.roomCost
        markPlayerDirty(sessionId)

        val newRoom = GuildHallRoom(
            id = "room_${guild.hallRooms.size}",
            template = template,
            title = tmplConfig.title,
            description = tmplConfig.description,
        )
        val updated = guild.copy(hallRooms = guild.hallRooms + newRoom)
        guildRepo.save(updated)
        guildSystem.updateGuildCache(updated)
        materializeHall(updated)

        outbound.send(OutboundEvent.SendInfo(sessionId, "The guild hall now has a ${tmplConfig.title}!"))
        broadcastToGuild(updated, "The guild hall has been expanded with a ${tmplConfig.title}!", excludeSid = sessionId)
        sendHallGmcp(sessionId, updated)
        return null
    }

    /**
     * Teleport into the guild hall meeting room.
     * Returns error message, or null on success (caller handles look).
     */
    suspend fun enterHall(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val guildId = ps.guildId ?: return "You are not in a guild."
        val guild = guildSystem.getGuild(guildId) ?: return "Guild not found."

        if (guild.hallRooms.isEmpty()) return "Your guild doesn't have a hall."

        if (sessionInHall[sessionId] == guildId) return "You are already in the guild hall."

        val entryRoomId = guildHallRoomId(guildId, 0)
        visitorOrigins[sessionId] = ps.roomId
        sessionInHall[sessionId] = guildId

        return null // Caller handles movement
    }

    /** Returns the entry room ID for the given guild's hall. */
    fun hallEntryRoomId(guildId: String): RoomId = guildHallRoomId(guildId, 0)

    /**
     * Leave the guild hall, returning to the previous location.
     * Returns error message, or null on success (caller handles look).
     */
    suspend fun leaveHall(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED

        val guildId = sessionInHall[sessionId] ?: return "You are not in a guild hall."
        val origin = visitorOrigins.remove(sessionId)
        sessionInHall.remove(sessionId)

        if (origin == null) {
            val dest = ps.recallRoomId ?: world.startRoom
            ps.roomId = dest
        }

        return null // Caller handles movement
    }

    /** Returns the origin room for the session to return to after leaving. */
    fun resolveHallExit(sessionId: SessionId): RoomId? {
        val origin = visitorOrigins.remove(sessionId)
        sessionInHall.remove(sessionId)
        return origin
    }

    /**
     * Show guild hall info to the player.
     */
    suspend fun showHallInfo(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val guildId = ps.guildId ?: return "You are not in a guild."
        val guild = guildSystem.getGuild(guildId) ?: return "Guild not found."

        if (guild.hallRooms.isEmpty()) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "Your guild doesn't have a hall. A guild leader can purchase one with " +
                        "'guild hall buy' (${config.purchaseCost} gold).",
                ),
            )
            return null
        }

        val lines = buildString {
            appendLine("=== ${guild.name} Guild Hall ===")
            appendLine("Rooms (${guild.hallRooms.size}/${config.maxRooms}):")
            for (room in guild.hallRooms) {
                appendLine("  ${room.title} [${room.template}]")
            }
            val membersInHall = sessionInHall.entries.count { it.value == guildId }
            append("Members in hall: $membersInHall")
        }.trimEnd()

        outbound.send(OutboundEvent.SendInfo(sessionId, lines))
        sendHallGmcp(sessionId, guild)
        return null
    }

    /** Checks whether [roomId] belongs to any guild hall zone. */
    fun isGuildHallRoom(roomId: RoomId): Boolean = roomId.zone.startsWith("guild_hall_")

    /** Checks whether [roomId] is a guild hall exit sentinel. */
    fun isGuildHallExit(roomId: RoomId): Boolean = roomId.local == GUILD_HALL_EXIT_LOCAL && isGuildHallRoom(roomId)

    /** Returns true if [sessionId] is currently in any guild hall. */
    fun isInAnyHall(sessionId: SessionId): Boolean = sessionInHall.containsKey(sessionId)

    /**
     * Called when a guild is disbanded — boot all visitors from the hall.
     */
    suspend fun onGuildDisbanded(guildId: String) {
        val toRemove = sessionInHall.entries
            .filter { it.value == guildId }
            .map { it.key }
            .toList()

        for (sid in toRemove) {
            val origin = visitorOrigins.remove(sid)
            sessionInHall.remove(sid)
            val ps = players.get(sid) ?: continue
            val dest = origin ?: ps.recallRoomId ?: world.startRoom
            ps.roomId = dest
            outbound.send(OutboundEvent.SendInfo(sid, "The guild hall fades around you as the guild is disbanded."))
            outbound.send(OutboundEvent.SendPrompt(sid))
        }

        // Remove guild hall rooms from the world
        removeHallRooms(guildId)
    }

    // -------- internals --------

    /** Materialise all rooms for [guild]'s hall in the World. */
    private fun materializeHall(guild: GuildRecord) {
        val guildId = guild.id
        val roomMap = mutableMapOf<RoomId, Room>()

        for ((index, hallRoom) in guild.hallRooms.withIndex()) {
            val roomId = guildHallRoomId(guildId, index)

            // All rooms connect back to meeting hall (room_0), and meeting hall connects to all others
            val exits = mutableMapOf<Direction, RoomId>()

            if (index == 0) {
                // Meeting hall: south exit goes back to world
                exits[Direction.SOUTH] = guildHallExitSentinel(guildId)
                // Connect to additional rooms via other directions
                val availableDirections = listOf(Direction.NORTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)
                for ((dirIdx, otherIndex) in (1 until guild.hallRooms.size).withIndex()) {
                    if (dirIdx < availableDirections.size) {
                        exits[availableDirections[dirIdx]] = guildHallRoomId(guildId, otherIndex)
                    }
                }
            } else {
                // Non-entry rooms connect back to meeting hall
                val availableDirections = listOf(Direction.NORTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)
                // Find which direction meeting hall uses to reach us
                val reverseDir = when {
                    index - 1 < availableDirections.size -> {
                        val fwdDir = availableDirections[index - 1]
                        when (fwdDir) {
                            Direction.NORTH -> Direction.SOUTH
                            Direction.SOUTH -> Direction.NORTH
                            Direction.EAST -> Direction.WEST
                            Direction.WEST -> Direction.EAST
                            Direction.UP -> Direction.DOWN
                            Direction.DOWN -> Direction.UP
                        }
                    }
                    else -> Direction.SOUTH
                }
                exits[reverseDir] = guildHallRoomId(guildId, 0)
            }

            val desc = hallRoom.customDescription ?: hallRoom.description
            roomMap[roomId] = Room(
                id = roomId,
                title = hallRoom.title,
                description = "$desc\n\nThis is part of the ${guild.name} guild hall.",
                exits = exits,
            )
        }

        world.putRooms(roomMap)
    }

    private fun removeHallRooms(guildId: String) {
        world.removeZone("guild_hall_$guildId")
    }

    private fun guildHallRoomId(guildId: String, index: Int): RoomId =
        RoomId("guild_hall_$guildId:room_$index")

    private fun guildHallExitSentinel(guildId: String): RoomId =
        RoomId("guild_hall_$guildId:$GUILD_HALL_EXIT_LOCAL")

    private suspend fun broadcastToGuild(
        guild: GuildRecord,
        message: String,
        excludeSid: SessionId? = null,
    ) {
        for ((memberId, _) in guild.members) {
            val sid = players.findSessionByPlayerId(memberId) ?: continue
            if (sid == excludeSid) continue
            outbound.send(OutboundEvent.SendInfo(sid, message))
        }
    }

    private suspend fun sendHallGmcp(sessionId: SessionId, guild: GuildRecord) {
        gmcpEmitter?.sendGuildHall(
            sessionId,
            guildName = guild.name,
            rooms = guild.hallRooms.map { GmcpEmitter.GuildHallRoomPayload(it.id, it.template, it.title) },
            membersInHall = sessionInHall.entries.count { it.value == guild.id },
            maxRooms = config.maxRooms,
        )
    }
}
