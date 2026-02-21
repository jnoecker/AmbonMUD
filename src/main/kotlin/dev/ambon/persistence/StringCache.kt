package dev.ambon.persistence

interface StringCache {
    fun get(key: String): String?

    fun setEx(
        key: String,
        ttlSeconds: Long,
        value: String,
    )
}
