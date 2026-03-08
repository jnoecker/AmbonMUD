package dev.ambon.domain.world.data

data class BehaviorFile(
    val template: String? = null,
    val params: BehaviorParamsFile = BehaviorParamsFile(),
    /** Inline tree definition — alternative to using a named template. */
    val tree: BehaviorNodeFile? = null,
)

data class BehaviorParamsFile(
    val patrolRoute: List<String> = emptyList(),
    val fleeHpPercent: Int = 20,
    val aggroMessage: String? = null,
    val fleeMessage: String? = null,
    val maxWanderDistance: Int = 3,
)

/**
 * YAML representation of a behavior tree node. Supports composing trees
 * from existing node primitives without writing Kotlin code.
 *
 * Example YAML:
 * ```yaml
 * behavior:
 *   tree:
 *     type: selector
 *     children:
 *       - type: is_in_combat
 *       - type: sequence
 *         children:
 *           - type: is_player_in_room
 *           - type: say
 *             message: "Halt!"
 *           - type: aggro
 *       - type: stationary
 * ```
 */
data class BehaviorNodeFile(
    val type: String = "",
    val children: List<BehaviorNodeFile> = emptyList(),
    // Action/condition parameters
    val message: String? = null,
    val route: List<String> = emptyList(),
    val maxDistance: Int = 3,
    val percent: Int = 20,
    val cooldownMs: Long = 0L,
    val key: String = "",
)
