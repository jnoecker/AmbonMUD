package dev.ambon.engine.commands.handlers

import dev.ambon.domain.dungeon.DungeonDifficulty
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

    override fun register(router: CommandRouter) {
        router.on<Command.DungeonEnter> { sid, cmd -> handleDungeonEnter(sid, cmd) }
        router.on<Command.DungeonLeave> { sid, _ -> handleDungeonLeave(sid) }
    }

    private suspend fun handleDungeonEnter(sessionId: SessionId, cmd: Command.DungeonEnter) {
        val dm = dungeonManager ?: return sendUnavailable(sessionId)
        val reg = dungeonRegistry ?: return sendUnavailable(sessionId)

        players.withPlayer(sessionId) { me ->
            // Already in a dungeon?
            if (dm.isInDungeon(sessionId)) {
                outbound.send(OutboundEvent.SendText(sessionId, "You are already in a dungeon. Type 'dungeon leave' to exit first."))
                return
            }

            // Find the template
            val template = reg.findByKeyword(cmd.templateKeyword)
            if (template == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "Unknown dungeon '${cmd.templateKeyword}'."))
                return
            }

            // Parse difficulty
            val difficulty = if (cmd.difficulty != null) {
                DungeonDifficulty.fromName(cmd.difficulty)
                    ?: run {
                        val valid = DungeonDifficulty.entries.joinToString(", ") { it.displayName.lowercase() }
                        outbound.send(OutboundEvent.SendText(sessionId, "Unknown difficulty '${cmd.difficulty}'. Options: $valid"))
                        return
                    }
            } else {
                DungeonDifficulty.NORMAL
            }

            // Check minimum level
            if (me.level < template.minLevel) {
                outbound.send(
                    OutboundEvent.SendText(sessionId, "You must be at least level ${template.minLevel} to enter this dungeon."),
                )
                return
            }

            // Determine party members
            val group = groupSystem?.getGroup(sessionId)
            val memberSids = if (group != null) {
                group.members.toSet()
            } else {
                setOf(sessionId)
            }

            // Calculate average party level
            val partyLevel = memberSids.mapNotNull { sid -> players.get(sid)?.level }.average().toInt().coerceAtLeast(1)

            // Check for re-entry into existing instance
            val existingInstance = dm.getInstanceForPlayer(sessionId)
            if (existingInstance != null) {
                val entrance = dm.entranceRoom(existingInstance)
                players.moveTo(sessionId, entrance)
                outbound.send(OutboundEvent.SendInfo(sessionId, "You re-enter ${existingInstance.template.name}."))
                ctx.sendLook(sessionId)
                return
            }

            // Create the dungeon instance
            val instance = dm.createInstance(
                template = template,
                difficulty = difficulty,
                leader = sessionId,
                members = memberSids,
                partyLevel = partyLevel,
                returnRoom = me.roomId,
            )

            // Teleport all members to the entrance
            val entrance = dm.entranceRoom(instance)
            for (sid in memberSids) {
                players.moveTo(sid, entrance)
                outbound.send(
                    OutboundEvent.SendInfo(
                        sid,
                        "** You enter ${template.name} (${difficulty.displayName} difficulty) **",
                    ),
                )
                ctx.sendLook(sid)
            }
        }
    }

    private suspend fun handleDungeonLeave(sessionId: SessionId) {
        val dm = dungeonManager ?: return sendUnavailable(sessionId)

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

    private suspend fun sendUnavailable(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendText(sessionId, "Dungeons are not available."))
    }
}
