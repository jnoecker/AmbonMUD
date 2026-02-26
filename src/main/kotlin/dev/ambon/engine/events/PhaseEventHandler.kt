package dev.ambon.engine.events

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.RegenSystem
import dev.ambon.engine.commands.PhaseResult
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.metrics.GameMetrics
import dev.ambon.sharding.HandoffManager
import dev.ambon.sharding.HandoffResult
import dev.ambon.sharding.PlayerLocationIndex
import dev.ambon.sharding.ZoneInstance
import dev.ambon.sharding.ZoneRegistry
import io.github.oshai.kotlinlogging.KLogger

class PhaseEventHandler(
    private val handoffManager: HandoffManager?,
    private val zoneRegistry: ZoneRegistry?,
    private val players: PlayerRegistry,
    private val combatSystem: CombatSystem,
    private val regenSystem: RegenSystem,
    private val statusEffectSystem: StatusEffectSystem,
    private val playerLocationIndex: PlayerLocationIndex?,
    private val engineId: String,
    private val sendInfo: suspend (SessionId, String) -> Unit,
    private val sendPrompt: suspend (SessionId) -> Unit,
    private val logger: KLogger,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    suspend fun handleCrossZoneMove(
        sessionId: SessionId,
        targetRoomId: RoomId,
    ) {
        metrics.onPhaseHandlerEvent()
        val mgr = handoffManager ?: return
        combatSystem.endCombatFor(sessionId)
        regenSystem.onPlayerDisconnected(sessionId)
        statusEffectSystem.removeAllFromPlayer(sessionId)

        when (val result = mgr.initiateHandoff(sessionId, targetRoomId)) {
            is HandoffResult.Initiated -> {
                logger.info { "Cross-zone handoff initiated to engine=${result.targetEngine.engineId}" }
            }

            HandoffResult.AlreadyInTransit -> {
                sendInfo(sessionId, "You are already crossing into new territory.")
                sendPrompt(sessionId)
            }

            HandoffResult.PlayerNotFound -> {
                logger.warn { "Cross-zone move failed: player not found for session=$sessionId" }
            }

            HandoffResult.NoEngineForZone -> {
                sendInfo(sessionId, "The way shimmers but does not yield.")
                sendPrompt(sessionId)
            }
        }
    }

    suspend fun handlePhase(
        sessionId: SessionId,
        targetHint: String?,
    ): PhaseResult {
        val reg = zoneRegistry ?: return PhaseResult.NotEnabled
        val mgr = handoffManager ?: return PhaseResult.NotEnabled

        val player = players.get(sessionId) ?: return PhaseResult.NoOp("You must be in the world to switch layers.")
        val currentZone = player.roomId.zone
        val instances = reg.instancesOf(currentZone)

        if (instances.size <= 1) {
            return PhaseResult.NoOp("There is only one instance of this zone.")
        }

        if (targetHint == null) {
            return PhaseResult.InstanceList(
                currentEngineId = engineId,
                instances = instances,
            )
        }

        val resolvedInstance =
            resolvePhaseTarget(targetHint, instances)
                ?: return PhaseResult.NoOp("Unknown instance or player: $targetHint")

        if (resolvedInstance.engineId == engineId) {
            return PhaseResult.NoOp("You are already on that instance.")
        }

        combatSystem.endCombatFor(sessionId)
        regenSystem.onPlayerDisconnected(sessionId)
        statusEffectSystem.removeAllFromPlayer(sessionId)
        return when (mgr.initiateHandoff(sessionId, player.roomId, targetEngineOverride = resolvedInstance.address)) {
            is HandoffResult.Initiated -> PhaseResult.Initiated
            HandoffResult.AlreadyInTransit -> PhaseResult.NoOp("You are already crossing into new territory.")
            HandoffResult.PlayerNotFound -> PhaseResult.NoOp("Could not find your player data.")
            HandoffResult.NoEngineForZone -> PhaseResult.NoOp("That instance is no longer available.")
        }
    }

    private suspend fun resolvePhaseTarget(
        hint: String,
        instances: List<ZoneInstance>,
    ): ZoneInstance? {
        instances.firstOrNull { it.engineId == hint }?.let { return it }

        val targetEngineId = playerLocationIndex?.lookupEngineId(hint)
        if (targetEngineId != null) {
            instances.firstOrNull { it.engineId == targetEngineId }?.let { return it }
        }

        val idx = hint.toIntOrNull()
        if (idx != null && idx in 1..instances.size) {
            return instances[idx - 1]
        }

        return null
    }
}
