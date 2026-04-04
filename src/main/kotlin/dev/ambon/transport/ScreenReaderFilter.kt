package dev.ambon.transport

/**
 * Transforms outbound text for screen-reader accessibility mode.
 *
 * Strips decorative box-drawing characters and other visual-only formatting
 * that clutters assistive technology output, replacing them with plain-text
 * equivalents.
 */
object ScreenReaderFilter {
    /**
     * Box-drawing and decorative characters that serve a purely visual purpose.
     * Each maps to a plain-text replacement.
     */
    private val boxDrawingReplacements = mapOf(
        '\u2550' to '-', // ═
        '\u2500' to '-', // ─
        '\u2502' to '|', // │
        '\u2554' to '+', // ╔
        '\u2557' to '+', // ╗
        '\u255A' to '+', // ╚
        '\u255D' to '+', // ╝
        '\u2560' to '+', // ╠
        '\u2563' to '+', // ╣
        '\u2566' to '+', // ╦
        '\u2569' to '+', // ╩
        '\u256C' to '+', // ╬
        '\u250C' to '+', // ┌
        '\u2510' to '+', // ┐
        '\u2514' to '+', // └
        '\u2518' to '+', // ┘
        '\u251C' to '+', // ├
        '\u2524' to '+', // ┤
        '\u252C' to '+', // ┬
        '\u2534' to '+', // ┴
        '\u253C' to '+', // ┼
        '\u2551' to '|', // ║
        '\u2580' to ' ', // ▀
        '\u2584' to ' ', // ▄
        '\u2588' to ' ', // █
        '\u258C' to ' ', // ▌
        '\u2590' to ' ', // ▐
        '\u2591' to ' ', // ░
        '\u2592' to ' ', // ▒
        '\u2593' to ' ', // ▓
        '\u00B7' to ' ', // ·
        '\u2022' to '*', // •
        '\u2605' to '*', // ★
        '\u2606' to '*', // ☆
        '\u25C6' to '*', // ◆
        '\u25C7' to '*', // ◇
        '\u25BA' to '>', // ►
        '\u25C4' to '<', // ◄
        '\u25B2' to '^', // ▲
        '\u25BC' to 'v', // ▼
    )

    /**
     * Strips decorative formatting from [text] for screen-reader consumption.
     *
     * Replaces box-drawing characters with plain-text equivalents and collapses
     * runs of repeated separator characters to keep output compact.
     */
    fun filter(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val replacement = boxDrawingReplacements[ch]
            if (replacement != null) {
                sb.append(replacement)
            } else {
                sb.append(ch)
            }
        }
        // Collapse runs of 3+ identical separator characters into exactly 3
        return collapseRepeatedSeparators(sb.toString())
    }

    /**
     * Collapses runs of 3 or more repeated separator characters (-, +, =, |)
     * into exactly 3, keeping output compact without losing structure hints.
     */
    private fun collapseRepeatedSeparators(text: String): String =
        text.replace(Regex("([\\-+=|])\\1{2,}")) { match ->
            match.value[0].toString().repeat(3)
        }
}
