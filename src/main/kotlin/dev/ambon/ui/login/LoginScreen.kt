package dev.ambon.ui.login

data class LoginScreen(
    val lines: List<String>,
    val ansiPrefixesByLine: List<String>,
) {
    fun isValid(): Boolean = lines.size == ansiPrefixesByLine.size
}
