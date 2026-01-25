package dev.ambon.transport

class AnsiRenderer : TextRenderer {
    private val reset = "\u001B[0m"
    private val dim = "\u001B[2m"
    private val brightGreen = "\u001B[92m"
    private val brightCyan = "\u001B[96m"
    private val brightRed = "\u001B[91m"

    override fun renderLine(
        text: String,
        kind: TextKind,
    ): String {
        val prefix =
            when (kind) {
                TextKind.NORMAL -> reset
                TextKind.INFO -> dim + brightCyan
                TextKind.ERROR -> brightRed
            }
        return prefix + text + reset + "\r\n"
    }

    override fun renderPrompt(prompt: PromptSpec): String = dim + brightGreen + prompt.text + reset
}
