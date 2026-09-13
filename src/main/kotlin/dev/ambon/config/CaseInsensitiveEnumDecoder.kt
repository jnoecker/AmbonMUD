package dev.ambon.config

import com.sksamuel.hoplite.ConfigFailure
import com.sksamuel.hoplite.ConfigResult
import com.sksamuel.hoplite.DecoderContext
import com.sksamuel.hoplite.Node
import com.sksamuel.hoplite.PrimitiveNode
import com.sksamuel.hoplite.decoder.NullHandlingDecoder
import com.sksamuel.hoplite.fp.invalid
import com.sksamuel.hoplite.fp.valid
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Decodes every enum in the config tree case-insensitively, so `standard`, `Standard` and
 * `STANDARD` all name [QuestDifficulty.STANDARD].
 *
 * Hoplite's built-in enum decoder matches constant names exactly, which makes a lowercase
 * spelling a hard startup failure. That is a bad trade for a server whose production config is
 * an overlay YAML authored outside this repo (`application-local.yaml`, fetched at boot — see
 * `docs/DEPLOYMENT.md`): the demo instance crash-looped on `tiers: {trivial: 0.25, ...}` because
 * world YAML spells difficulties lowercase (`difficulty: easy`, parsed case-insensitively by
 * [QuestDifficulty.parse]) and the overlay followed suit. Config and world YAML now agree, and
 * capitalisation can never again take the server down.
 *
 * Registered in [AppConfigLoader]. User decoders outrank Hoplite's own (which declare the minimum
 * priority), and the map decoder resolves key decoders through the same registry, so this covers
 * both enum-valued fields and enum-keyed maps such as [QuestXpConfig.tiers].
 */
class CaseInsensitiveEnumDecoder : NullHandlingDecoder<Any> {
    override fun supports(type: KType): Boolean {
        val classifier = type.classifier
        return classifier is KClass<*> && classifier.java.isEnum
    }

    override fun safeDecode(
        node: Node,
        type: KType,
        context: DecoderContext,
    ): ConfigResult<Any> {
        val kclass = type.classifier as? KClass<*> ?: return ConfigFailure.DecodeError(node, type).invalid()
        val raw = (node as? PrimitiveNode)?.value?.toString() ?: return ConfigFailure.DecodeError(node, type).invalid()
        val constants = kclass.java.enumConstants?.filterIsInstance<Enum<*>>().orEmpty()
        val match = constants.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        return match?.valid() ?: ConfigFailure.InvalidEnumConstant(node, type, raw).invalid()
    }
}
