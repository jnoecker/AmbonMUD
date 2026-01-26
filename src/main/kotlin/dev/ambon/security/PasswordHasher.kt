package dev.ambon.security

import org.mindrot.jbcrypt.BCrypt

class PasswordHasher(
    private val cost: Int = 12,
) {
    fun hash(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt(cost))

    fun verify(
        password: String,
        hash: String,
    ): Boolean = BCrypt.checkpw(password, hash)
}
