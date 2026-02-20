package dev.ambon.ui.login

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

object LoginScreenLoader {
    fun load(strict: Boolean = false): LoginScreen {
        val linesText = readResourceTextOrNull("/login.txt")
        val stylesText = readResourceTextOrNull("/login.styles.yaml")
        return loadFromTexts(linesText, stylesText, strict)
    }

    fun loadFromTexts(
        linesText: String?,
        stylesYaml: String?,
        strict: Boolean = false,
    ): LoginScreen {
        val lines = parseLines(linesText)

        if (stylesYaml == null) {
            return plain(lines)
        }
        if (stylesYaml.isBlank()) {
            return plain(lines)
        }

        return runCatching {
            parseStyled(lines, stylesYaml)
        }.getOrElse { error ->
            if (strict) {
                throw IllegalStateException("Failed to load login.styles.yaml: ${error.message}", error)
            }
            plain(lines)
        }
    }

    private fun parseLines(linesText: String?): List<String> {
        if (linesText == null) return FALLBACK_LINES
        val lines = linesText.reader().buffered().use { it.readLines() }
        return if (lines.isEmpty()) FALLBACK_LINES else lines
    }

    private fun parseStyled(
        lines: List<String>,
        stylesYaml: String,
    ): LoginScreen {
        val stylesFile: LoginStylesFile = yamlMapper.readValue(stylesYaml)
        val stylePrefixes = compileStylePrefixes(stylesFile.styles)
        val defaultStyle = stylesFile.defaultStyle.trim().ifEmpty { "plain" }
        val defaultPrefix =
            stylePrefixes[defaultStyle]
                ?: throw IllegalStateException(
                    "Unknown defaultStyle '$defaultStyle'. Known styles=${stylePrefixes.keys.sorted()}",
                )

        if (stylesFile.lineStyles.size > lines.size) {
            throw IllegalStateException(
                "lineStyles has ${stylesFile.lineStyles.size} entries but login.txt has ${lines.size} lines",
            )
        }

        val paddedLineStyles =
            if (stylesFile.lineStyles.size < lines.size) {
                stylesFile.lineStyles + List(lines.size - stylesFile.lineStyles.size) { defaultStyle }
            } else {
                stylesFile.lineStyles
            }

        val ansiPrefixesByLine =
            paddedLineStyles.mapIndexed { index, rawStyle ->
                val styleName = rawStyle.trim().ifEmpty { defaultStyle }
                when (styleName) {
                    defaultStyle -> defaultPrefix
                    else ->
                        stylePrefixes[styleName]
                            ?: throw IllegalStateException(
                                "lineStyles[$index] references unknown style '$styleName'",
                            )
                }
            }

        return LoginScreen(lines = lines, ansiPrefixesByLine = ansiPrefixesByLine)
    }

    private fun compileStylePrefixes(styles: Map<String, LoginStyleFile>): Map<String, String> {
        val prefixes = mutableMapOf("plain" to "")
        for ((rawName, def) in styles) {
            val name = rawName.trim()
            if (name.isEmpty()) {
                throw IllegalStateException("styles contains a blank style name")
            }

            val prefix =
                def.ansi.joinToString(separator = "") { rawToken ->
                    val token = rawToken.trim().lowercase()
                    ansiTokenCodes[token]
                        ?: throw IllegalStateException("Style '$name' uses unknown ANSI token '$rawToken'")
                }
            prefixes[name] = prefix
        }
        return prefixes
    }

    private fun plain(lines: List<String>): LoginScreen = LoginScreen(lines = lines, ansiPrefixesByLine = List(lines.size) { "" })

    private fun readResourceTextOrNull(path: String): String? =
        LoginScreenLoader::class.java
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }

    private data class LoginStylesFile(
        val defaultStyle: String = "plain",
        val styles: Map<String, LoginStyleFile> = emptyMap(),
        val lineStyles: List<String> = emptyList(),
    )

    private data class LoginStyleFile(
        val ansi: List<String> = emptyList(),
    )

    private val yamlMapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())

    private val ansiTokenCodes =
        mapOf(
            "reset" to "\u001B[0m",
            "bold" to "\u001B[1m",
            "dim" to "\u001B[2m",
            "bright_black" to "\u001B[90m",
            "bright_red" to "\u001B[91m",
            "bright_green" to "\u001B[92m",
            "bright_yellow" to "\u001B[93m",
            "bright_blue" to "\u001B[94m",
            "bright_magenta" to "\u001B[95m",
            "bright_cyan" to "\u001B[96m",
            "bright_white" to "\u001B[97m",
        )

    private val FALLBACK_LINES = listOf("Welcome to AmbonMUD")
}
