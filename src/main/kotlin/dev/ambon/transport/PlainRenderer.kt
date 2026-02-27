package dev.ambon.transport

class PlainRenderer : TextRenderer {
    override fun renderLine(
        text: String,
        kind: TextKind,
    ): String = text.normalizeToCrlf() + "\r\n"

    override fun renderPrompt(prompt: PromptSpec): String = prompt.text
}
