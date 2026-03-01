package dev.ambon.domain.guild

import dev.ambon.persistence.PlayerId

data class GuildRecord(
    val id: String,
    val name: String,
    val tag: String,
    val leaderId: PlayerId,
    val motd: String? = null,
    val members: Map<PlayerId, GuildRank> = emptyMap(),
    val createdAtEpochMs: Long,
)
