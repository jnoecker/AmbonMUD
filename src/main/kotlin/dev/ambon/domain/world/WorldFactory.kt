package dev.ambon.domain.world

import dev.ambon.config.MobTiersConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.load.WorldLoadException
import dev.ambon.domain.world.load.WorldLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.jar.JarFile

private val log = KotlinLogging.logger {}

object WorldFactory {
    fun demoWorld(
        resources: List<String> = emptyList(),
        tiers: MobTiersConfig = MobTiersConfig(),
        zoneFilter: Set<String> = emptySet(),
        startRoom: RoomId? = null,
    ): World {
        val paths = resources.ifEmpty { discoverClasspathZones() }
        if (paths.isEmpty()) throw WorldLoadException("No zone files found — classpath 'world/' directory is empty")
        return WorldLoader.loadFromResources(paths, tiers, zoneFilter, startRoom)
    }

    /**
     * Scans the [dir] classpath directory for YAML files that contain a top-level `zone:` key.
     * Works for both exploded classpath (IDE / `./gradlew run`) and fat-JAR deployments.
     */
    private fun discoverClasspathZones(dir: String = "world"): List<String> {
        val classLoader = Thread.currentThread().contextClassLoader
        val url = classLoader.getResource(dir) ?: return emptyList()
        val candidates =
            when (url.protocol) {
                "file" ->
                    File(url.toURI())
                        .listFiles { f -> f.name.endsWith(".yaml") }
                        ?.map { "$dir/${it.name}" }
                        ?.sorted()
                        ?: emptyList()
                "jar" -> {
                    val jarPath = url.path.substringBefore("!").removePrefix("file:")
                    JarFile(jarPath).use { jar ->
                        jar
                            .entries()
                            .asSequence()
                            .filter { !it.isDirectory && it.name.startsWith("$dir/") && it.name.endsWith(".yaml") }
                            .map { it.name }
                            .sorted()
                            .toList()
                    }
                }
                else -> emptyList()
            }
        return candidates.filter { isZoneFile(it, classLoader) }.also {
            log.info { "Auto-discovered ${it.size} zone file(s) from classpath '$dir/': $it" }
        }
    }

    /** Returns true if the classpath resource at [path] contains a top-level `zone:` key. */
    private fun isZoneFile(
        path: String,
        classLoader: ClassLoader,
    ): Boolean {
        val stream = classLoader.getResourceAsStream(path) ?: return false
        return stream.bufferedReader().use { reader ->
            reader.lineSequence().take(20).any { line -> line.trimStart().startsWith("zone:") }
        }
    }
}
