package dev.ambon.swarm

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val cli = CliArgs.parse(args.toList())
    if (cli.help) {
        println(CliArgs.helpText)
        return
    }
    val configPath = cli.configPath ?: errorAndExit("Missing --config <path>")
    val config = loadConfig(configPath)

    if (cli.validateOnly) {
        println("Config OK: $configPath")
        return
    }

    runBlocking {
        val runner = SwarmRunner(config)
        runner.run()
    }
}

private fun loadConfig(path: String): SwarmConfig {
    val mapper = ObjectMapper(YAMLFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val root = mapper.readTree(java.io.File(path))

    val target = root.path("target")
    val run = root.path("run")
    val behavior = root.path("behavior")

    val commandInterval = behavior.path("commandIntervalMs")
    val weights = behavior.path("weights")

    val config =
        SwarmConfig(
            target =
                TargetConfig(
                    host = target.text("host", "127.0.0.1"),
                    telnetPort = target.int("telnetPort", 4000),
                    websocketUrl = target.text("websocketUrl", "ws://127.0.0.1:8080/ws"),
                ),
            run =
                RunConfig(
                    totalBots = run.int("totalBots", 100),
                    rampSeconds = run.int("rampSeconds", 30),
                    durationSeconds = run.int("durationSeconds", 120),
                    seed = run.longOrNull("seed"),
                    deterministic = run.bool("deterministic", false),
                    namespacePrefix = run.text("namespacePrefix", "swarm"),
                    basePassword = run.text("basePassword", "swarmpass"),
                    protocolMix = ProtocolMixConfig(telnetPercent = run.path("protocolMix").int("telnetPercent", 70)),
                ),
            behavior =
                BehaviorConfig(
                    commandIntervalMs =
                        IntRangeConfig(
                            min = commandInterval.int("min", 800),
                            max = commandInterval.int("max", 2200),
                        ),
                    weights =
                        BehaviorWeights(
                            idle = weights.int("idle", 20),
                            loginChurn = weights.int("loginChurn", 10),
                            movement = weights.int("movement", 35),
                            chat = weights.int("chat", 20),
                            autoCombat = weights.int("autoCombat", 15),
                        ),
                    chatPhrases = behavior.strings("chatPhrases", BehaviorConfig().chatPhrases),
                    movementCommands = behavior.strings("movementCommands", BehaviorConfig().movementCommands),
                    combatCommands = behavior.strings("combatCommands", BehaviorConfig().combatCommands),
                ),
        )
    return config.validated()
}

private fun JsonNode.int(
    field: String,
    default: Int,
): Int = path(field).takeIf { !it.isMissingNode && !it.isNull }?.asInt(default) ?: default

private fun JsonNode.longOrNull(field: String): Long? {
    val node = path(field)
    return if (node.isMissingNode || node.isNull) null else node.asLong()
}

private fun JsonNode.bool(
    field: String,
    default: Boolean,
): Boolean = path(field).takeIf { !it.isMissingNode && !it.isNull }?.asBoolean(default) ?: default

private fun JsonNode.text(
    field: String,
    default: String,
): String = path(field).takeIf { !it.isMissingNode && !it.isNull }?.asText(default) ?: default

private fun JsonNode.strings(
    field: String,
    default: List<String>,
): List<String> {
    val node = path(field)
    if (node.isMissingNode || node.isNull || !node.isArray) return default
    return node.mapNotNull { if (it.isTextual) it.asText() else null }.ifEmpty { default }
}

private fun errorAndExit(message: String): Nothing {
    System.err.println(message)
    System.err.println(CliArgs.helpText)
    exitProcess(2)
}

private data class CliArgs(
    val configPath: String?,
    val validateOnly: Boolean,
    val help: Boolean,
) {
    companion object {
        val helpText: String =
            """
            Swarm Load Tester
            Usage:
              ./gradlew :swarm:run --args="--config example.swarm.yaml"
              ./gradlew :swarm:run --args="--config example.swarm.yaml --validate"

            Flags:
              --config <path>   YAML config file path
              --validate        Validate config and exit
              --help            Show this message
            """.trimIndent()

        fun parse(args: List<String>): CliArgs {
            var config: String? = null
            var validate = false
            var help = false
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--config" -> {
                        val next = args.getOrNull(i + 1) ?: throw IllegalArgumentException("--config requires a value")
                        config = next
                        i += 2
                    }

                    "--validate" -> {
                        validate = true
                        i += 1
                    }

                    "--help", "-h" -> {
                        help = true
                        i += 1
                    }

                    else -> throw IllegalArgumentException("Unknown argument: ${args[i]}")
                }
            }
            return CliArgs(configPath = config, validateOnly = validate, help = help)
        }
    }
}
