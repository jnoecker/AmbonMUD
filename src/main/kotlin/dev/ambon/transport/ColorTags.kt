package dev.ambon.transport

/**
 * Inline color-tag convention for engine-emitted text. Engine code wraps a
 * span in `{c:NAME}…{/c}` to mark it as semantically colored; the active
 * [TextRenderer] decides whether to translate the tags to ANSI escapes or
 * strip them out.
 *
 * Keeping the markup transport-agnostic preserves the engine boundary —
 * gameplay code never embeds ANSI codes directly.
 */
internal object ColorTags {
    /** ANSI SGR codes per known tag name. */
    private val ansiByTag = mapOf(
        "quest" to "\u001B[93m", // bright yellow — quest available
        "turnin" to "\u001B[92m", // bright green  — quest turn-in ready
        "dialogue" to "\u001B[96m", // bright cyan   — has dialogue
        "aggro" to "\u001B[91m", // bright red    — aggressive mob
        "room" to "\u001B[38;5;139m", // dusty rose (256-color) — adjacent room names in the auto-peek line
    )

    private val tagRegex = Regex("""\{c:([a-z]+)\}|\{/c\}""")

    /** Removes all `{c:NAME}…{/c}` tags, leaving the wrapped text in place. */
    fun strip(text: String): String = tagRegex.replace(text, "")

    /**
     * Translates tags to ANSI escape sequences. `{c:NAME}` becomes the color
     * for that tag (or stripped if unknown). `{/c}` re-asserts [baseColor] so
     * the rest of the line keeps the line-kind's base color.
     */
    fun applyAnsi(text: String, baseColor: String): String =
        tagRegex.replace(text) { match ->
            val tagName = match.groupValues[1]
            if (tagName.isEmpty()) baseColor else ansiByTag[tagName] ?: ""
        }
}
