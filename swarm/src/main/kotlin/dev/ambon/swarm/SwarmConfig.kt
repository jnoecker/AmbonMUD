package dev.ambon.swarm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SwarmConfig(
    val target: TargetConfig = TargetConfig(),
    val run: RunConfig = RunConfig(),
    val behavior: BehaviorConfig = BehaviorConfig(),
) {
    fun validated(): SwarmConfig {
        require(target.host.isNotBlank()) { "target.host must be non-blank" }
        require(target.telnetPort in 1..65535) { "target.telnetPort must be between 1 and 65535" }
        require(target.websocketUrl.startsWith("ws://") || target.websocketUrl.startsWith("wss://")) {
            "target.websocketUrl must start with ws:// or wss://"
        }
        require(run.totalBots > 0) { "run.totalBots must be > 0" }
        require(run.durationSeconds > 0) { "run.durationSeconds must be > 0" }
        require(run.rampSeconds >= 0) { "run.rampSeconds must be >= 0" }
        require(run.protocolMix.telnetPercent in 0..100) { "run.protocolMix.telnetPercent must be in 0..100" }
        require(run.namespacePrefix.matches(Regex("[A-Za-z_][A-Za-z0-9_]{1,15}"))) {
            "run.namespacePrefix must match player-name constraints (2..16 chars, alnum/underscore, not starting with digit)"
        }
        require(run.basePassword.isNotBlank() && run.basePassword.length <= 72) {
            "run.basePassword must be non-blank and <= 72 chars"
        }
        behavior.weights.validated()
        require(behavior.commandIntervalMs.min in 1..60_000) { "behavior.commandIntervalMs.min must be 1..60000" }
        require(behavior.commandIntervalMs.max in behavior.commandIntervalMs.min..60_000) {
            "behavior.commandIntervalMs.max must be >= min and <= 60000"
        }
        return this
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class TargetConfig(
    val host: String = "127.0.0.1",
    val telnetPort: Int = 4000,
    val websocketUrl: String = "ws://127.0.0.1:8080/ws",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RunConfig(
    val totalBots: Int = 100,
    val rampSeconds: Int = 30,
    val durationSeconds: Int = 120,
    val seed: Long? = null,
    val deterministic: Boolean = false,
    val namespacePrefix: String = "swarm",
    val basePassword: String = "swarmpass",
    val protocolMix: ProtocolMixConfig = ProtocolMixConfig(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProtocolMixConfig(
    val telnetPercent: Int = 70,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BehaviorConfig(
    val commandIntervalMs: IntRangeConfig = IntRangeConfig(800, 2200),
    val weights: BehaviorWeights = BehaviorWeights(),
    val chatPhrases: List<String> = listOf("hello", "lag?", "nice room", "test ping"),
    val movementCommands: List<String> = listOf("north", "south", "east", "west", "look"),
    val combatCommands: List<String> = listOf("kill rat", "kill goblin", "attack rat"),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BehaviorWeights(
    val idle: Int = 20,
    val loginChurn: Int = 10,
    val movement: Int = 35,
    val chat: Int = 20,
    val autoCombat: Int = 15,
) {
    fun validated() {
        val all = listOf(idle, loginChurn, movement, chat, autoCombat)
        require(all.all { it >= 0 }) { "behavior.weights values must be >= 0" }
        require(all.sum() > 0) { "behavior.weights sum must be > 0" }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class IntRangeConfig(
    val min: Int,
    val max: Int,
)
