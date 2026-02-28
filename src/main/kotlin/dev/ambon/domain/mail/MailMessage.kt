package dev.ambon.domain.mail

data class MailMessage(
    val id: String,
    val fromName: String,
    val body: String,
    val sentAtEpochMs: Long,
    val read: Boolean = false,
)
