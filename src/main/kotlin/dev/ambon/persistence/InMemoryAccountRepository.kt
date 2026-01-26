package dev.ambon.persistence

class InMemoryAccountRepository : AccountRepository {
    private val accounts = mutableMapOf<String, AccountRecord>()

    override fun findByUsernameLower(usernameLower: String): AccountRecord? = accounts[usernameLower.trim().lowercase()]

    override fun create(record: AccountRecord): AccountRecord {
        val key = record.usernameLower.trim().lowercase()
        require(key.isNotEmpty()) { "usernameLower cannot be blank" }
        if (accounts.containsKey(key)) {
            throw IllegalStateException("Account already exists: '$key'")
        }
        val normalized = record.copy(usernameLower = key)
        accounts[key] = normalized
        return normalized
    }

    override fun all(): List<AccountRecord> = accounts.values.toList()
}
