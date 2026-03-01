package dev.ambon.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.addEnvironmentSource
import com.sksamuel.hoplite.addResourceSource

object AppConfigLoader {
    private const val DEFAULT_RESOURCE_PATH = "/application.yaml"

    // Environment variable naming convention for container deployments:
    //   AMBONMUD_MODE              → ambonMUD.mode
    //   AMBONMUD_PERSISTENCE_BACKEND → ambonMUD.persistence.backend
    //   AMBONMUD_DATABASE_JDBCURL  → ambonMUD.database.jdbcUrl
    //   AMBONMUD_REDIS_URI         → ambonMUD.redis.uri
    //   AMBONMUD_REDIS_BUS_ENABLED → ambonMUD.redis.bus.enabled
    //   AMBONMUD_SHARDING_ENABLED  → ambonMUD.sharding.enabled
    //   AMBONMUD_SHARDING_REGISTRY_TYPE → ambonMUD.sharding.registry.type
    //   AMBONMUD_GRPC_CLIENT_ENGINEHOST → ambonMUD.grpc.client.engineHost
    // Hoplite lowercases all keys and replaces _ with . for path segments.
    @OptIn(ExperimentalHoplite::class)
    fun load(
        resourcePath: String = DEFAULT_RESOURCE_PATH,
        extraSources: List<PropertySource> = emptyList(),
    ): AppConfig {
        var builder =
            ConfigLoaderBuilder
                .default()
                .withExplicitSealedTypes()
                // Environment variables are highest priority (lowest index = first checked)
                .addEnvironmentSource()

        for (source in extraSources) {
            builder = builder.addSource(source)
        }

        val profileName = System.getProperty("ambon.profile")
        if (profileName != null) {
            builder = builder.addSource(PropertySource.resource("/application-$profileName.yaml", optional = true))
        }

        builder = builder.addResourceSource(resourcePath, optional = false)

        return builder
            .build()
            .loadConfigOrThrow<AmbonMUDRootConfig>()
            .ambonMUD
            .validated()
    }
}
