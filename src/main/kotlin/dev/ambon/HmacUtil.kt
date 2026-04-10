package dev.ambon

import java.nio.charset.StandardCharsets
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

/** Returns true when [signature] is non-blank and matches [hmacSha256] of [payload] under [secret]. */
internal fun isValidHmac(
    secret: String,
    payload: String,
    signature: String,
): Boolean = signature.isNotBlank() && signature == hmacSha256(secret, payload)
