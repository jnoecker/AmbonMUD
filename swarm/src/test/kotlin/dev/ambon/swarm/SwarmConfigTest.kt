package dev.ambon.swarm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SwarmConfigTest {
    @Test
    fun `valid defaults pass validation`() {
        val config = SwarmConfig().validated()
        assertEquals("127.0.0.1", config.target.host)
    }

    @Test
    fun `invalid telnet mix fails`() {
        val config = SwarmConfig(run = RunConfig(protocolMix = ProtocolMixConfig(telnetPercent = 200)))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `namespace must match player constraints`() {
        val config = SwarmConfig(run = RunConfig(namespacePrefix = "1bad"))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `namespace prefix longer than 10 chars is rejected`() {
        val config = SwarmConfig(run = RunConfig(namespacePrefix = "abcdefghijk"))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `empty races list fails validation`() {
        val config = SwarmConfig(behavior = BehaviorConfig(races = emptyList()))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `empty classes list fails validation`() {
        val config = SwarmConfig(behavior = BehaviorConfig(classes = emptyList()))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `empty chatPhrases fails validation`() {
        val config = SwarmConfig(behavior = BehaviorConfig(chatPhrases = emptyList()))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `empty movementCommands fails validation`() {
        val config = SwarmConfig(behavior = BehaviorConfig(movementCommands = emptyList()))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `empty combatCommands fails validation`() {
        val config = SwarmConfig(behavior = BehaviorConfig(combatCommands = emptyList()))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `rampSeconds exceeding durationSeconds fails validation`() {
        val config = SwarmConfig(run = RunConfig(rampSeconds = 300, durationSeconds = 120))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `deterministic without seed fails validation`() {
        val config = SwarmConfig(run = RunConfig(deterministic = true, seed = null))
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `deterministic with seed passes validation`() {
        val config = SwarmConfig(run = RunConfig(deterministic = true, seed = 42L))
        config.validated()
    }

    @Test
    fun `long namespace prefix with many bots exceeds name limit`() {
        val config = SwarmConfig(
            run = RunConfig(
                namespacePrefix = "abcdefghij",
                totalBots = 1_000_000,
            ),
        )
        assertThrows<IllegalArgumentException> { config.validated() }
    }

    @Test
    fun `short namespace prefix with moderate bots passes`() {
        val config = SwarmConfig(
            run = RunConfig(
                namespacePrefix = "sw",
                totalBots = 9999,
            ),
        )
        config.validated()
    }
}
