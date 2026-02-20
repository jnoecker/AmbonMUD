package dev.ambon.ui.login

class LoginScreenRenderer {
    fun render(
        screen: LoginScreen,
        ansiEnabled: Boolean,
    ): List<String> {
        require(screen.isValid()) {
            "LoginScreen is invalid: lines=${screen.lines.size}, ansiPrefixesByLine=${screen.ansiPrefixesByLine.size}"
        }

        return screen.lines.indices.map { index ->
            renderLine(
                line = screen.lines[index],
                ansiPrefix = screen.ansiPrefixesByLine[index],
                ansiEnabled = ansiEnabled,
            )
        }
    }

    private fun renderLine(
        line: String,
        ansiPrefix: String,
        ansiEnabled: Boolean,
    ): String {
        if (line.isEmpty()) return "\r\n"
        if (ansiEnabled && ansiPrefix.isNotEmpty()) return ansiPrefix + line + ANSI_RESET + "\r\n"
        return line + "\r\n"
    }

    private companion object {
        private const val ANSI_RESET = "\u001B[0m"
    }
}
