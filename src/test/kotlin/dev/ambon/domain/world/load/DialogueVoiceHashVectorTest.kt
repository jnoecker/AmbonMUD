package dev.ambon.domain.world.load

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.world.data.DialogueNodeFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Canonical cross-parser vectors for voice-over clip hashing (see docs/VOICE_OVER_CONTRACT.md
 * "Hash spec"). These pin exactly how the engine's YAML loader materializes a dialogue node's
 * `text` into a string — which is the input to the SHA-256 path hash. Arcanum must parse the
 * same authored YAML into byte-identical text (and therefore the same sha8); a divergence in
 * block-scalar chomping / trailing-newline handling between the two parsers would fail this
 * test on one side and surface the desync before it ships as 404ing clip URLs.
 *
 * The mapper here mirrors `WorldLoader`'s exactly (YAMLFactory + KotlinModule, unknown props
 * ignored) so the parsed text matches what the engine actually loads at runtime.
 */
class DialogueVoiceHashVectorTest {
    private val mapper =
        ObjectMapper(YAMLFactory())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .registerModule(KotlinModule.Builder().build())

    private fun sha8(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)

    @Test
    fun `plain scalar text hashes to the published vector`() {
        val node: DialogueNodeFile = mapper.readValue("text: Hello there!\n")
        assertEquals("Hello there!", node.text)
        assertEquals("89b8b8e4", sha8(node.text))
    }

    @Test
    fun `literal block scalar uses clip chomping - one trailing newline`() {
        // A real `|` literal block with default (clip) chomping. Interior newlines are
        // preserved and exactly one trailing newline is kept. THIS is the parser-parity
        // boundary called out in the contract — Arcanum must produce the identical string.
        val yaml =
            """
            text: |
              Hello there!
              Stay a while.
            """.trimIndent() + "\n"
        val node: DialogueNodeFile = mapper.readValue(yaml)

        assertEquals("Hello there!\nStay a while.\n", node.text)
        // sha8("Hello there!\nStay a while.\n") = df658e4d
        assertEquals("df658e4d", sha8(node.text))
    }

    @Test
    fun `strip block scalar drops the trailing newline`() {
        // `|-` strip chomping — no trailing newline, so it hashes differently from `|`.
        // Documents that the chomping indicator is hash-significant.
        val yaml =
            """
            text: |-
              Hello there!
              Stay a while.
            """.trimIndent() + "\n"
        val node: DialogueNodeFile = mapper.readValue(yaml)

        assertEquals("Hello there!\nStay a while.", node.text)
    }
}
