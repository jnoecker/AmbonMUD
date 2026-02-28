package dev.ambon.persistence

data class WorldContentDocument(
    val sourceName: String,
    val zone: String,
    val content: String,
    val loadOrder: Int,
    val importedAtEpochMs: Long,
)
