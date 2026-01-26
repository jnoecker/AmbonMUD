package dev.ambon.persistence

data class AccountRecord(
    val username: String,
    val usernameLower: String,
    val passwordHash: String,
    val playerId: PlayerId,
)
