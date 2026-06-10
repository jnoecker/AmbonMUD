package dev.ambon

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Computes HMAC-SHA256 of [payload] under [secret], returning a lowercase hex string. */
internal fun hmacSha256(
    secret: String,
    payload: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Returns true when [signature] is non-blank and matches [hmacSha256] of [payload] under [secret].
 *
 * Uses [MessageDigest.isEqual], which is constant-time for equal-length inputs, so a forged
 * signature cannot be recovered byte-by-byte via a timing side-channel. (The expected HMAC is a
 * fixed-length 64-char hex string, so a length mismatch only reveals that the guess is the wrong
 * length — information an attacker already has.)
 */
internal fun isValidHmac(
    secret: String,
    payload: String,
    signature: String,
): Boolean {
    if (signature.isBlank()) return false
    val expected = hmacSha256(secret, payload)
    return MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        signature.toByteArray(StandardCharsets.UTF_8),
    )
}
