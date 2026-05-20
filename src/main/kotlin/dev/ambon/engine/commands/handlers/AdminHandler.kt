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
import dev.ambon.engine.resolvePlayerStats
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
    private val classRegistry = ctx.classRegistry
    private val raceRegistry = ctx.raceRegistry
    private val genderRegistry = ctx.genderRegistry
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
        router.onStaff<Command.SetStaff> { sid, cmd -> handleSetStaff(sid, cmd) }
        router.onStaff<Command.SetGold> { sid, cmd -> handleSetGold(sid, cmd) }
        router.onStaff<Command.SetRace> { sid, cmd -> handleSetRace(sid, cmd) }
        router.onStaff<Command.SetClass> { sid, cmd -> handleSetClass(sid, cmd) }
        router.onStaff<Command.StaffSetGender> { sid, cmd -> handleStaffSetGender(sid, cmd) }
        router.onStaff<Command.SetXp> { sid, cmd -> handleSetXp(sid, cmd) }
        router.onStaff<Command.Heal> { sid, cmd -> handleHeal(sid, cmd) }
        router.onStaff<Command.Pinfo> { sid, cmd -> handlePinfo(sid, cmd) }
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
                sendAdminError(sessionId, "No such room or player: ${cmd.arg}", code = "TARGET_NOT_FOUND", command = "goto")
                return
            }
            if (!world.rooms.containsKey(targetRoomId)) {
                if (!attemptCrossZoneMove(sessionId, targetRoomId, onCrossZoneMove, router::suppressAutoPrompt)) {
                    sendAdminError(sessionId, "No such room or player: ${cmd.arg}", code = "TARGET_NOT_FOUND", command = "goto")
                }
                return
            }
            players.moveTo(sessionId, targetRoomId)
            sendAdminSuccess(sessionId, "Teleported to ${targetRoomId.value}.", code = "GOTO_COMPLETE", command = "goto")
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
                    sendAdminSuccess(
                        sessionId,
                        "Transfer request sent to other engines.",
                        code = "TRANSFER_REQUESTED",
                        command = "transfer",
                    )
                } else {
                    sendAdminError(
                        sessionId,
                        "No such player: ${cmd.playerName}",
                        code = "PLAYER_NOT_FOUND",
                        command = "transfer",
                    )
                }
                return
            }
            players.withPlayer(targetSid) { targetPlayer ->
                val targetRoomId = resolveGotoArg(cmd.arg, targetPlayer.roomId.zone, world)
                if (targetRoomId == null || !world.rooms.containsKey(targetRoomId)) {
                    sendAdminError(sessionId, "No such room: ${cmd.arg}", code = "ROOM_NOT_FOUND", command = "transfer")
                    return
                }
                players.moveTo(targetSid, targetRoomId)
                outbound.send(OutboundEvent.SendText(targetSid, "You are transported by a divine hand."))
                ctx.sendLook(targetSid)
                outbound.send(OutboundEvent.SendPrompt(targetSid))
                sendAdminSuccess(
                    sessionId,
                    "Transferred ${targetPlayer.name} to ${targetRoomId.value}.",
                    code = "TRANSFER_COMPLETE",
                    command = "transfer",
                )
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
                sendAdminError(sessionId, "No mob template found: ${cmd.templateArg}", code = "MOB_TEMPLATE_NOT_FOUND", command = "spawn")
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
                level = template.level,
            )
            mobs.upsert(spawned)
            broadcastToRoom(players, outbound, me.roomId, "${template.name} appears.")
            gmcpEmitter?.broadcastRoomAddMob(me.roomId, spawned, players)
            sendAdminSuccess(sessionId, "Spawned ${template.name}.", code = "SPAWN_COMPLETE", command = "spawn")
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
            sendAdminSuccess(
                sessionId,
                "Server shutdown initiated.",
                code = "SHUTDOWN_INITIATED",
                command = "shutdown",
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
            sendAdminSuccess(
                sessionId,
                "Broadcast sent to all connected players.",
                code = "BROADCAST_COMPLETE",
                command = "broadcast",
            )
        }
    }

    private suspend fun handleSmite(
        sessionId: SessionId,
        cmd: Command.Smite,
    ) {
        players.withPlayer(sessionId) { me ->
            val targetSid = players.findSessionByName(cmd.target)
            if (targetSid == sessionId) {
                sendAdminError(sessionId, "You cannot smite yourself.", code = "CANNOT_TARGET_SELF", command = "smite")
                return
            }
            if (targetSid != null) {
                players.withPlayer(targetSid) { targetPlayer ->
                    combat.endCombatFor(targetSid)
                    targetPlayer.hp = 1
                    players.moveTo(targetSid, world.startRoom)
                    outbound.send(
                        OutboundEvent.SendText(targetSid, "A divine hand strikes you down. You awaken at the start, bruised and humbled."),
                    )
                    ctx.sendLook(targetSid)
                    outbound.send(OutboundEvent.SendPrompt(targetSid))
                    sendAdminSuccess(sessionId, "Smote ${targetPlayer.name}.", code = "SMITE_COMPLETE", command = "smite")
                }
                return
            }

            val targetMob = combat.findMobInRoom(me.roomId, cmd.target)
            if (targetMob == null) {
                sendAdminError(
                    sessionId,
                    "No player or mob named '${cmd.target}'.",
                    code = "TARGET_NOT_FOUND",
                    command = "smite",
                )
                return
            }
            releasePossessorOf(targetMob.id)
            items.removeMobItems(targetMob.id)
            mobRemovalCoordinator?.removeMobExternally(targetMob.id)
            broadcastToRoom(players, outbound, me.roomId, "${targetMob.name} is struck down by divine wrath.")
            gmcpEmitter?.broadcastRoomRemoveMob(me.roomId, targetMob.id.value, players)
            sendAdminSuccess(sessionId, "Smote ${targetMob.name}.", code = "SMITE_COMPLETE", command = "smite")
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
                sendAdminSuccess(
                    sessionId,
                    "Kick request sent to other engines.",
                    code = "KICK_REQUESTED",
                    command = "kick",
                )
            } else {
                sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "kick")
            }
            return
        }
        if (targetSid == sessionId) {
            sendAdminError(sessionId, "You cannot kick yourself.", code = "CANNOT_TARGET_SELF", command = "kick")
            return
        }
        outbound.send(OutboundEvent.Close(targetSid, "Kicked by staff."))
        sendAdminSuccess(sessionId, "${cmd.playerName} has been kicked.", code = "KICK_COMPLETE", command = "kick")
    }

    private suspend fun handleSetLevel(
        sessionId: SessionId,
        cmd: Command.SetLevel,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "setlevel")
            return
        }
        val maxLevel = players.maxLevel
        if (cmd.level !in 1..maxLevel) {
            sendAdminError(
                sessionId,
                "Level must be between 1 and $maxLevel.",
                code = "INVALID_LEVEL",
                command = "setlevel",
            )
            return
        }
        players.withPlayer(targetSid) { targetPlayer ->
            players.setLevel(targetSid, cmd.level)
            outbound.send(OutboundEvent.SendInfo(targetSid, "A divine hand reshapes your fate. You are now level ${cmd.level}."))
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            gmcpEmitter?.sendCharVitals(targetSid, targetPlayer)
            sendAdminSuccess(
                sessionId,
                "Set ${targetPlayer.name} to level ${cmd.level}.",
                code = "SET_LEVEL_COMPLETE",
                command = "setlevel",
            )
        }
    }

    private suspend fun handleSetStaff(
        sessionId: SessionId,
        cmd: Command.SetStaff,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            sendAdminError(sessionId, "No such online player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "setstaff")
            return
        }
        if (targetSid == sessionId) {
            sendAdminError(sessionId, "You cannot change your own staff flag.", code = "CANNOT_TARGET_SELF", command = "setstaff")
            return
        }
        players.withPlayer(targetSid) { targetPlayer ->
            targetPlayer.isStaff = cmd.grant
            val verb = if (cmd.grant) "granted" else "revoked"
            val targetMsg = if (cmd.grant) "You have been granted staff access." else "Your staff access has been revoked."
            outbound.send(OutboundEvent.SendInfo(targetSid, targetMsg))
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            gmcpEmitter?.sendCharName(targetSid, targetPlayer)
            sendAdminSuccess(
                sessionId,
                "Staff $verb for ${targetPlayer.name}.",
                code = "SET_STAFF_COMPLETE",
                command = "setstaff",
            )
        }
    }

    private suspend fun handleDispel(
        sessionId: SessionId,
        cmd: Command.Dispel,
    ) {
        if (statusEffects == null) {
            sendAdminError(sessionId, "Status effects are not available.", code = "UNAVAILABLE", command = "dispel")
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
                sendAdminSuccess(sessionId, "Dispelled all effects from ${mob.name}.", code = "DISPEL_COMPLETE", command = "dispel")
                return
            }
            sendAdminError(sessionId, "No player or mob named '${cmd.target}'.", code = "TARGET_NOT_FOUND", command = "dispel")
        }
    }

    private suspend fun handleReload(
        sessionId: SessionId,
        cmd: Command.Reload,
    ) {
        if (onReload == null) {
            sendAdminError(sessionId, "Hot reload is not configured.", code = "UNAVAILABLE", command = "reload")
            return
        }
        val target = cmd.target?.lowercase()
        val validTargets = setOf("world", "abilities", "effects", "all")
        if (target != null && target !in validTargets) {
            sendAdminError(
                sessionId,
                "Usage: reload [${validTargets.joinToString("|")}]  (default: all)",
                code = "INVALID_TARGET",
                command = "reload",
            )
            return
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Reloading ${target ?: "all"}..."))
        val summary = onReload.invoke(target)
        sendAdminSuccess(sessionId, summary, code = "RELOAD_COMPLETE", command = "reload")
    }

    private suspend fun handlePossess(
        sessionId: SessionId,
        cmd: Command.Possess,
    ) {
        players.withPlayer(sessionId) { me ->
            if (me.possessedMobId != null) {
                sendAdminError(
                    sessionId,
                    "You are already possessing a mob. Use 'return' first.",
                    code = "ALREADY_POSSESSING",
                    command = "possess",
                )
                return
            }
            if (combat.isInCombat(sessionId)) {
                sendAdminError(sessionId, "You cannot possess a mob while in combat.", code = "IN_COMBAT", command = "possess")
                return
            }
            val mob = mobs.findInRoomByKeyword(me.roomId, cmd.target).firstOrNull()
            if (mob == null) {
                sendAdminError(sessionId, "No mob '${cmd.target}' found in this room.", code = "MOB_NOT_FOUND", command = "possess")
                return
            }
            me.possessedMobId = mob.id
            me.prePossessRoomId = me.roomId
            setInvisible(sessionId, me, true)
            gmcpEmitter?.sendStaffPossessionState(sessionId, true, mob.name)
            val message = "You take control of ${mob.name}. Type 'return' or 'recall' to release."
            sendAdminSuccess(sessionId, message, code = "POSSESS_COMPLETE", command = "possess")
            outbound.send(OutboundEvent.SendPrompt(sessionId))
        }
    }

    private suspend fun handleReturn(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val mobId = me.possessedMobId
            if (mobId == null) {
                sendAdminError(sessionId, "You are not possessing a mob.", code = "NOT_POSSESSING", command = "return")
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
            val message = "You release $mobName and return to your body."
            sendAdminSuccess(sessionId, message, code = "RETURN_COMPLETE", command = "return")
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
            val returnRoom = me.prePossessRoomId ?: me.roomId
            me.prePossessRoomId = null
            setInvisible(sessionId, me, false)
            gmcpEmitter?.sendStaffPossessionState(sessionId, false, null)
            if (me.roomId != returnRoom) {
                players.moveTo(sessionId, returnRoom)
                ctx.sendLook(sessionId)
            }
            outbound.send(OutboundEvent.SendError(sessionId, "The mob you were possessing no longer exists. Returning to your body."))
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
            is Command.Reload, is Command.Possess, is Command.SetGold, is Command.SetRace,
            is Command.SetClass, is Command.StaffSetGender, is Command.SetXp,
            is Command.Heal, is Command.Pinfo,
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
            val message = "You are now $state."
            outbound.send(OutboundEvent.SendInfo(sessionId, message))
            sendAdminSuccess(sessionId, message, code = "VISIBILITY_CHANGED", command = "invis")
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

    private suspend fun handleSetGold(
        sessionId: SessionId,
        cmd: Command.SetGold,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "setgold")
            return
        }
        if (cmd.amount < 0) {
            sendAdminError(sessionId, "Gold amount cannot be negative.", code = "INVALID_AMOUNT", command = "setgold")
            return
        }
        players.withPlayer(targetSid) { targetPlayer ->
            targetPlayer.gold = cmd.amount
            outbound.send(OutboundEvent.SendInfo(targetSid, "A divine hand adjusts your purse. You now have ${cmd.amount} gold."))
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            gmcpEmitter?.sendCharVitals(targetSid, targetPlayer)
            sendAdminSuccess(
                sessionId,
                "Set ${targetPlayer.name}'s gold to ${cmd.amount}.",
                code = "SET_GOLD_COMPLETE",
                command = "setgold",
            )
        }
    }

    private suspend fun handleSetRace(
        sessionId: SessionId,
        cmd: Command.SetRace,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "setrace")
            return
        }
        val raceId = cmd.race.uppercase()
        val raceDef = raceRegistry?.get(raceId)
        if (raceDef == null) {
            val options = raceRegistry?.all()?.joinToString(", ") { it.id } ?: "unknown"
            sendAdminError(sessionId, "Unknown race '${cmd.race}'. Options: $options", code = "INVALID_RACE", command = "setrace")
            return
        }
        players.withPlayer(targetSid) { targetPlayer ->
            targetPlayer.race = raceDef.id
            outbound.send(OutboundEvent.SendInfo(targetSid, "A divine hand reshapes your form. You are now a ${raceDef.displayName}."))
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            gmcpEmitter?.sendCharName(targetSid, targetPlayer)
            sendAdminSuccess(
                sessionId,
                "Set ${targetPlayer.name}'s race to ${raceDef.displayName}.",
                code = "SET_RACE_COMPLETE",
                command = "setrace",
            )
        }
    }

    private suspend fun handleSetClass(
        sessionId: SessionId,
        cmd: Command.SetClass,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "setclass")
            return
        }
        val classId = cmd.playerClass.uppercase()
        val classDef = classRegistry?.get(classId)
        if (classDef == null) {
            val options = classRegistry?.all()?.joinToString(", ") { it.id } ?: "unknown"
            sendAdminError(
                sessionId,
                "Unknown class '${cmd.playerClass}'. Options: $options",
                code = "INVALID_CLASS",
                command = "setclass",
            )
            return
        }
        players.withPlayer(targetSid) { targetPlayer ->
            targetPlayer.playerClass = classDef.id
            targetPlayer.unlockedClasses.add(classDef.id)
            // Recalculate HP/mana based on new class scaling
            players.setLevel(targetSid, targetPlayer.level)
            outbound.send(
                OutboundEvent.SendInfo(targetSid, "A divine hand reshapes your destiny. You are now a ${classDef.displayName}."),
            )
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            gmcpEmitter?.sendCharName(targetSid, targetPlayer)
            gmcpEmitter?.sendCharVitals(targetSid, targetPlayer)
            sendAdminSuccess(
                sessionId,
                "Set ${targetPlayer.name}'s class to ${classDef.displayName}.",
                code = "SET_CLASS_COMPLETE",
                command = "setclass",
            )
        }
    }

    private suspend fun handleStaffSetGender(
        sessionId: SessionId,
        cmd: Command.StaffSetGender,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "setgender")
            return
        }
        val genderId = cmd.gender.lowercase()
        val gender = genderRegistry?.get(genderId)
        if (gender == null) {
            val options = genderRegistry?.allIds()?.joinToString(", ") ?: "unknown"
            sendAdminError(
                sessionId,
                "Unknown gender '${cmd.gender}'. Options: $options",
                code = "INVALID_GENDER",
                command = "setgender",
            )
            return
        }
        players.withPlayer(targetSid) { targetPlayer ->
            targetPlayer.gender = gender.id
            outbound.send(OutboundEvent.SendInfo(targetSid, "A divine hand reshapes your identity. Gender set to ${gender.displayName}."))
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            gmcpEmitter?.sendCharName(targetSid, targetPlayer)
            sendAdminSuccess(
                sessionId,
                "Set ${targetPlayer.name}'s gender to ${gender.displayName}.",
                code = "SET_GENDER_COMPLETE",
                command = "setgender",
            )
        }
    }

    private suspend fun handleSetXp(
        sessionId: SessionId,
        cmd: Command.SetXp,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "setxp")
            return
        }
        if (cmd.amount < 0) {
            sendAdminError(sessionId, "XP amount cannot be negative.", code = "INVALID_AMOUNT", command = "setxp")
            return
        }
        players.withPlayer(targetSid) { targetPlayer ->
            targetPlayer.xpTotal = cmd.amount
            outbound.send(OutboundEvent.SendInfo(targetSid, "A divine hand adjusts your experience. You now have ${cmd.amount} XP."))
            outbound.send(OutboundEvent.SendPrompt(targetSid))
            gmcpEmitter?.sendCharVitals(targetSid, targetPlayer)
            sendAdminSuccess(
                sessionId,
                "Set ${targetPlayer.name}'s XP to ${cmd.amount}.",
                code = "SET_XP_COMPLETE",
                command = "setxp",
            )
        }
    }

    private suspend fun handleHeal(
        sessionId: SessionId,
        cmd: Command.Heal,
    ) {
        val targetSid = if (cmd.playerName != null) {
            val sid = players.findSessionByName(cmd.playerName)
            if (sid == null) {
                sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "heal")
                return
            }
            sid
        } else {
            sessionId
        }
        players.withPlayer(targetSid) { targetPlayer ->
            targetPlayer.hp = targetPlayer.maxHp
            targetPlayer.mana = targetPlayer.maxMana
            if (targetSid != sessionId) {
                outbound.send(OutboundEvent.SendInfo(targetSid, "A divine hand restores you to full health."))
                outbound.send(OutboundEvent.SendPrompt(targetSid))
            }
            gmcpEmitter?.sendCharVitals(targetSid, targetPlayer)
            sendAdminSuccess(
                sessionId,
                if (targetSid == sessionId) {
                    "Restored to full HP and mana."
                } else {
                    "Restored ${targetPlayer.name} to full HP and mana."
                },
                code = "HEAL_COMPLETE",
                command = "heal",
            )
        }
    }

    private suspend fun handlePinfo(
        sessionId: SessionId,
        cmd: Command.Pinfo,
    ) {
        val targetSid = players.findSessionByName(cmd.playerName)
        if (targetSid == null) {
            sendAdminError(sessionId, "No such player: ${cmd.playerName}", code = "PLAYER_NOT_FOUND", command = "pinfo")
            return
        }
        players.withPlayer(targetSid) { target ->
            val effectiveStats = resolvePlayerStats(target, items, statusEffects, classRegistry)
            val lines = buildString {
                appendLine("=== Player Info: ${target.name} ===")
                appendLine("Level: ${target.level}  |  XP: ${target.xpTotal}  |  Prestige: ${target.prestigeLevel}")
                appendLine("Race: ${target.race}  |  Class: ${target.playerClass}  |  Gender: ${target.gender}")
                appendLine("HP: ${target.hp}/${target.maxHp}  |  Mana: ${target.mana}/${target.maxMana}")
                appendLine("Gold: ${target.gold}  |  Bank: ${target.bankGold}")
                val stats = effectiveStats.values.entries.joinToString(", ") { (k, v) -> "$k=$v" }
                appendLine("Stats: $stats")
                appendLine("Room: ${target.roomId.value}")
                appendLine("Staff: ${target.isStaff}  |  Invisible: ${target.invisible}")
                if (target.guildId != null) appendLine("Guild: ${target.guildId} (${target.guildRank ?: "no rank"})")
                if (target.activeTitle != null) appendLine("Title: ${target.activeTitle}")
                if (target.unlockedClasses.size > 1) appendLine("Unlocked classes: ${target.unlockedClasses.joinToString(", ")}")
                if (target.learnedAbilityIds.isNotEmpty()) appendLine("Abilities: ${target.learnedAbilityIds.joinToString(", ")}")
                val pvp = "PvP: ${target.pvpKills}K / ${target.pvpDeaths}D"
                val pve = "Mobs killed: ${target.mobsKilledTotal}  |  Dungeons: ${target.dungeonsCompleted}"
                appendLine("$pvp  |  $pve")
                if (target.currencies.isNotEmpty()) {
                    val curr = target.currencies.entries.joinToString(", ") {
                        "${it.key}=${it.value}"
                    }
                    appendLine("Currencies: $curr")
                }
                append("=== End Info ===")
            }
            outbound.send(OutboundEvent.SendText(sessionId, lines))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
        }
    }

    private fun findMobTemplate(arg: String): dev.ambon.domain.world.MobTemplateDef? {
        val trimmed = arg.trim()
        return if (':' in trimmed) {
            world.mobTemplates.values.firstOrNull { it.id.value.equals(trimmed, ignoreCase = true) }
        } else {
            val lowerLocal = trimmed.lowercase()
            world.mobTemplates.values.firstOrNull {
                it.id.value.substringAfter(':', it.id.value).lowercase() == lowerLocal
            }
        }
    }

    private suspend fun sendAdminError(
        sessionId: SessionId,
        message: String,
        code: String? = null,
        command: String? = null,
    ) {
        outbound.send(OutboundEvent.SendError(sessionId, message))
        gmcpEmitter?.sendUiFeedback(
            sessionId = sessionId,
            type = "error",
            message = message,
            code = code,
            scope = "admin",
            command = command,
        )
    }

    private suspend fun sendAdminSuccess(
        sessionId: SessionId,
        message: String,
        code: String? = null,
        command: String? = null,
    ) {
        outbound.send(OutboundEvent.SendInfo(sessionId, message))
        gmcpEmitter?.sendUiFeedback(
            sessionId = sessionId,
            type = "success",
            message = message,
            code = code,
            scope = "admin",
            command = command,
        )
    }
}
