package dev.ambon.engine.behavior

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.data.BehaviorParamsFile
import dev.ambon.engine.behavior.actions.AggroAction
import dev.ambon.engine.behavior.actions.FleeAction
import dev.ambon.engine.behavior.actions.PatrolAction
import dev.ambon.engine.behavior.actions.SayAction
import dev.ambon.engine.behavior.actions.StationaryAction
import dev.ambon.engine.behavior.actions.WanderAction
import dev.ambon.engine.behavior.conditions.IsHpBelow
import dev.ambon.engine.behavior.conditions.IsInCombat
import dev.ambon.engine.behavior.conditions.IsPlayerInRoom
import dev.ambon.engine.behavior.nodes.SelectorNode
import dev.ambon.engine.behavior.nodes.SequenceNode

object BehaviorTemplates {
    val templateNames: Set<String> =
        setOf(
            "aggro_guard",
            "stationary_aggro",
            "patrol",
            "patrol_aggro",
            "wander",
            "wander_aggro",
            "coward",
        )

    fun resolve(
        name: String,
        params: BehaviorParamsFile,
        zone: String,
    ): BtNode? =
        when (name.lowercase()) {
            "aggro_guard" -> aggroGuard(params)
            "stationary_aggro" -> stationaryAggro(params)
            "patrol" -> patrol(params, zone)
            "patrol_aggro" -> patrolAggro(params, zone)
            "wander" -> wander()
            "wander_aggro" -> wanderAggro(params)
            "coward" -> coward(params)
            else -> null
        }

    private fun aggroGuard(params: BehaviorParamsFile): BtNode =
        SelectorNode(
            listOf(
                IsInCombat,
                aggroSequence(params),
                StationaryAction,
            ),
        )

    private fun stationaryAggro(params: BehaviorParamsFile): BtNode =
        SelectorNode(
            listOf(
                IsInCombat,
                aggroSequence(params),
                StationaryAction,
            ),
        )

    private fun patrol(
        params: BehaviorParamsFile,
        zone: String,
    ): BtNode =
        SelectorNode(
            listOf(
                IsInCombat,
                PatrolAction(normalizeRoute(params.patrolRoute, zone)),
            ),
        )

    private fun patrolAggro(
        params: BehaviorParamsFile,
        zone: String,
    ): BtNode =
        SelectorNode(
            listOf(
                IsInCombat,
                aggroSequence(params),
                PatrolAction(normalizeRoute(params.patrolRoute, zone)),
            ),
        )

    private fun wander(): BtNode =
        SelectorNode(
            listOf(
                IsInCombat,
                WanderAction,
            ),
        )

    private fun wanderAggro(params: BehaviorParamsFile): BtNode =
        SelectorNode(
            listOf(
                IsInCombat,
                aggroSequence(params),
                WanderAction,
            ),
        )

    private fun coward(params: BehaviorParamsFile): BtNode {
        val fleeChildren = mutableListOf<BtNode>()
        fleeChildren.add(IsInCombat)
        fleeChildren.add(IsHpBelow(params.fleeHpPercent))
        if (params.fleeMessage != null) {
            fleeChildren.add(SayAction(params.fleeMessage))
        }
        fleeChildren.add(FleeAction)

        return SelectorNode(
            listOf(
                SequenceNode(fleeChildren),
                WanderAction,
            ),
        )
    }

    private fun aggroSequence(params: BehaviorParamsFile): BtNode {
        val children = mutableListOf<BtNode>()
        children.add(IsPlayerInRoom)
        if (params.aggroMessage != null) {
            children.add(SayAction(params.aggroMessage))
        }
        children.add(AggroAction)
        return SequenceNode(children)
    }

    private fun normalizeRoute(
        route: List<String>,
        zone: String,
    ): List<RoomId> =
        route.map { raw ->
            val id = if (':' in raw) raw else "$zone:$raw"
            RoomId(id)
        }
}
