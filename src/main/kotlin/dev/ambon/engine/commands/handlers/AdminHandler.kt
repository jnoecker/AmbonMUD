package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.MobRemovalCoordinator
import dev.ambon.engine.PlayerState
import dev.ambon.engine.broadcastToRoom
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandParser
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.onStaff
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.BroadcastType
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage

class AdminHandler(
    ctx: EngineContext,
    private val onShutdown: suspend () -> Unit = {},
    private val mobRemovalCoordinator: MobRemovalCoordinator? = null,
    private val onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val interEngineBus: InterEngineBus? = null,
    private val engineId: String = "",
    private val metrics: GameMetrics = GameMetrics.noop(),
    private val onReload: (suspend (String?) -> String)? = null,
) : CommandHandler {
    private val ctx = ctx
    private val world = ctx.world
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val items = ctx.items
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private lateinit var router: CommandRouter

    private var adminSpawnSeq = 0

    override fun register(router: CommandRouter) {
        this.router = router
        router.onStaff<Command.Goto> { sid, cmd -> handleGoto(sid, cmd) }
        router.onStaff<Command.Transfer> { sid, cmd -> handleTransfer(sid, cmd) }
        router.onStaff<Command.Spawn> { sid, cmd -> handleSpawn(sid, cmd) }
        router.onStaff<Command.Shutdown> { sid, _ -> handleShutdown(sid) }
        router.onStaff<Command.Smite> { sid, cmd -> handleSmite(sid, cmd) }
        router.onStaff<Command.Kick> { sid, cmd -> handleKick(sid, cmd) }
        router.onStaff<Command.SetLevel> { sid, cmd -> handleSetLevel(sid, cmd) }
        router.onStaff<Command.Dispel> { sid, cmd -> handleDispel(sid, cmd) }
        router.onStaff<Command.Reload> { sid, cmd -> handleReload(sid, cmd) }
        router.onStaff<Command.Broadcast> { sid, cmd -> handleBroadcast(sid, cmd) }
        router.onStaff<Command.Possess> { sid, cmd -> handlePossess(sid, cmd) }
        router.onStaff<Command.Return> { sid, _ -> handleReturn(sid) }
        router.onStaff<Command.Invis> { sid, _ -> handleInvis(sid) }
    }

    private suspend fun handleGoto(
        sessionId: SessionId,
        cmd: Command.Goto,
    ) {
        players.withPlayer(sessionId) { me ->
            // Try player name first — goto <player> teleports to that player's room
            val targetPlayerSid = players.findSessionByName(cmd.arg.trim())
            if (targetPlayerSid != null && targetPlayerSid != sessionId) {
                val targetPlayer = players.get(targetPlayerSid)
                if (targetPlayer != null) {
                    players.moveTo(sessionId, targetPlayer.roomId)
                    ctx.sendLook(sessionId)
                    return
                }
            }

            val targetRoomId = resolveGotoArg(cmd.arg, me.roomId.zone, world)
            if (targetRoomId == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No such room or player: ${cmd.arg}"))
                return
            }
            if (!world.rooms.containsKey(targetRoomId)) {
                if (!attemptCrossZoneMove(sessionId, targetRoomId, onCrossZoneMove, router::suppressAutoPrompt)) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No such room or player: ${cmd.arg}"))
                }
                return
            }
            players.moveTo(sessionId, targetRoomId)
            ctx.sendLook(sessionId)
        }
    }

    private suspend fun handleTransfer(
        sessionId: SessionId,
        cmd: Command.Transfer,
    ) {
        players.withPlayer(sessionId) { me ->
            val targetSid = players.findSessionByName(cmd.playerName)
            if (targetSid == null) {
                if (interEngineBus != null) {
                    interEngineBus.broadcast(
                        InterEngineMessage.TransferRequest(
                            staffName = me.name,
                            targetPlayerName = cmd.playerName,
                            targetRoomId = cmd.arg,
                        ),
                    )
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Transfer request sent to other engines."))
                } else {
                    outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.playerName}"))
                }
                return
            }
            players.withPlayer(targetSid) { targetPlayer ->
                val targetRoomId = resolveGotoArg(cmd.arg, targetPlayer.roomId.zone, world)
                if (targetRoomId == null || !world.rooms.containsKey(targetRoomId)) {
                    outbound.send(OutboundEvent.SendError(sessionId, "No such room: ${cmd.arg}"))
                    return
                }
                players.moveTo(targetSid, targetRoomId)
                outbound.send(OutboundEvent.SendText(targetSid, "You are transported by a divine hand."))
                ctx.sendLook(targetSid)
                outbound.send(OutboundEvent.SendPrompt(targetSid))
                outbound.send(OutboundEvent.SendInfo(sessionId, "Transferred ${targetPlayer.name} to ${targetRoomId.value}."))
            }
        }
    }

    private suspend fun handleSpawn(
        sessionId: SessionId,
        cmd: Command.Spawn,
    ) {
        players.withPlayer(sessionId) { me ->
            val template = findMobTemplate(cmd.templateArg)
            if (template == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No mob template found: ${cmd.templateArg}"))
                return
            }
            val seq = ++adminSpawnSeq
            val zone = template.id.value.substringBefore(':', template.id.value)
            val local = template.id.value.substringAfter(':', template.id.value)
            val newMobId = MobId("$zone:${local}_adm_$seq")
            val spawned = MobState(
                id = newMobId,
                name = template.name,
                description = template.description,
                roomId = me.roomId,
                hp = template.maxHp,
                maxHp = template.maxHp,
                damage = template.damage,
                armor = template.armor,
                xpReward = template.xpReward,
                drops = template.drops,
                spawnRoomId = me.roomId,
                image = template.image,
            )
            mobs.upsert(spawned)
            broadcastToRoom(players, outbound, me.roomId, "${template.name} appears.")
            gmcpEmitter?.broadcastRoomAddMob(me.roomId, spawned, players)
        }
    }

    private suspend fun handleShutdown(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            for (p in players.allPlayers()) {
                outbound.send(
                    OutboundEvent.SendText(p.sessionId, "[SYSTEM] ${me.name} has initiated a server shutdown. Goodbye!"),
                )
            }
            interEngineBus?.broadcast(
                InterEngineMessage.GlobalBroadcast(
                    broadcastType = BroadcastType.SHUTDOWN,
                    senderName = me.name,
                    text = "${me.name} has initiated a server shutdown. Goodbye!",
                    sourceEngineId = engineId,
                ),
            )
            onShutdown()
        }
    }

    private suspend fun handleBroadcast(
        sessionId: SessionId,
        cmd: Command.Broadcast,
    ) {
        players.withPlayer(sessionId) { me ->
            for (p in players.allPlayers()) {
                outbound.send(
                    OutboundEvent.SendText(p.sessionId, "[ANNOUNCEMENT] ${me.name}: ${cmd.message}"),
                )
                gmcpEmitter?.sendServerBroadcast(p.sessionId, me.name, cmd.message)
                outbound.send(OutboundEvent.SendPrompt(p.sessionId))
            }
            interEngineBus?.broadcast(
                InterEngineMessage.GlobalBroadcast(
                    broadcastType = BroadcastType.ANNOUNCEMENT,
                    senderName = me.name,
                    text = cmd.message,
                    sourceEngineId = engineId,
                ),
            )
        }
    }

    private suspend fun handleSmite(
        sessionId: SessionId,
        cmd: Command.Smite,
    ) {
        players.withPlayer(sessionId) { me ->
            val targetSid = players.findSessionByName(cmd.target)
            if (targetSid != null && targetSid != sessionId) {
                players.withPlayer(targetSid) { targetPlayer ->
                    combat.endCombatFor(targetSid)
                    targetPlayer.hp = 1
                    players.moveTo(targetSid, world.startRoom)
                    outbound.send(
                        OutboundEvent.SendText(targetSid, "A divine hand strikes you down. You awaken at the start, bruised and humbled."),
                    )
                    ctx.sendLook(targetSid)
                    outbound.send(OutboundEvent.SendPrompt(targetSid))
                    outbound.send(OutboundEvent.SendInfo(sessionId, "Smote ${targetPlayer.name}."))
                }
                return
            }

            val targetMob = combat.findMobInRoom(me.roomId, cmd.target)
            if (targetMob == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No player or mob named '${cmd.target}'."))
                return
            }
            releasePossessorOf(targetMob.id)
            items.removeMobItems(targetMob.id)
            mobRemovalCoordinator?.removeMobExternally(targetMob.id)
            broadcastToRoom(players, outbound, me.roomId, "${targetMob.name} is struck down by divine wrath.")
            gmcpEmitter?.broadcastRoomRemoveMob(me.roomId, targetMob.id.value, players)
        }
    }

    private suspend fun handleKick(
        sessionId: SessionId,
        cmd: Command.Kick,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            if (interEngineBus != null) {
                interEngineBus.broadcast(InterEngineMessage.KickRequest(targetPlayerName = cmd.playerName))
                outbound.send(OutboundEvent.SendInfo(sessionId, "Kick request sent to other engines."))
            } else {
                outbound.send(OutboundEvent.SendError(sessionId, "No such player: ${cmd.playerName}"))
            }
            return
        }
        if (targetSid == sessionId) {
            outbound.send(OutboundEvent.SendError(sessionId, "You cannot kick yourself."))
            return
        }
        outbound.send(OutboundEvent.Close(targetSid, "Kicked by staff."))
        outbound.send(OutboundEvent.SendInfo(sessionId, "${cmd.playerName} has been kicked."))
    }

    private suspend fun handleSetLevel(
        sessionId: SessionId,
        cmd: Command.SetLevel,
    ) {
        val targetSid = requirePlayerOnline(sessionId, cmd.playerName, players, outbound) ?: return
        val maxLevel = players.maxLevel
        if (cmd.level !in 1..maxLevel) {
            outbound.send(OutboundEvent.SendError(sessionId, "Level must be between 1 and $maxLevel."))
            return
        }
        players.withPlayer(targetSid) { targetPlayer ->
            players.setLevel(targetSid, cmd.level)
            outbound.send(OutboundEvent.SendInfo(targetSid, "A divine hand reshapes your fate. You are now level ${cmd.level}."))
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            gmcpEmitter?.sendCharVitals(targetSid, targetPlayer)
            outbound.send(OutboundEvent.SendInfo(sessionId, "Set ${targetPlayer.name} to level ${cmd.level}."))
        }
    }

    private suspend fun handleDispel(
        sessionId: SessionId,
        cmd: Command.Dispel,
    ) {
        if (statusEffects == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Status effects are not available."))
            return
        }
        val targetSid = players.findSessionByName(cmd.target)
        if (targetSid != null) {
            statusEffects.removeAllFromPlayer(targetSid)
            val targetName = players.get(targetSid)?.name ?: cmd.target
            outbound.send(OutboundEvent.SendInfo(sessionId, "Dispelled all effects from $targetName."))
            outbound.send(OutboundEvent.SendText(targetSid, "All your effects have been dispelled."))
            return
        }
        players.withPlayer(sessionId) { me ->
            val mob = combat.findMobInRoom(me.roomId, cmd.target)
            if (mob != null) {
                statusEffects.removeAllFromMob(mob.id)
                outbound.send(OutboundEvent.SendInfo(sessionId, "Dispelled all effects from ${mob.name}."))
                return
            }
            outbound.send(OutboundEvent.SendError(sessionId, "No player or mob named '${cmd.target}'."))
        }
    }

    private suspend fun handleReload(
        sessionId: SessionId,
        cmd: Command.Reload,
    ) {
        if (onReload == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Hot reload is not configured."))
            return
        }
        val target = cmd.target?.lowercase()
        val validTargets = setOf("world", "abilities", "effects", "all")
        if (target != null && target !in validTargets) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Usage: reload [${validTargets.joinToString("|")}]  (default: all)",
                ),
            )
            return
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Reloading ${target ?: "all"}..."))
        val summary = onReload.invoke(target)
        outbound.send(OutboundEvent.SendInfo(sessionId, summary))
    }

    private suspend fun handlePossess(
        sessionId: SessionId,
        cmd: Command.Possess,
    ) {
        players.withPlayer(sessionId) { me ->
            if (me.possessedMobId != null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You are already possessing a mob. Use 'return' first."))
                return
            }
            if (combat.isInCombat(sessionId)) {
                outbound.send(OutboundEvent.SendError(sessionId, "You cannot possess a mob while in combat."))
                return
            }
            val mob = mobs.findInRoomByKeyword(me.roomId, cmd.target).firstOrNull()
            if (mob == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No mob '${cmd.target}' found in this room."))
                return
            }
            me.possessedMobId = mob.id
            me.prePossessRoomId = me.roomId
            setInvisible(sessionId, me, true)
            gmcpEmitter?.sendStaffPossessionState(sessionId, true, mob.name)
            outbound.send(
                OutboundEvent.SendInfo(sessionId, "You take control of ${mob.name}. Type 'return' or 'recall' to release."),
            )
            outbound.send(OutboundEvent.SendPrompt(sessionId))
        }
    }

    private suspend fun handleReturn(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val mobId = me.possessedMobId
            if (mobId == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You are not possessing a mob."))
                return
            }
            val mobName = mobs.get(mobId)?.name ?: "the mob"
            me.possessedMobId = null
            val returnRoom = me.prePossessRoomId ?: me.roomId
            me.prePossessRoomId = null
            setInvisible(sessionId, me, false)
            gmcpEmitter?.sendStaffPossessionState(sessionId, false, null)
            if (me.roomId != returnRoom) {
                players.moveTo(sessionId, returnRoom)
                ctx.sendLook(sessionId)
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "You release $mobName and return to your body."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
        }
    }

    /**
     * Routes a command while the player is possessing a mob.
     * Navigation moves the mob; say/emote speak as the mob; other staff commands pass through.
     */
    suspend fun handlePossessedCommand(sessionId: SessionId, line: String) {
        val me = players.get(sessionId) ?: return
        val mobId = me.possessedMobId ?: return
        val mob = mobs.get(mobId)
        if (mob == null) {
            me.possessedMobId = null
            me.prePossessRoomId = null
            outbound.send(OutboundEvent.SendError(sessionId, "The mob you were possessing no longer exists."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }

        val cmd = CommandParser.parse(line)
        when (cmd) {
            // Return / recall while possessing = release mob and return to body
            is Command.Return, is Command.Recall -> {
                handleReturn(sessionId)
                return
            }
            // Staff commands pass through normally
            is Command.Goto, is Command.Transfer, is Command.Spawn, is Command.Shutdown,
            is Command.Smite, is Command.Kick, is Command.SetLevel, is Command.Dispel,
            is Command.Reload, is Command.Possess,
            -> {
                router.handle(sessionId, cmd)
                return
            }
            // Navigation moves the mob
            is Command.Move -> {
                possessedMove(sessionId, me, mob, cmd)
                return
            }
            // Look shows mob's room
            is Command.Look -> {
                // Ensure player's roomId is the mob's room for sendLook
                val playerRoom = me.roomId
                if (playerRoom != mob.roomId) {
                    players.moveTo(sessionId, mob.roomId)
                }
                ctx.sendLook(sessionId)
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            // Exits shows mob's room exits
            is Command.Exits -> {
                val room = world.rooms[mob.roomId]
                if (room != null) {
                    val exitList = if (room.exits.isEmpty()) "none" else room.exits.keys.joinToString(", ") { it.name.lowercase() }
                    outbound.send(OutboundEvent.SendText(sessionId, "Exits: $exitList"))
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            // Say speaks as the mob
            is Command.Say -> {
                broadcastToRoom(players, outbound, mob.roomId, "${mob.name} says: ${cmd.message}")
                outbound.send(OutboundEvent.SendText(sessionId, "[${mob.name}] says: ${cmd.message}"))
                for (p in players.playersInRoom(mob.roomId)) {
                    gmcpEmitter?.sendCommChannel(p.sessionId, "say", mob.name, cmd.message)
                }
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            // Emote as the mob
            is Command.Emote -> {
                broadcastToRoom(players, outbound, mob.roomId, "${mob.name} ${cmd.message}")
                outbound.send(OutboundEvent.SendText(sessionId, "[${mob.name}] ${cmd.message}"))
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            // Combat: kill starts player combat from mob's room
            is Command.Kill -> {
                if (me.roomId != mob.roomId) players.moveTo(sessionId, mob.roomId)
                router.handle(sessionId, cmd)
                return
            }
            // Combat: flee ends player combat
            is Command.Flee -> {
                router.handle(sessionId, cmd)
                return
            }
            // Score shows mob stats
            is Command.Score -> {
                val combatStatus = if (combat.isInCombat(sessionId)) " | IN COMBAT" else ""
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "Possessing: ${mob.name} | HP: ${mob.hp}/${mob.maxHp} | Room: ${mob.roomId.value}$combatStatus",
                    ),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            else -> {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "While possessing: move, look, say, emote, kill, flee, score, or 'return'. Staff commands also work.",
                    ),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }
        }
    }

    private suspend fun possessedMove(
        sessionId: SessionId,
        me: PlayerState,
        mob: dev.ambon.domain.mob.MobState,
        cmd: Command.Move,
    ) {
        val from = mob.roomId
        val room = world.rooms[from]
        if (room == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "The mob's room doesn't exist."))
            return
        }
        val to = room.exits[cmd.dir]
        if (to == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You can't go that way."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        if (!world.rooms.containsKey(to)) {
            outbound.send(OutboundEvent.SendError(sessionId, "That exit leads to an unloaded zone."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }

        // Announce departure
        for (p in players.playersInRoom(from)) {
            outbound.send(OutboundEvent.SendText(p.sessionId, "${mob.name} leaves."))
        }
        gmcpEmitter?.broadcastRoomRemoveMob(from, mob.id.value, players)

        // Move the mob
        mobs.moveTo(mob.id, to)

        // Announce arrival
        for (p in players.playersInRoom(to)) {
            outbound.send(OutboundEvent.SendText(p.sessionId, "${mob.name} arrives."))
        }
        gmcpEmitter?.broadcastRoomAddMob(to, mob, players)

        // Move player's perspective to the mob's new room
        players.moveTo(sessionId, to)
        ctx.sendLook(sessionId)
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleInvis(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            setInvisible(sessionId, me, !me.invisible)
            val state = if (me.invisible) "invisible" else "visible"
            outbound.send(OutboundEvent.SendInfo(sessionId, "You are now $state."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
        }
    }

    /** Release any staff player possessing the given mob, returning them to their body. */
    private suspend fun releasePossessorOf(mobId: MobId) {
        for (p in players.allPlayers()) {
            if (p.possessedMobId == mobId) {
                p.possessedMobId = null
                val returnRoom = p.prePossessRoomId ?: p.roomId
                p.prePossessRoomId = null
                setInvisible(p.sessionId, p, false)
                gmcpEmitter?.sendStaffPossessionState(p.sessionId, false, null)
                if (p.roomId != returnRoom) players.moveTo(p.sessionId, returnRoom)
                outbound.send(
                    OutboundEvent.SendError(p.sessionId, "The mob you were possessing has been killed. Returning to your body."),
                )
                outbound.send(OutboundEvent.SendPrompt(p.sessionId))
            }
        }
    }

    /** Release possessor when a mob dies — called from GameEngine.onCombatMobRemoved. */
    suspend fun releasePossessorOfPublic(mobId: MobId) = releasePossessorOf(mobId)

    private suspend fun setInvisible(sessionId: SessionId, me: PlayerState, invisible: Boolean) {
        if (me.invisible == invisible) return
        val roomId = me.roomId
        if (invisible) {
            // Disappear from the room
            for (other in players.playersInRoom(roomId).filter { it.sessionId != sessionId }) {
                gmcpEmitter?.sendRoomRemovePlayer(other.sessionId, me.name)
            }
        } else {
            // Reappear in the room
            for (other in players.playersInRoom(roomId).filter { it.sessionId != sessionId }) {
                gmcpEmitter?.sendRoomAddPlayer(other.sessionId, me)
            }
        }
        me.invisible = invisible
    }

    private fun findMobTemplate(arg: String): dev.ambon.domain.world.MobSpawn? {
        val trimmed = arg.trim()
        return if (':' in trimmed) {
            world.mobSpawns.firstOrNull { it.id.value.equals(trimmed, ignoreCase = true) }
        } else {
            val lowerLocal = trimmed.lowercase()
            world.mobSpawns.firstOrNull {
                it.id.value.substringAfter(':', it.id.value).lowercase() == lowerLocal
            }
        }
    }
}
