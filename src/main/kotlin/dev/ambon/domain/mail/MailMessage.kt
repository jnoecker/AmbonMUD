package dev.ambon.domain.mail

import dev.ambon.domain.items.ItemInstance

data class MailMessage(
    val id: String,
    val fromName: String,
    val body: String,
    val sentAtEpochMs: Long,
    val read: Boolean = false,
    /** Gold attached to the letter, granted to the recipient on claim. */
    val gold: Long = 0L,
    /** A single item attached to the letter, granted to the recipient on claim. */
    val item: ItemInstance? = null,
    /** Whether the recipient has already claimed the gold/item. */
    val claimed: Boolean = false,
) {
    /** Whether this letter carries gold or an item the recipient has not yet claimed. */
    val hasUnclaimedAttachments: Boolean
        get() = !claimed && (gold > 0L || item != null)
}
