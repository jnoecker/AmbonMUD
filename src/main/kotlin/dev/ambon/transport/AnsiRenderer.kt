package dev.ambon.transport

class AnsiRenderer : TextRenderer {
    private val reset = "\u001B[0m"
    private val dim = "\u001B[2m"
    private val brightGreen = "\u001B[92m"
    private val brightCyan = "\u001B[96m"
    private val brightRed = "\u001B[91m"
    private val infoPrefix = dim + brightCyan

    override fun renderLine(
        text: String,
        kind: TextKind,
    ): String {
        val prefix =
            when (kind) {
                TextKind.NORMAL -> reset
                TextKind.INFO -> infoPrefix
                TextKind.ERROR -> brightRed
            }
        return buildString {
            append(prefix)
            append(text)
            append(reset)
            append("\r\n")
        }
    }

    override fun renderPrompt(prompt: PromptSpec): String =
        buildString {
            append(dim)
            append(brightGreen)
            append(prompt.text)
            append(reset)
        }
}
