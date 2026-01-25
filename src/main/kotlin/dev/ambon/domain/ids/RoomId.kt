package dev.ambon.domain.ids

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
