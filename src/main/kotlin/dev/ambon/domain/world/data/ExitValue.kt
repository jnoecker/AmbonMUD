package dev.ambon.domain.world.data

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Represents an exit in a room definition, supporting two YAML forms:
 *
 * Simple string (backward-compatible):
 * ```yaml
 * exits:
 *   n: inner_keep
 * ```
 *
 * Object form with optional door block:
 * ```yaml
 * exits:
 *   n:
 *     to: inner_keep
 *     door:
 *       initialState: locked
 *       keyItemId: zone:iron_key
 *       keyConsumed: false
 *       resetWithZone: true
 * ```
 */
data class ExitValue(
    val to: String,
    val door: DoorFile? = null,
)

class ExitValueDeserializer : StdDeserializer<ExitValue>(ExitValue::class.java) {
    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): ExitValue = if (p.currentToken == JsonToken.VALUE_STRING) {
        ExitValue(to = p.valueAsString)
    } else {
        val node = p.readValueAsTree<ObjectNode>()
        val to =
            node.get("to")?.asText()
                ?: throw ctxt.instantiationException(
                    ExitValue::class.java,
                    "Exit object must have a 'to' field",
                )
        val doorNode = node.get("door")
        val door =
            if (doorNode != null && !doorNode.isNull) {
                ctxt.readTreeAsValue(doorNode, DoorFile::class.java)
            } else {
                null
            }
        ExitValue(to = to, door = door)
    }
}
