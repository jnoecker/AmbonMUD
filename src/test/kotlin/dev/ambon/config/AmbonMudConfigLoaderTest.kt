package dev.ambon.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class AmbonMudConfigLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads defaults when config files are absent`() {
        val config = AmbonMudConfigLoader.load(baseDir = tempDir, env = emptyMap(), systemProperties = emptyMap())

        assertEquals(4000, config.deployment.telnetPort)
        assertEquals(8080, config.deployment.webPort)
        assertEquals("http://localhost:8080", config.deployment.webClientUrl)
        assertEquals(100L, config.gameplay.engineTickMillis)
        assertFalse(config.deployment.demoAutoLaunchBrowser)
    }

    @Test
    fun `applies file then env then system-property precedence`() {
        tempDir
            .resolve("ambonmud.yml")
            .writeText(
                """
                deployment:
                  telnetPort: 4100
                  webPort: 8181
                """.trimIndent(),
            )

        tempDir
            .resolve("ambonmud.gameplay.yml")
            .writeText(
                """
                gameplay:
                  engineTickMillis: 150
                """.trimIndent(),
            )

        val env =
            mapOf(
                "AMBONMUD_DEPLOYMENT_TELNET_PORT" to "4200",
                "AMBONMUD_DEPLOYMENT_WEB_PORT" to "8282",
                "AMBONMUD_GAMEPLAY_ENGINE_TICK_MILLIS" to "200",
            )
        val props =
            mapOf(
                "ambonmud.deployment.telnetPort" to "4300",
                "ambonmud.gameplay.engineTickMillis" to "250",
            )

        val config = AmbonMudConfigLoader.load(baseDir = tempDir, env = env, systemProperties = props)

        assertEquals(4300, config.deployment.telnetPort)
        assertEquals(8282, config.deployment.webPort)
        assertEquals("http://localhost:8282", config.deployment.webClientUrl)
        assertEquals(250L, config.gameplay.engineTickMillis)
    }

    @Test
    fun `supports legacy quickmud demo property alias`() {
        val fromLegacy =
            AmbonMudConfigLoader.load(
                baseDir = tempDir,
                env = emptyMap(),
                systemProperties = mapOf("quickmud.demo.autolaunchBrowser" to "true"),
            )

        assertTrue(fromLegacy.deployment.demoAutoLaunchBrowser)

        val newPropertyWins =
            AmbonMudConfigLoader.load(
                baseDir = tempDir,
                env = emptyMap(),
                systemProperties =
                    mapOf(
                        "quickmud.demo.autolaunchBrowser" to "true",
                        "ambonmud.deployment.demoAutoLaunchBrowser" to "false",
                    ),
            )

        assertFalse(newPropertyWins.deployment.demoAutoLaunchBrowser)
    }

    @Test
    fun `throws on invalid override values`() {
        val ex =
            assertThrows(AmbonMudConfigException::class.java) {
                AmbonMudConfigLoader.load(
                    baseDir = tempDir,
                    env = mapOf("AMBONMUD_DEPLOYMENT_TELNET_PORT" to "not-a-number"),
                    systemProperties = emptyMap(),
                )
            }

        assertTrue(
            ex.message!!.contains("telnetPort"),
            "Expected field name in error message. got='${ex.message}'",
        )
    }
}
