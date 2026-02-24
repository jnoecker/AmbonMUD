package dev.ambon.swarm

private val ansiRegex = Regex("\\u001B\\[[;\\d]*m")

internal fun normalizeSwarmLine(raw: String): String = raw.replace(ansiRegex, "").trim()

internal fun isLoginPrompt(line: String): Boolean {
    val lower = line.lowercase()
    return lower == ">" ||
        "enter your name" in lower ||
        "no user named" in lower ||
        "create a new user" in lower ||
        "please answer yes or no" in lower ||
        "create a password" in lower ||
        lower == "password:" ||
        lower.startsWith("password:") ||
        "incorrect password" in lower ||
        "blank password" in lower ||
        "invalid password" in lower
}

internal fun isTerminalLoginFailure(line: String): Boolean {
    val lower = line.lowercase()
    return "too many failed login attempts" in lower
}

internal fun isWorldSignal(line: String): Boolean {
    val lower = line.lowercase()
    return lower.startsWith("exits:") ||
        lower.contains("known spells") ||
        lower.contains("you have learned") ||
        lower.contains("you attack") ||
        lower.contains("you say") ||
        lower.contains("you are transported")
}
