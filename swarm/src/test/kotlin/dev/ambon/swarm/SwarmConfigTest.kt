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
}
