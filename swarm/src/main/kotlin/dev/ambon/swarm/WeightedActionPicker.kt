package dev.ambon.swarm

import kotlin.random.Random

enum class BotAction {
    IDLE,
    LOGIN_CHURN,
    MOVEMENT,
    CHAT,
    AUTO_COMBAT,
}

object WeightedActionPicker {
    fun pick(
        weights: BehaviorWeights,
        random: Random,
    ): BotAction {
        val entries =
            listOf(
                BotAction.IDLE to weights.idle,
                BotAction.LOGIN_CHURN to weights.loginChurn,
                BotAction.MOVEMENT to weights.movement,
                BotAction.CHAT to weights.chat,
                BotAction.AUTO_COMBAT to weights.autoCombat,
            )
        val total = entries.sumOf { it.second }
        require(total > 0) { "total weight must be > 0" }

        var target = random.nextInt(total)
        for ((action, weight) in entries) {
            if (weight <= 0) continue
            if (target < weight) return action
            target -= weight
        }
        return BotAction.IDLE
    }
}
