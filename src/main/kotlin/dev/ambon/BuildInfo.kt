package dev.ambon

/**
 * The engine build's identity. The Docker image is built with `--build-arg GIT_SHA=<commit>` and
 * exposes it as `AMBONMUD_BUILD_SHA`; a local run may pass `-Dambon.build.sha=<commit>`; anything else
 * reads `unknown`.
 */
object BuildInfo {
    val sha: String =
        System.getProperty("ambon.build.sha")?.takeIf { it.isNotBlank() }
            ?: System.getenv("AMBONMUD_BUILD_SHA")?.takeIf { it.isNotBlank() }
            ?: "unknown"
}
