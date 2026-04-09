package dev.ambon.engine.commands.handlers

import dev.ambon.domain.dungeon.DungeonDifficulty
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dungeon.DungeonManager
import dev.ambon.engine.dungeon.DungeonRegistry
import dev.ambon.engine.events.OutboundEvent

class DungeonHandler(
    private val ctx: EngineContext,
    private val dungeonManager: DungeonManager? = null,
    private val dungeonRegistry: DungeonRegistry? = null,
    private val groupSystem: GroupSystem? = null,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.DungeonEnter> { sid, cmd -> handleDungeonEnter(sid, cmd) }
        router.on<Command.DungeonLeave> { sid, _ -> handleDungeonLeave(sid) }
    }

    private suspend fun handleDungeonEnter(sessionId: SessionId, cmd: Command.DungeonEnter) {
        val dm = dungeonManager ?: return sendUnavailable(sessionId)
        val reg = dungeonRegistry ?: return sendUnavailable(sessionId)

        players.withPlayer(sessionId) { me ->
            // Re-entry: if the player has an active dungeon, teleport back in
            val existingInstance = dm.getInstanceForPlayer(sessionId)
            if (existingInstance != null) {
                val entrance = dm.entranceRoom(existingInstance)
                players.moveTo(sessionId, entrance)
                val message = "You re-enter ${existingInstance.template.name}."
                outbound.send(OutboundEvent.SendInfo(sessionId, message))
                sendDungeonFeedback(sessionId, "success", message, code = "REENTERED", command = "enter")
                ctx.sendLook(sessionId)
                gmcpEmitter?.sendDungeonInfo(
                    sessionId,
                    active = true,
                    instanceId = existingInstance.id,
                    name = existingInstance.template.name,
                    difficulty = existingInstance.difficulty.displayName,
                    totalRooms = existingInstance.layout.rooms.size,
                    completed = existingInstance.completed,
                    memberCount = existingInstance.members.size,
                )
                return
            }

            // Find the template
            val template = reg.findByKeyword(cmd.templateKeyword)
            if (template == null) {
                val message = "Unknown dungeon '${cmd.templateKeyword}'."
                outbound.send(OutboundEvent.SendError(sessionId, message))
                sendDungeonFeedback(sessionId, "error", message, code = "DUNGEON_NOT_FOUND", command = "enter")
                return
            }

            // Parse difficulty
            val difficulty = if (cmd.difficulty != null) {
                DungeonDifficulty.fromName(cmd.difficulty)
                    ?: run {
                        val valid = DungeonDifficulty.entries.joinToString(", ") { it.displayName.lowercase() }
                        val message = "Unknown difficulty '${cmd.difficulty}'. Options: $valid"
                        outbound.send(OutboundEvent.SendError(sessionId, message))
                        sendDungeonFeedback(sessionId, "error", message, code = "INVALID_DIFFICULTY", command = "enter")
                        return
                    }
            } else {
                DungeonDifficulty.NORMAL
            }

            // Check minimum level for all party members
            val group = groupSystem?.getGroup(sessionId)
            val memberSids = if (group != null) {
                group.members.toSet()
            } else {
                setOf(sessionId)
            }
            for (sid in memberSids) {
                val member = players.get(sid) ?: continue
                if (member.level < template.minLevel) {
                    val message =
                        "${member.name} is level ${member.level} but this dungeon requires level ${template.minLevel}."
                    outbound.send(OutboundEvent.SendError(sessionId, message))
                    sendDungeonFeedback(sessionId, "error", message, code = "MIN_LEVEL", command = "enter")
                    return
                }
            }

            // Calculate average party level
            val partyLevel = memberSids.mapNotNull { sid -> players.get(sid)?.level }.average().toInt().coerceAtLeast(1)

            // Determine return room: prefer template's portal room, fall back to current room
            val returnRoom = if (template.portalRoom != null) {
                val qualifiedPortal = RoomId("${template.id.substringBefore(':')}:${template.portalRoom}")
                if (ctx.world.rooms.containsKey(qualifiedPortal)) qualifiedPortal else me.roomId
            } else {
                me.roomId
            }

            // Create the dungeon instance
            val instance = dm.createInstance(
                template = template,
                difficulty = difficulty,
                leader = sessionId,
                members = memberSids,
                partyLevel = partyLevel,
                returnRoom = returnRoom,
            )

            // Teleport all members to the entrance
            val entrance = dm.entranceRoom(instance)
            for (sid in memberSids) {
                players.moveTo(sid, entrance)
                val enterMessage = "** You enter ${template.name} (${difficulty.displayName} difficulty) **"
                outbound.send(
                    OutboundEvent.SendInfo(
                        sid,
                        enterMessage,
                    ),
                )
                sendDungeonFeedback(sid, "success", enterMessage, code = "ENTERED", command = "enter")
                ctx.sendLook(sid)
                gmcpEmitter?.sendDungeonInfo(
                    sid,
                    active = true,
                    instanceId = instance.id,
                    name = template.name,
                    difficulty = difficulty.displayName,
                    totalRooms = instance.layout.rooms.size,
                    completed = false,
                    memberCount = memberSids.size,
                )
            }
        }
    }

    private suspend fun handleDungeonLeave(sessionId: SessionId) {
        val dm = dungeonManager ?: return sendUnavailable(sessionId)

        players.withPlayer(sessionId) { me ->
            val returnRoom = dm.removePlayer(sessionId)
            if (returnRoom == null) {
                val message = "You are not in a dungeon."
                outbound.send(OutboundEvent.SendError(sessionId, message))
                sendDungeonFeedback(sessionId, "error", message, code = "NOT_IN_DUNGEON", command = "leave")
                return
            }
            val message = "You leave the dungeon."
            outbound.send(OutboundEvent.SendInfo(sessionId, message))
            sendDungeonFeedback(sessionId, "success", message, code = "LEFT", command = "leave")
            players.moveTo(sessionId, returnRoom)
            ctx.sendLook(sessionId)
            gmcpEmitter?.sendDungeonInfo(sessionId, active = false)
        }
    }

    private suspend fun sendUnavailable(sessionId: SessionId) {
        val message = "Dungeons are not available."
        outbound.send(OutboundEvent.SendError(sessionId, message))
        sendDungeonFeedback(sessionId, "error", message, code = "UNAVAILABLE")
    }

    private suspend fun sendDungeonFeedback(
        sessionId: SessionId,
        type: String,
        message: String,
        code: String? = null,
        command: String? = null,
    ) {
        gmcpEmitter?.sendUiFeedback(
            sessionId = sessionId,
            type = type,
            message = message,
            code = code,
            scope = "dungeon",
            command = command,
        )
    }
}
