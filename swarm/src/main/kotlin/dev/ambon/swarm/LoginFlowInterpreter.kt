package dev.ambon.swarm

internal enum class LoginSignal {
    SEND_NAME,
    SEND_YES,
    SEND_PASSWORD,
    SUCCESS,
    NOOP,
}

internal class LoginFlowInterpreter(
    private val credential: BotCredential,
) {
    private var sentCredential = false

    fun onLine(rawLine: String): LoginSignal {
        val line = normalize(rawLine)
        if (line.isEmpty()) return LoginSignal.NOOP

        return when {
            "enter your name" in line || line.startsWith("name:") -> LoginSignal.SEND_NAME
            "create a new user" in line || "no user named" in line -> LoginSignal.SEND_YES
            "create a password" in line || "new password" in line -> {
                sentCredential = true
                LoginSignal.SEND_PASSWORD
            }
            line.startsWith("password:") || "password:" in line -> {
                sentCredential = true
                LoginSignal.SEND_PASSWORD
            }
            sentCredential && looksLikeInGameOutput(line) -> LoginSignal.SUCCESS
            else -> LoginSignal.NOOP
        }
    }

    private fun looksLikeInGameOutput(line: String): Boolean =
        line.startsWith("exits:") ||
            " hp " in " $line " ||
            "also online:" in line ||
            "you are in combat" in line ||
            " enters." in line ||
            "leaves." in line

    private fun normalize(input: String): String {
        val withoutAnsi = ANSI_ESCAPE_REGEX.replace(input, "")
        val withoutName = withoutAnsi.replace(credential.name.lowercase(), "<bot>")
        return withoutName.trim().lowercase()
    }

    private companion object {
        val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[0-9;]*[ -/]*[@-~]")
    }
}
