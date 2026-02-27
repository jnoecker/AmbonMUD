package dev.ambon.engine

import org.mindrot.jbcrypt.BCrypt

interface PasswordHasher {
    fun hash(password: String): String

    fun verify(password: String, passwordHash: String): Boolean
}

object BCryptPasswordHasher : PasswordHasher {
    override fun hash(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt())

    override fun verify(
        password: String,
        passwordHash: String,
    ): Boolean = runCatching { BCrypt.checkpw(password, passwordHash) }.getOrDefault(false)
}
