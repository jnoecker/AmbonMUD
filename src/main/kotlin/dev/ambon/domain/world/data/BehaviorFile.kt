package dev.ambon.domain.world.data

data class BehaviorFile(
    val template: String,
    val params: BehaviorParamsFile = BehaviorParamsFile(),
)

data class BehaviorParamsFile(
    val patrolRoute: List<String> = emptyList(),
    val fleeHpPercent: Int = 20,
    val aggroMessage: String? = null,
    val fleeMessage: String? = null,
)
