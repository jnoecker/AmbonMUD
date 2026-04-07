package dev.ambon.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.addEnvironmentSource
import com.sksamuel.hoplite.addResourceSource
import java.io.File

object AppConfigLoader {
    private const val DEFAULT_RESOURCE_PATH = "/application.yaml"
    private const val LOCAL_OVERRIDE_FILENAME = "application-local.yaml"
    private const val LOCAL_OVERRIDE_CLASSPATH = "/$LOCAL_OVERRIDE_FILENAME"

    // Environment variable naming convention for container deployments:
    //   AMBONMUD_MODE              → ambonmud.mode
    //   AMBONMUD_PERSISTENCE_BACKEND → ambonmud.persistence.backend
    //   AMBONMUD_DATABASE_JDBCURL  → ambonmud.database.jdbcUrl
    //   AMBONMUD_REDIS_URI         → ambonmud.redis.uri
    //   AMBONMUD_REDIS_BUS_ENABLED → ambonmud.redis.bus.enabled
    //   AMBONMUD_SHARDING_ENABLED  → ambonmud.sharding.enabled
    //   AMBONMUD_SHARDING_REGISTRY_TYPE → ambonmud.sharding.registry.type
    //   AMBONMUD_GRPC_CLIENT_ENGINEHOST → ambonmud.grpc.client.engineHost
    // Hoplite lowercases all keys and replaces _ with . for path segments.
    // NOTE: The root YAML key and AmbonMUDRootConfig field are both `ambonmud`
    // (all-lowercase) so that Hoplite's env-var normalisation resolves correctly.
    //
    // Config source priority (highest → lowest):
    //   1. Environment variables
    //   2. Extra sources (programmatic)
    //   3. Profile overlay  (-Dambon.profile=<name>)
    //   4. Local overlay    (application-local.yaml — $AMBONMUD_DATA_DIR, CWD, or classpath)
    //   5. Base defaults    (application.yaml — checked in, generic/tech defaults)
    //
    // The local overlay is checked in three locations, in order:
    //   a. $AMBONMUD_DATA_DIR/application-local.yaml (container deployments with
    //      a bind-mounted data volume — the fetch-world-zones systemd unit writes
    //      the overlay to /app/data/application-local.yaml and the docker run
    //      passes AMBONMUD_DATA_DIR=/app/data, so this picks it up.)
    //   b. ./application-local.yaml in the JVM working directory (legacy)
    //   c. Classpath resource /application-local.yaml (for local dev with the file
    //      in src/main/resources/)
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

        // Local config file (git-ignored) — a complete standalone config produced
        // by the deployment pipeline.  When present it REPLACES the base
        // application.yaml entirely (no deep-merge), so every field must be
        // defined there.  When absent the checked-in application.yaml provides
        // sensible defaults for local development.
        val dataDirOverlay =
            System
                .getenv("AMBONMUD_DATA_DIR")
                ?.let { File(it, LOCAL_OVERRIDE_FILENAME) }
                ?.takeIf { it.isFile }
        val cwdLocalFile = File(LOCAL_OVERRIDE_FILENAME)
        val localOnClasspath =
            AppConfigLoader::class.java.getResource(LOCAL_OVERRIDE_CLASSPATH) != null

        if (dataDirOverlay != null) {
            builder = builder.addSource(PropertySource.file(dataDirOverlay, optional = false))
        } else if (cwdLocalFile.isFile) {
            builder = builder.addSource(PropertySource.file(cwdLocalFile, optional = false))
        } else if (localOnClasspath) {
            builder = builder.addResourceSource(LOCAL_OVERRIDE_CLASSPATH, optional = false)
        } else {
            builder = builder.addResourceSource(resourcePath, optional = false)
        }

        return builder
            .build()
            .loadConfigOrThrow<AmbonMUDRootConfig>()
            .ambonmud
            .validated()
    }
}
