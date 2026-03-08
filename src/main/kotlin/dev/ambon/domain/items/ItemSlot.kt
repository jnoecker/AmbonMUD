package dev.ambon.domain.items

/**
 * An equipment slot identifier. Slot names are always stored lowercase.
 *
 * The set of valid slots is data-driven via [dev.ambon.config.EquipmentConfig].
 * The named constants below are well-known defaults that ship with the standard
 * configuration; they are not the only legal values.
 */
@JvmInline
value class ItemSlot(
    val name: String,
) {
    fun label(): String = name

    companion object {
        val HEAD = ItemSlot("head")
        val BODY = ItemSlot("body")
        val HAND = ItemSlot("hand")

        fun parse(raw: String): ItemSlot? {
            val value = raw.trim().lowercase()
            return if (value.isEmpty()) null else ItemSlot(value)
        }
    }
}
