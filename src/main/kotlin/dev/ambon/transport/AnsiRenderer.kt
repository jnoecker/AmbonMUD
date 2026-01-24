package dev.ambon.transport

class AnsiRenderer : TextRenderer {
    private val reset = "\u001B[0m"
    private val brightGreen = "\u001B[92m"
    private val dim = "\u001B[2m"

    override fun renderLine(text: String): String {
        // Reset before/after to prevent style leaks
        return "$reset$text$reset\r\n"
    }

    override fun renderPrompt(prompt: PromptSpec): String {
        // Quick win: dim + bright green prompt
        return "$dim$brightGreen$prompt$reset"
    }
}
