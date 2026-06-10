package dev.ambon

import dev.ambon.redis.redactRedisUri
import dev.ambon.transport.WsConnectionLimiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecurityHardeningTest {
    @Test
    fun `redactRedisUri strips inline credentials`() {
        assertEquals("redis://***@host:6379", redactRedisUri("redis://:s3cret@host:6379"))
        assertEquals("rediss://***@host:6380/0", redactRedisUri("rediss://user:p@ss@host:6380/0"))
    }

    @Test
    fun `redactRedisUri leaves credential-free URIs unchanged`() {
        assertEquals("redis://localhost:6379", redactRedisUri("redis://localhost:6379"))
        assertEquals("redis://localhost:6379/0", redactRedisUri("redis://localhost:6379/0"))
    }

    @Test
    fun `isValidHmac accepts a correct signature and rejects forgeries`() {
        val sig = hmacSha256("secret", "payload")
        assertTrue(isValidHmac("secret", "payload", sig))
        assertFalse(isValidHmac("secret", "payload", sig.dropLast(1) + "0"))
        assertFalse(isValidHmac("secret", "payload", ""))
        assertFalse(isValidHmac("wrong", "payload", sig))
        // A truncated guess (different length) must also be rejected, not throw.
        assertFalse(isValidHmac("secret", "payload", "abc"))
    }

    @Test
    fun `WsConnectionLimiter enforces the global cap`() {
        val limiter = WsConnectionLimiter(maxTotal = 2, maxPerIp = 0)
        assertTrue(limiter.tryAcquire("1.1.1.1"))
        assertTrue(limiter.tryAcquire("2.2.2.2"))
        assertFalse(limiter.tryAcquire("3.3.3.3"))
        limiter.release("1.1.1.1")
        assertTrue(limiter.tryAcquire("3.3.3.3"))
    }

    @Test
    fun `WsConnectionLimiter enforces the per-IP cap independently of the global cap`() {
        val limiter = WsConnectionLimiter(maxTotal = 100, maxPerIp = 2)
        assertTrue(limiter.tryAcquire("9.9.9.9"))
        assertTrue(limiter.tryAcquire("9.9.9.9"))
        assertFalse(limiter.tryAcquire("9.9.9.9"))
        // A different IP is unaffected.
        assertTrue(limiter.tryAcquire("8.8.8.8"))
        // Releasing one frees a slot for that IP.
        limiter.release("9.9.9.9")
        assertTrue(limiter.tryAcquire("9.9.9.9"))
    }
}
