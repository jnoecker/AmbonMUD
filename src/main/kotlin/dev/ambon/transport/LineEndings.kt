package dev.ambon.transport

private val lineBreakRegex = Regex("\r\n|\n|\r")

internal fun String.normalizeToCrlf(): String = replace(lineBreakRegex, "\r\n")
