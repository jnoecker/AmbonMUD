package dev.ambon.engine.commands.handlers

import dev.ambon.config.AkathavaeConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock

/**
 * The Akathavae pledge: the entry and exit points of the pacifist explorer path.
 *
 * At an Akathavae shrine (`akathavaeShrine: true` room flag), `pledge` freely binds
 * the player to the vow of the chroniclers of the Arcanum — combat is forbidden and
 * progression flows from illuminating the world instead. `renounce confirm` breaks
 * the vow at a shrine for a gold price, with a cooldown before re-pledging so the
 * two paths can't be flipped between to double-dip rewards.
 */
class AkathavaeHandler(
    ctx: EngineContext,
    private val config: AkathavaeConfig = AkathavaeConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val markVitalsDirty: ((SessionId) -> Unit)? = null,
) : CommandHandler {
    private val players = ctx.players
    private val world = ctx.world
    private val outbound = ctx.outbound
    private val combat = ctx.combat
    private val metrics = ctx.metrics

    override fun register(router: CommandRouter) {
        router.on<Command.Pledge> { sid, _ -> handlePledge(sid) }
        router.on<Command.Renounce> { sid, cmd -> handleRenounce(sid, cmd) }
    }

    private suspend fun requireShrine(sessionId: SessionId, me: PlayerState): Boolean {
        if (!config.enabled) {
            outbound.send(OutboundEvent.SendError(sessionId, "The Akathavae do not accept pledges in this world."))
            return false
        }
        val room = world.rooms[me.roomId]
        if (room == null || !room.akathavaeShrine) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Only at an Akathavae shrine can such a vow be spoken or unsaid.",
                ),
            )
            return false
        }
        return true
    }

    private suspend fun handlePledge(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        if (!requireShrine(sessionId, me)) return

        if (me.isAkathavae) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You have already given your voice to the Arcanum."))
            return
        }
        if (combat.isInCombat(sessionId)) {
            outbound.send(
                OutboundEvent.SendError(sessionId, "You cannot pledge peace with blood on your hands. Finish or flee this fight first."),
            )
            return
        }

        val now = clock.millis()
        val cooldownEndsAt = me.akathavaeRenouncedAtMs + config.repledgeCooldownMs
        if (me.akathavaeRenouncedAtMs > 0 && now < cooldownEndsAt) {
            val hoursLeft = ((cooldownEndsAt - now) + HOUR_MS - 1) / HOUR_MS
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "The Arcanum remembers your renunciation. The Akathavae will hear your pledge again in about $hoursLeft hour${if (hoursLeft == 1L) "" else "s"}.",
                ),
            )
            return
        }

        me.isAkathavae = true
        me.akathavaePledgedAtMs = now
        metrics.onGameEvent("akathavae", "pledge")
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "You kneel before the shrine and speak the Pledge of the Akathavae. " +
                    "A hush settles over you: your weapons feel like strangers' tools, and the world " +
                    "sharpens into something worth recording. You are now a keeper of the Arcanum — " +
                    "go and illuminate what you find.",
            ),
        )
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "While pledged you cannot fight. Visit new places, observe creatures, and discover items to earn experience.",
            ),
        )
        broadcastToRoomExcept(
            roomId = me.roomId,
            excludeSessionId = sessionId,
            message = "${me.name} kneels at the shrine and takes the Pledge of the Akathavae.",
            players = players,
            outbound = outbound,
        )
        markVitalsDirty?.invoke(sessionId)
    }

    private suspend fun handleRenounce(sessionId: SessionId, cmd: Command.Renounce) {
        val me = players.get(sessionId) ?: return
        if (!me.isAkathavae) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are not bound by the Akathavae pledge."))
            return
        }
        if (!requireShrine(sessionId, me)) return

        if (!cmd.confirm) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "Renouncing the pledge costs ${config.renounceCostGold} gold and the Akathavae will not " +
                        "accept a new pledge from you for a time. Your Arcanum is kept — but it earns nothing while you bear arms.",
                ),
            )
            outbound.send(OutboundEvent.SendInfo(sessionId, "Type 'renounce confirm' if you are certain."))
            return
        }
        if (me.gold < config.renounceCostGold) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Breaking a sacred vow demands an offering of ${config.renounceCostGold} gold — you have only ${me.gold}.",
                ),
            )
            return
        }

        me.gold -= config.renounceCostGold
        me.isAkathavae = false
        me.akathavaeRenouncedAtMs = clock.millis()
        metrics.onGameEvent("akathavae", "renounce")
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "You lay ${config.renounceCostGold} gold upon the shrine and unsay your vow. " +
                    "The hush lifts. Your hands remember the weight of weapons.",
            ),
        )
        broadcastToRoomExcept(
            roomId = me.roomId,
            excludeSessionId = sessionId,
            message = "${me.name} lays an offering on the shrine and renounces the Pledge of the Akathavae.",
            players = players,
            outbound = outbound,
        )
        markVitalsDirty?.invoke(sessionId)
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
    }
}
