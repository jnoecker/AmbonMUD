package dev.ambon.transport

interface TextRenderer {
    fun renderLine(text: String): String

    fun renderPrompt(): String
}
