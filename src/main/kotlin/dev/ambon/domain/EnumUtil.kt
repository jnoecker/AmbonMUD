package dev.ambon.domain

internal inline fun <reified T : Enum<T>> enumFromString(s: String): T? =
    enumValues<T>().firstOrNull { it.name.equals(s, ignoreCase = true) }
