package dev.ambon.bus

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun interface BusPublisher {
    fun publish(
        channel: String,
        message: String,
    )
}

fun interface BusSubscriberSetup {
    fun startListening(
        channelName: String,
        onMessage: (String) -> Unit,
    )
}

internal fun hmacSha256(
    secret: String,
    payload: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)).toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Returns true when [signature] is non-blank and matches [hmacSha256] of [payload] under [secret]. */
internal fun isValidHmac(
    secret: String,
    payload: String,
    signature: String,
): Boolean = signature.isNotBlank() && signature == hmacSha256(secret, payload)
