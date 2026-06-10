package dev.ambon.engine

import org.mindrot.jbcrypt.BCrypt

interface PasswordHasher {
    fun hash(password: String): String

    fun verify(password: String, passwordHash: String): Boolean
}

object BCryptPasswordHasher : PasswordHasher {
    // Work factor (log2 rounds). 12 ≈ a few hundred ms/hash on modern hardware — materially slows
    // offline cracking of a leaked hash versus jBCrypt's default of 10. Existing hashes keep their
    // own embedded cost and still verify; only newly created/changed passwords use the new factor.
    private const val BCRYPT_COST = 12

    override fun hash(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST))

    override fun verify(
        password: String,
        passwordHash: String,
    ): Boolean = runCatching { BCrypt.checkpw(password, passwordHash) }.getOrDefault(false)
}
