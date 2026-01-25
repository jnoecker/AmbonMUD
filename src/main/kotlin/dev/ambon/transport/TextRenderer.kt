package dev.ambon.transport

interface TextRenderer {
    fun renderLine(
        text: String,
        kind: TextKind,
    ): String

    fun renderPrompt(prompt: PromptSpec): String
}
