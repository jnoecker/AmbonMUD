package dev.ambon.swarm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwarmLoginHeuristicsTest {
    @Test
    fun `normalizes ansi`() {
        val normalized = normalizeSwarmLine("\u001B[2m\u001B[96mEnter your name:\u001B[0m")
        assertTrue(normalized == "Enter your name:")
    }

    @Test
    fun `recognizes new login prompts`() {
        assertTrue(isLoginPrompt("No user named 'foo' was found. Create a new user? (yes/no)"))
        assertTrue(isLoginPrompt("Please answer yes or no."))
        assertTrue(isLoginPrompt("Password:"))
        assertTrue(isLoginPrompt(">"))
    }

    @Test
    fun `recognizes world signals`() {
        assertTrue(isWorldSignal("Exits: north east"))
        assertTrue(isWorldSignal("Known spells (Mana: 10/10):"))
        assertFalse(isWorldSignal("Create a password:"))
    }
}
