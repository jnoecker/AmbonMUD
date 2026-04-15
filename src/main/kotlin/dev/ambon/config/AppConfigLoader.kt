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

    // Secrets overlay — a separate YAML file holding values (admin token, DB
    // passwords, etc) that must NOT appear in the publicly-fetched creator
    // overlay. Populated outside the JVM by the deployment pipeline (systemd
    // ExecStartPre fetches the admin token from SSM Parameter Store and writes
    // secrets.yaml alongside the creator overlay). Takes precedence over every
    // other source so real secret values overwrite whatever placeholder the
    // creator tool emits.
    //
    // Lookup order:
    //   1. $AMBONMUD_SECRETS_FILE (explicit path)
    //   2. $AMBONMUD_DATA_DIR/secrets.yaml
    //   3. ./secrets.yaml in the JVM working directory (local dev)
    // If none exist, the overlay simply isn't added — secrets fall back to
    // whatever the env source or the creator overlay provides.
    private const val SECRETS_FILENAME = "secrets.yaml"
    private const val SECRETS_PATH_ENV = "AMBONMUD_SECRETS_FILE"

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
    //   1. Secrets overlay  (secrets.yaml — production secrets injected by
    //                        the deployment pipeline; see SECRETS_FILENAME)
    //   2. Environment variables
    //   3. Extra sources (programmatic)
    //   4. Profile overlay  (-Dambon.profile=<name>)
    //   5. Local overlay    (application-local.yaml — $AMBONMUD_DATA_DIR, CWD, or classpath)
    //   6. Base defaults    (application.yaml — checked in, generic/tech defaults)
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

        // Secrets overlay takes precedence over every other source (lowest
        // index = first checked). See SECRETS_FILENAME doc above for why.
        val secretsFile = resolveSecretsFile()
        if (secretsFile != null) {
            builder = builder.addSource(PropertySource.file(secretsFile, optional = false))
        }

        // Environment variables come next. Used as a fallback override path for
        // local dev and ad-hoc tweaks.
        builder = builder.addEnvironmentSource()

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

    private fun resolveSecretsFile(): File? {
        // System property is checked first for test-friendliness — tests can't
        // easily set env vars in the JVM, matching the existing `ambon.profile`
        // pattern used above.
        System.getProperty("ambon.secretsFile")?.let { path ->
            val f = File(path)
            if (f.isFile) return f
        }
        System.getenv(SECRETS_PATH_ENV)?.let { path ->
            val f = File(path)
            if (f.isFile) return f
        }
        System.getenv("AMBONMUD_DATA_DIR")?.let { dir ->
            val f = File(dir, SECRETS_FILENAME)
            if (f.isFile) return f
        }
        val cwd = File(SECRETS_FILENAME)
        if (cwd.isFile) return cwd
        return null
    }
}
