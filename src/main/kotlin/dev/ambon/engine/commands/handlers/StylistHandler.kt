package dev.ambon.engine.commands.handlers

import dev.ambon.config.StylistConfig
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerState
import dev.ambon.engine.SpriteRegistry
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class StylistHandler(
    ctx: EngineContext,
    private val stylistConfig: StylistConfig = StylistConfig(),
    private val progression: PlayerProgression = PlayerProgression(),
    private val abilitySystem: AbilitySystem? = null,
    private val markVitalsDirty: ((SessionId) -> Unit)? = null,
    private val markStatsDirty: ((SessionId) -> Unit)? = null,
    private val spriteRegistry: SpriteRegistry? = null,
) : CommandHandler {
    private val players = ctx.players
    private val world = ctx.world
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val raceRegistry = ctx.raceRegistry
    private val metrics = ctx.metrics

    override fun register(router: CommandRouter) {
        router.on<Command.Stylist.List> { sid, _ -> handleList(sid) }
        router.on<Command.Stylist.ChangeRace> { sid, cmd -> handleChangeRace(sid, cmd) }
    }

    private suspend fun requireStylist(sessionId: SessionId, me: PlayerState): Boolean {
        val room = world.rooms[me.roomId]
        if (room == null || !room.stylist) {
            outbound.send(OutboundEvent.SendError(sessionId, "You need to be at a stylist to do that."))
            return false
        }
        return true
    }

    private suspend fun handleList(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        if (!requireStylist(sessionId, me)) return

        val registry = raceRegistry
        if (registry == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "The stylist isn't open for business right now."))
            return
        }

        val races = registry.all()
        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Stylist ]"))
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "  Fee: ${stylistConfig.feeGold} gold  (you have ${me.gold} gold)",
            ),
        )
        outbound.send(OutboundEvent.SendInfo(sessionId, "  Current race: ${me.race}"))
        outbound.send(OutboundEvent.SendInfo(sessionId, "  Available races:"))
        for (race in races) {
            val mods = formatStatMods(race.statMods)
            val marker = if (race.id.equals(me.race, ignoreCase = true)) " (current)" else ""
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "    ${race.id} — ${race.displayName}$marker  $mods",
                ),
            )
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Use 'changerace <race>' to switch."))
        emitStylistState(sessionId, me)
    }

    private suspend fun handleChangeRace(sessionId: SessionId, cmd: Command.Stylist.ChangeRace) {
        val me = players.get(sessionId) ?: return
        if (!requireStylist(sessionId, me)) return

        val registry = raceRegistry
        if (registry == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "The stylist isn't open for business right now."))
            return
        }

        val target = registry.get(cmd.raceId)
        if (target == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "No such race: '${cmd.raceId}'."))
            return
        }

        if (target.id.equals(me.race, ignoreCase = true)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You already are a ${target.displayName}."))
            return
        }

        if (me.gold < stylistConfig.feeGold) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "The stylist requires ${stylistConfig.feeGold} gold, but you only have ${me.gold}.",
                ),
            )
            return
        }

        val oldRace = registry.get(me.race)
        val oldMods = oldRace?.statMods ?: StatMap.EMPTY

        me.gold -= stylistConfig.feeGold
        me.stats = applyStatDelta(me.stats, oldMods, target.statMods)
        me.race = target.id
        progression.recomputeVitalCaps(me)

        abilitySystem?.setRaceAbilities(sessionId, target.abilities.toSet())

        markVitalsDirty?.invoke(sessionId)
        markStatsDirty?.invoke(sessionId)

        metrics.onGameEvent("stylist", "race_change")
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "The stylist weaves their craft. You are now a ${target.displayName}. (-${stylistConfig.feeGold} gold)",
            ),
        )

        // If the player had an explicit sprite pinned that's no longer valid for the new race,
        // drop it so they fall back to auto-resolve for their new race.
        val spriteReg = spriteRegistry
        val currentSprite = me.activeSprite
        if (spriteReg != null && currentSprite != null) {
            val stillValid = spriteReg.validateSelection(
                imageId = currentSprite,
                level = me.level,
                unlockedAchievementIds = me.unlockedAchievementIds,
                isStaff = me.isStaff,
                playerRace = me.race,
                playerClass = me.playerClass,
                playerGender = me.gender,
            )
            if (stillValid == null) {
                me.activeSprite = null
            }
        }

        gmcpEmitter?.sendCharName(sessionId, me)
        if (abilitySystem != null) {
            gmcpEmitter?.sendCharSkills(
                sessionId,
                abilitySystem.knownAbilities(sessionId),
                cooldownRemainingMs = { abilityId -> abilitySystem.cooldownRemainingMs(sessionId, abilityId) },
                player = me,
                manaCostFor = { ability -> abilitySystem.computeManaCost(me, ability) },
            )
        }
        // Re-emit the sprite catalogue so the stylist / sprite panel shows choices
        // appropriate to the new race (sprites are race-filtered).
        gmcpEmitter?.sendCharSprites(sessionId, me)
        emitStylistState(sessionId, me)
    }

    private suspend fun emitStylistState(sessionId: SessionId, me: PlayerState) {
        val registry = raceRegistry ?: return
        gmcpEmitter?.sendStylistState(
            sessionId = sessionId,
            currentRace = me.race,
            feeGold = stylistConfig.feeGold,
            playerGold = me.gold,
            races = registry.all(),
        )
    }

    private fun applyStatDelta(
        current: StatMap,
        oldMods: StatMap,
        newMods: StatMap,
    ): StatMap {
        val merged = current.values.toMutableMap()
        val touched = (oldMods.values.keys + newMods.values.keys)
        for (key in touched) {
            val k = key.uppercase()
            val delta = newMods[k] - oldMods[k]
            if (delta != 0) {
                merged[k] = (merged[k] ?: 0) + delta
            }
        }
        return StatMap(merged)
    }

    private fun formatStatMods(mods: StatMap): String {
        val nonZero = mods.nonZero()
        if (nonZero.isEmpty()) return "(no stat change)"
        return nonZero.entries
            .sortedBy { it.key }
            .joinToString(", ", prefix = "(", postfix = ")") { (k, v) ->
                if (v >= 0) "$k +$v" else "$k $v"
            }
    }
}
