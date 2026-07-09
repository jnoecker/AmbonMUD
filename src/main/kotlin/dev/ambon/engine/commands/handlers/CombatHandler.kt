package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.ConsiderOutcome
import dev.ambon.engine.DuelSystem
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectSystem

class CombatHandler(
    ctx: EngineContext,
    private val abilitySystem: AbilitySystem? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val dialogueSystem: DialogueSystem? = null,
    private val housingSystem: HousingSystem? = null,
    private val duelSystem: DuelSystem? = null,
) : CommandHandler {
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val combat = ctx.combat
    private val akathavaeSystem = ctx.akathavaeSystem
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val metrics = ctx.metrics
    private val world: World = ctx.world

    override fun register(router: CommandRouter) {
        router.on<Command.Kill> { sid, cmd -> handleKill(sid, cmd) }
        router.on<Command.Consider> { sid, cmd -> handleConsider(sid, cmd) }
        router.on<Command.Flee> { sid, _ -> handleFlee(sid) }
        router.on<Command.Cast> { sid, cmd -> handleCast(sid, cmd) }
    }

    private suspend fun handleConsider(
        sessionId: SessionId,
        cmd: Command.Consider,
    ) {
        when (val outcome = combat.consider(sessionId, cmd.target)) {
            is ConsiderOutcome.Error -> {
                // Pledged players sizing up a non-combatant NPC get the Akathavae
                // read (observation always succeeds) instead of the combat error.
                val me = players.get(sessionId)
                val mob =
                    if (me?.isAkathavae == true && akathavaeSystem != null) {
                        combat.findMobInRoom(me.roomId, cmd.target.trim())
                    } else {
                        null
                    }
                if (mob != null && !mob.role.isCombatant && !mob.isPet) {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "${mob.name} poses no threat — observing them for your Arcanum always succeeds.",
                        ),
                    )
                } else {
                    outbound.send(OutboundEvent.SendError(sessionId, outcome.message))
                }
            }
            is ConsiderOutcome.Ok -> {
                val r = outcome.result
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "You size up ${r.mobName} (lvl ${r.mobLevel}): ${r.rating.flavor}",
                    ),
                )
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "  Estimated win chance: ${r.winChancePct}%  " +
                            "(your ~${r.playerAvgDamage} dmg vs their ~${r.mobAvgDamage} dmg).",
                    ),
                )
                // Pledged players don't fight — show their real gamble: the
                // illumination success roll for their current effective stats.
                val me = players.get(sessionId)
                if (me?.isAkathavae == true && akathavaeSystem != null) {
                    val mob = mobs.get(MobId(r.mobId))
                    if (mob != null) {
                        val pct = akathavaeSystem.illuminationOddsFor(me, mob)
                        outbound.send(
                            OutboundEvent.SendText(
                                sessionId,
                                "  Your steady hand gives you a $pct% chance to illuminate ${r.mobName}.",
                            ),
                        )
                    }
                }
                gmcpEmitter?.sendCombatConsider(sessionId, r)
            }
        }
    }

    private suspend fun handleKill(
        sessionId: SessionId,
        cmd: Command.Kill,
    ) {
        // Block combat in house rooms marked as safe, or for visitors in any house
        if (housingSystem != null) {
            val me = players.get(sessionId)
            if (me != null && housingSystem.isHouseRoom(me.roomId)) {
                val template = housingSystem.currentRoomTemplate(sessionId)
                if (template?.safe == true) {
                    outbound.send(OutboundEvent.SendError(sessionId, "This room is protected. Combat is not allowed here."))
                    return
                }
                if (!housingSystem.isInOwnHouse(sessionId)) {
                    outbound.send(OutboundEvent.SendError(sessionId, "You can't start fights in someone else's house."))
                    return
                }
            }
        }
        dialogueSystem?.endConversation(sessionId)

        // In PvP zones, try targeting a player first
        val me = players.get(sessionId) ?: return
        val zone = me.roomId.zone
        if (world.isZonePvpEnabled(zone)) {
            val targetSid = players.findSessionByName(cmd.target)
            if (targetSid != null) {
                val target = players.get(targetSid)
                if (target != null && target.roomId == me.roomId) {
                    val pvpError = combat.startPvpCombat(sessionId, targetSid)
                    if (pvpError != null) {
                        outbound.send(OutboundEvent.SendError(sessionId, pvpError))
                        gmcpEmitter?.sendUiFeedback(
                            sessionId,
                            "error",
                            pvpError,
                            code = "PVP_ERROR",
                            scope = "combat",
                            command = "kill",
                        )
                    }
                    return
                }
            }
        }

        val error = combat.startCombat(sessionId, cmd.target)
        outbound.sendIfError(sessionId, error)
        if (error != null) {
            val code = killErrorCode(error)
            gmcpEmitter?.sendUiFeedback(sessionId, "error", error, code = code, scope = "combat", command = "kill")
        }
    }

    private suspend fun handleFlee(sessionId: SessionId) {
        dialogueSystem?.endConversation(sessionId)

        // Check if player is in a duel first
        if (duelSystem?.isInDuel(sessionId) == true) {
            val duel = duelSystem.endDuel(sessionId)
            if (duel != null) {
                val other = if (duel.player1 == sessionId) duel.player2 else duel.player1
                val myName = players.get(sessionId)?.name ?: "Someone"
                outbound.send(OutboundEvent.SendInfo(sessionId, "You flee from the duel!"))
                outbound.send(OutboundEvent.SendInfo(other, "$myName flees from the duel!"))
                gmcpEmitter?.sendDuelState(sessionId, active = false)
                gmcpEmitter?.sendDuelState(other, active = false)
            }
            return
        }

        // Check PvP combat
        if (combat.isInPvpCombat(sessionId)) {
            val pvpError = combat.fleePvp(sessionId)
            outbound.sendIfError(sessionId, pvpError)
            return
        }

        val error = combat.flee(sessionId)
        outbound.sendIfError(sessionId, error)
        if (error != null) {
            gmcpEmitter?.sendUiFeedback(sessionId, "error", error, code = "NOT_IN_COMBAT", scope = "combat", command = "flee")
        }
    }

    private suspend fun handleCast(
        sessionId: SessionId,
        cmd: Command.Cast,
    ) {
        val abilities = requireSystemOrNull(sessionId, abilitySystem, "Abilities", outbound) ?: return
        val error = abilities.cast(sessionId, cmd.spellName, cmd.target)
        outbound.sendIfError(sessionId, error)
        if (error != null) {
            gmcpEmitter?.sendUiFeedback(sessionId, "error", error, scope = "combat", command = "cast")
        } else {
            metrics.onGameEvent("ability", "cast")
        }
    }

    private fun killErrorCode(error: String): String = when {
        error.contains("already fighting", ignoreCase = true) -> "ALREADY_IN_COMBAT"
        error.contains("already in PvP", ignoreCase = true) -> "ALREADY_IN_COMBAT"
        error.contains("don't see", ignoreCase = true) -> "TARGET_NOT_FOUND"
        error.contains("Kill what", ignoreCase = true) -> "MISSING_TARGET"
        else -> "COMBAT_ERROR"
    }
}
