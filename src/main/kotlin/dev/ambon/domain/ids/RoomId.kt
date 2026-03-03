package dev.ambon.domain.ids

/** Qualifies [localId] with [zone] if it doesn't already contain a zone prefix. */
fun qualifyId(zone: String, localId: String): String =
    if (':' in localId) localId else "$zone:$localId"

/** Extracts the zone prefix from a namespaced id (before the first `:`), or the full string if unqualified. */
fun idZone(rawId: String): String = rawId.substringBefore(':', rawId)

@JvmInline
value class RoomId(
    val value: String,
) {
    init {
        require(':' in value) {
            "RoomId must be namespaced as <zone>:<room>, got '$value'"
        }
    }

    val zone: String
        get() = value.substringBefore(':')

    val local: String
        get() = value.substringAfter(':')
}
