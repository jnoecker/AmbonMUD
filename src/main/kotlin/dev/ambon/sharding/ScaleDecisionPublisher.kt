package dev.ambon.sharding

import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Receives scaling decisions and publishes them to an external system.
 */
interface ScaleDecisionPublisher {
    fun publish(decisions: List<ScaleDecision>)
}

/**
 * Logs scaling decisions to the application logger. Suitable for
 * development and operators who watch log output.
 */
class LoggingScaleDecisionPublisher : ScaleDecisionPublisher {
    override fun publish(decisions: List<ScaleDecision>) {
        for (decision in decisions) {
            when (decision) {
                is ScaleDecision.ScaleUp -> {
                    log.info { "SCALE-UP zone=${decision.zone}: ${decision.reason}" }
                }
                is ScaleDecision.ScaleDown -> {
                    log.info {
                        "SCALE-DOWN zone=${decision.zone} engine=${decision.engineId}: ${decision.reason}"
                    }
                }
            }
        }
    }
}

/**
 * Publishes scaling decisions to a Redis pub/sub channel so an
 * external orchestrator can react. Falls back to logging if the
 * Redis command fails.
 */
class RedisScaleDecisionPublisher(
    private val redisCommands: io.lettuce.core.api.sync.RedisCommands<String, String>,
    private val channel: String = DEFAULT_CHANNEL,
) : ScaleDecisionPublisher {
    override fun publish(decisions: List<ScaleDecision>) {
        for (decision in decisions) {
            val payload =
                when (decision) {
                    is ScaleDecision.ScaleUp ->
                        """{"type":"scale_up","zone":"${escapeJson(decision.zone)}","reason":"${escapeJson(decision.reason)}"}"""
                    is ScaleDecision.ScaleDown ->
                        """{"type":"scale_down","zone":"${escapeJson(
                            decision.zone,
                        )}","engineId":"${escapeJson(decision.engineId)}","reason":"${escapeJson(
                            decision.reason,
                        )}"}"""
                }
            try {
                redisCommands.publish(channel, payload)
            } catch (e: Exception) {
                log.warn(e) { "Failed to publish scale decision to Redis channel=$channel" }
            }
        }
    }

    companion object {
        const val DEFAULT_CHANNEL = "ambon:scaling:decisions"
    }
}

private fun escapeJson(s: String): String =
    s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
