package dev.ambon.engine.behavior

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.qualifyId
import dev.ambon.domain.world.data.BehaviorNodeFile
import dev.ambon.engine.behavior.actions.AggroAction
import dev.ambon.engine.behavior.actions.FleeAction
import dev.ambon.engine.behavior.actions.PatrolAction
import dev.ambon.engine.behavior.actions.SayAction
import dev.ambon.engine.behavior.actions.StationaryAction
import dev.ambon.engine.behavior.actions.WanderAction
import dev.ambon.engine.behavior.conditions.IsHpBelow
import dev.ambon.engine.behavior.conditions.IsInCombat
import dev.ambon.engine.behavior.conditions.IsPlayerInRoom
import dev.ambon.engine.behavior.nodes.CooldownNode
import dev.ambon.engine.behavior.nodes.InverterNode
import dev.ambon.engine.behavior.nodes.SelectorNode
import dev.ambon.engine.behavior.nodes.SequenceNode

/**
 * Builds a [BtNode] tree from a YAML [BehaviorNodeFile] definition.
 *
 * Supports all built-in node types:
 * - Control: `selector`, `sequence`, `inverter`, `cooldown`
 * - Conditions: `is_in_combat`, `is_player_in_room`, `is_hp_below`
 * - Actions: `stationary`, `say`, `aggro`, `flee`, `patrol`, `wander`
 */
object BehaviorTreeLoader {
    val knownNodeTypes: Set<String> = setOf(
        "selector",
        "sequence",
        "inverter",
        "cooldown",
        "is_in_combat",
        "is_player_in_room",
        "is_hp_below",
        "stationary",
        "say",
        "aggro",
        "flee",
        "patrol",
        "wander",
    )

    fun load(
        nodeFile: BehaviorNodeFile,
        zone: String,
    ): BtNode {
        val type = nodeFile.type.lowercase()
        return when (type) {
            // Control flow nodes
            "selector" -> SelectorNode(nodeFile.children.map { load(it, zone) })
            "sequence" -> SequenceNode(nodeFile.children.map { load(it, zone) })
            "inverter" -> {
                require(nodeFile.children.size == 1) {
                    "Inverter node must have exactly 1 child, got ${nodeFile.children.size}"
                }
                InverterNode(load(nodeFile.children.first(), zone))
            }
            "cooldown" -> {
                require(nodeFile.children.size == 1) {
                    "Cooldown node must have exactly 1 child, got ${nodeFile.children.size}"
                }
                require(nodeFile.cooldownMs > 0) { "Cooldown node requires cooldownMs > 0" }
                val key = nodeFile.key.ifEmpty { "cooldown_${nodeFile.hashCode()}" }
                CooldownNode(nodeFile.cooldownMs, key, load(nodeFile.children.first(), zone))
            }

            // Condition nodes
            "is_in_combat" -> IsInCombat
            "is_player_in_room" -> IsPlayerInRoom
            "is_hp_below" -> IsHpBelow(nodeFile.percent)

            // Action nodes
            "stationary" -> StationaryAction
            "say" -> {
                requireNotNull(nodeFile.message) { "Say node requires a 'message' parameter" }
                SayAction(nodeFile.message)
            }
            "aggro" -> AggroAction
            "flee" -> FleeAction
            "patrol" -> {
                require(nodeFile.route.isNotEmpty()) { "Patrol node requires a non-empty 'route'" }
                PatrolAction(nodeFile.route.map { RoomId(qualifyId(zone, it)) })
            }
            "wander" -> WanderAction(nodeFile.maxDistance)

            else -> throw IllegalArgumentException(
                "Unknown behavior tree node type '$type'. " +
                    "Known types: ${knownNodeTypes.sorted().joinToString(", ")}",
            )
        }
    }
}
