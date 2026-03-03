package dev.ambon.sharding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/** Deserializes [json] to [T], returning null on any parse/mapping failure. */
internal inline fun <reified T> ObjectMapper.readValueOrNull(json: String): T? =
    try {
        readValue<T>(json)
    } catch (_: Exception) {
        null
    }
