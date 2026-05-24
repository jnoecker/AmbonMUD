package dev.ambon.transport

class PlainRenderer : TextRenderer {
    override fun renderLine(
        text: String,
        kind: TextKind,
    ): String = ColorTags.strip(text).normalizeToCrlf() + "\r\n"

    override fun renderPrompt(prompt: PromptSpec): String = prompt.text
}
