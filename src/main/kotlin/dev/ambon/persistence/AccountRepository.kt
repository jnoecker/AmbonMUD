package dev.ambon.persistence

interface AccountRepository {
    fun findByUsernameLower(usernameLower: String): AccountRecord?

    fun create(record: AccountRecord): AccountRecord

    fun all(): List<AccountRecord>
}
