package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectSystem

class CombatHandler(
    router: CommandRouter,
    ctx: EngineContext,
    private val abilitySystem: AbilitySystem? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val dialogueSystem: DialogueSystem? = null,
) {
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val combat = ctx.combat
    private val outbound = ctx.outbound

    init {
        router.on<Command.Kill> { sid, cmd -> handleKill(sid, cmd) }
        router.on<Command.Flee> { sid, _ -> handleFlee(sid) }
        router.on<Command.Cast> { sid, cmd -> handleCast(sid, cmd) }
    }

    private suspend fun handleKill(
        sessionId: SessionId,
        cmd: Command.Kill,
    ) {
        dialogueSystem?.endConversation(sessionId)
        val err = combat.startCombat(sessionId, cmd.target)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        }
    }

    private suspend fun handleFlee(sessionId: SessionId) {
        val err = combat.flee(sessionId)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        }
    }

    private suspend fun handleCast(
        sessionId: SessionId,
        cmd: Command.Cast,
    ) {
        if (abilitySystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Abilities are not available."))
            return
        }
        val err = abilitySystem.cast(sessionId, cmd.spellName, cmd.target)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        }
    }
}
