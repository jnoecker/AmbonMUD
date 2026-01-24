package dev.ambon.transport

class PlainRenderer : TextRenderer {
    override fun renderLine(text: String): String = text + "\r\n"

    override fun renderPrompt(prompt: PromptSpec): String = prompt.text
}
