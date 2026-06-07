package dev.ambon.engine.commands.handlers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefiniteArticleTest {
    @Test
    fun `swaps a leading indefinite article for the`() {
        assertEquals("the lacquered curio chest", the("a lacquered curio chest"))
        assertEquals("the ostentatious brass lever", the("an ostentatious brass lever"))
    }

    @Test
    fun `names already starting with the pass through`() {
        assertEquals("the supply chest", the("the supply chest"))
        assertEquals("The Door That Asks", the("The Door That Asks"))
    }

    @Test
    fun `bare names get the prefixed`() {
        assertEquals("the door to the north", the("door to the north"))
    }

    @Test
    fun `does not mangle words that merely start with article letters`() {
        assertEquals("the anvil", the("anvil"))
        assertEquals("the theater door", the("theater door"))
        assertEquals("the apple cart", the("apple cart"))
    }

    @Test
    fun `theCap capitalizes the sentence-initial form`() {
        assertEquals("The lacquered curio chest", theCap("a lacquered curio chest"))
        assertEquals("The iron lever", theCap("an iron lever"))
        assertEquals("The Door That Asks", theCap("The Door That Asks"))
        assertEquals("The door to the north", theCap("door to the north"))
    }
}
