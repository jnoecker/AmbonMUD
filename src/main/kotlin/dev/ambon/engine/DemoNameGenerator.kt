package dev.ambon.engine

import kotlin.random.Random

/**
 * Generates fantasy-flavored names for demo (guest) characters.
 *
 * A demo name is a curated fantasy first name with a small numeric suffix
 * (e.g. "Aelara42", "Brogan7"). The suffix keeps names unique among
 * concurrently-online demo characters and reduces the chance of collision
 * with a registered account.
 *
 * Callers pass [isTaken] to test candidate names against both the online
 * registry and the persistent repo. Generation retries up to [maxAttempts]
 * times before falling back to a longer suffix that is virtually guaranteed
 * to be unique.
 */
class DemoNameGenerator(
    private val random: Random = Random.Default,
) {
    suspend fun generate(isTaken: suspend (String) -> Boolean, maxAttempts: Int = 20): String {
        repeat(maxAttempts) {
            val candidate = buildCandidate(suffixDigits = 2)
            if (!isTaken(candidate)) return candidate
        }
        // Fallback: a longer suffix to guarantee a free slot.
        repeat(maxAttempts) {
            val candidate = buildCandidate(suffixDigits = 4)
            if (!isTaken(candidate)) return candidate
        }
        // Last resort — shouldn't happen in practice unless the world has
        // tens of thousands of online demos. Names are still valid per
        // PlayerRegistry.isValidName (2..16 chars, alnum/_, no leading digit).
        return buildCandidate(suffixDigits = 5)
    }

    private fun buildCandidate(suffixDigits: Int): String {
        val name = FANTASY_NAMES[random.nextInt(FANTASY_NAMES.size)]
        val max = pow10(suffixDigits)
        val suffix = random.nextInt(max)
        // 16-char cap: pick names short enough that name + 5 digits fits.
        return "$name$suffix"
    }

    private fun pow10(n: Int): Int {
        var v = 1
        repeat(n) { v *= 10 }
        return v
    }

    companion object {
        // Hand-picked fantasy names: max 10 chars so a 4-5 digit suffix keeps
        // the total under the 16-char name limit.
        internal val FANTASY_NAMES = listOf(
            "Aelara",
            "Brogan",
            "Cyrene",
            "Drystan",
            "Elowen",
            "Fenris",
            "Galen",
            "Halina",
            "Ivar",
            "Joren",
            "Kaeli",
            "Larian",
            "Mira",
            "Nyx",
            "Orin",
            "Petra",
            "Quill",
            "Rowan",
            "Sable",
            "Tova",
            "Ulrich",
            "Vesna",
            "Wren",
            "Xanth",
            "Yara",
            "Zorin",
            "Anara",
            "Bryn",
            "Calix",
            "Darra",
        )
    }
}
