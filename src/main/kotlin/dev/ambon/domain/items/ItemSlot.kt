package dev.ambon.domain.items

enum class ItemSlot {
    HEAD,
    BODY,
    HAND,
    ;

    fun label(): String = name.lowercase()

    companion object {
        fun parse(raw: String): ItemSlot? {
            val value = raw.trim().lowercase()
            return when (value) {
                "head" -> HEAD
                "body" -> BODY
                "hand" -> HAND
                else -> null
            }
        }
    }
}
