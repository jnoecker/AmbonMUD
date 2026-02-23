package dev.ambon.engine.commands

import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandParserTest {
    @Test
    fun `parses movement aliases`() {
        assertEquals(Command.Move(Direction.NORTH), CommandParser.parse("n"))
        assertEquals(Command.Move(Direction.NORTH), CommandParser.parse("north"))
        assertEquals(Command.Move(Direction.SOUTH), CommandParser.parse("s"))
        assertEquals(Command.Move(Direction.EAST), CommandParser.parse("e"))
        assertEquals(Command.Move(Direction.WEST), CommandParser.parse("west"))
    }

    @Test
    fun `parses core commands and ignores whitespace`() {
        assertEquals(Command.Help, CommandParser.parse("help"))
        assertEquals(Command.Help, CommandParser.parse("  ?  "))
        assertEquals(Command.Look, CommandParser.parse("l"))
        assertEquals(Command.Look, CommandParser.parse(" look "))
        assertEquals(Command.Quit, CommandParser.parse("quit"))
        assertEquals(Command.Quit, CommandParser.parse(" exit "))
        assertEquals(Command.Noop, CommandParser.parse("   "))
    }

    @Test
    fun `parser parses tell and t aliases`() {
        assertEquals(Command.Tell("Bob", "hi there"), CommandParser.parse("tell Bob hi there"))
        assertEquals(Command.Tell("Bob", "hi there"), CommandParser.parse("t Bob hi there"))
    }

    @Test
    fun `parser parses gossip and gs aliases`() {
        assertEquals(Command.Gossip("hello"), CommandParser.parse("gossip hello"))
        assertEquals(Command.Gossip("hello"), CommandParser.parse("gs hello"))
    }

    @Test
    fun `parser parses say and apostrophe shorthand`() {
        assertEquals(Command.Say("hello"), CommandParser.parse("say hello"))
        assertEquals(Command.Say("hello"), CommandParser.parse("'hello"))
        assertEquals(Command.Say("hello world"), CommandParser.parse("'  hello world   "))
    }

    @Test
    fun `parser returns Invalid for incomplete tell and gossip`() {
        assertTrue(CommandParser.parse("tell Bob") is Command.Invalid)
        assertTrue(CommandParser.parse("t Bob") is Command.Invalid)
        assertTrue(CommandParser.parse("gossip") is Command.Invalid)
        assertTrue(CommandParser.parse("gossip   ") is Command.Invalid)
        assertTrue(CommandParser.parse("gs") is Command.Invalid)
        assertTrue(CommandParser.parse("gs   ") is Command.Invalid)
    }

    @Test
    fun `parser returns Invalid for empty say payload`() {
        assertTrue(CommandParser.parse("say   ") is Command.Invalid)
        assertTrue(CommandParser.parse("'   ") is Command.Invalid)
    }

    @Test
    fun `unknown lines become Unknown`() {
        val cmd = CommandParser.parse("dance wildly")
        assertEquals(Command.Unknown("dance wildly"), cmd)
    }

    @Test
    fun `parses exits`() {
        assertTrue(CommandParser.parse("exits") is Command.Exits)
        assertTrue(CommandParser.parse("ex") is Command.Exits)
    }

    @Test
    fun `parses equipment wear and remove`() {
        assertEquals(Command.Equipment, CommandParser.parse("equipment"))
        assertEquals(Command.Equipment, CommandParser.parse("eq"))
        assertEquals(Command.Wear("sword"), CommandParser.parse("wear sword"))
        assertEquals(Command.Wear("sword"), CommandParser.parse("equip sword"))
        assertEquals(Command.Remove(ItemSlot.HEAD), CommandParser.parse("remove head"))
        assertEquals(Command.Remove(ItemSlot.BODY), CommandParser.parse("unequip body"))
    }

    @Test
    fun `remove validates slot names`() {
        assertTrue(CommandParser.parse("remove") is Command.Invalid)
        assertTrue(CommandParser.parse("remove feet") is Command.Invalid)
    }

    @Test
    fun `parses get aliases including pick up`() {
        assertEquals(Command.Get("coin"), CommandParser.parse("get coin"))
        assertEquals(Command.Get("coin"), CommandParser.parse("take coin"))
        assertEquals(Command.Get("coin"), CommandParser.parse("pickup coin"))
        assertEquals(Command.Get("coin"), CommandParser.parse("pick coin"))
        assertEquals(Command.Get("coin"), CommandParser.parse("pick up coin"))
    }

    @Test
    fun `parses use command`() {
        assertEquals(Command.Use("potion"), CommandParser.parse("use potion"))
    }

    @Test
    fun `parses give command with multi-word item`() {
        assertEquals(Command.Give("shimmering potion", "Bob"), CommandParser.parse("give shimmering potion Bob"))
    }

    @Test
    fun `give and use validate arguments`() {
        assertTrue(CommandParser.parse("use") is Command.Invalid)
        assertTrue(CommandParser.parse("use   ") is Command.Invalid)
        assertTrue(CommandParser.parse("give coin") is Command.Invalid)
        assertTrue(CommandParser.parse("give   ") is Command.Invalid)
    }

    @Test
    fun `parses kill and flee`() {
        assertEquals(Command.Kill("wolf"), CommandParser.parse("kill wolf"))
        assertEquals(Command.Flee, CommandParser.parse("flee"))
    }

    @Test
    fun `parses look direction`() {
        val c1 = CommandParser.parse("look north")
        assertEquals(Command.LookDir(Direction.NORTH), c1)

        val c2 = CommandParser.parse("l e")
        assertEquals(Command.LookDir(Direction.EAST), c2)
    }

    @Test
    fun `look direction invalid usage`() {
        val c = CommandParser.parse("look sideways")
        assertTrue(c is Command.Invalid)
    }

    @Test
    fun `parses goto with full zone and room`() {
        assertEquals(Command.Goto("demo_ruins:caravan_gate"), CommandParser.parse("goto demo_ruins:caravan_gate"))
    }

    @Test
    fun `parses goto with local room only`() {
        assertEquals(Command.Goto("caravan_gate"), CommandParser.parse("goto caravan_gate"))
    }

    @Test
    fun `parses goto with zone colon empty room`() {
        assertEquals(Command.Goto("demo_ruins:"), CommandParser.parse("goto demo_ruins:"))
    }

    @Test
    fun `goto with no arg returns Invalid`() {
        assertTrue(CommandParser.parse("goto") is Command.Invalid)
        assertTrue(CommandParser.parse("goto   ") is Command.Invalid)
    }

    @Test
    fun `parses transfer with player and room`() {
        assertEquals(Command.Transfer("Alice", "demo_ruins:caravan_gate"), CommandParser.parse("transfer Alice demo_ruins:caravan_gate"))
    }

    @Test
    fun `transfer with missing room returns Invalid`() {
        assertTrue(CommandParser.parse("transfer Alice") is Command.Invalid)
        assertTrue(CommandParser.parse("transfer Alice   ") is Command.Invalid)
    }

    @Test
    fun `transfer with no args returns Invalid`() {
        assertTrue(CommandParser.parse("transfer") is Command.Invalid)
    }

    @Test
    fun `parses spawn with local name`() {
        assertEquals(Command.Spawn("gate_scout"), CommandParser.parse("spawn gate_scout"))
    }

    @Test
    fun `parses spawn with fully qualified name`() {
        assertEquals(Command.Spawn("demo_ruins:gate_scout"), CommandParser.parse("spawn demo_ruins:gate_scout"))
    }

    @Test
    fun `spawn with no arg returns Invalid`() {
        assertTrue(CommandParser.parse("spawn") is Command.Invalid)
        assertTrue(CommandParser.parse("spawn   ") is Command.Invalid)
    }

    @Test
    fun `parses shutdown`() {
        assertEquals(Command.Shutdown, CommandParser.parse("shutdown"))
        assertEquals(Command.Shutdown, CommandParser.parse("  shutdown  "))
    }

    @Test
    fun `parses smite with target`() {
        assertEquals(Command.Smite("Alice"), CommandParser.parse("smite Alice"))
        assertEquals(Command.Smite("gate_scout"), CommandParser.parse("smite gate_scout"))
    }

    @Test
    fun `smite with no target returns Invalid`() {
        assertTrue(CommandParser.parse("smite") is Command.Invalid)
        assertTrue(CommandParser.parse("smite   ") is Command.Invalid)
    }

    @Test
    fun `parses kick with player name`() {
        assertEquals(Command.Kick("Bob"), CommandParser.parse("kick Bob"))
    }

    @Test
    fun `kick with no player returns Invalid`() {
        assertTrue(CommandParser.parse("kick") is Command.Invalid)
        assertTrue(CommandParser.parse("kick   ") is Command.Invalid)
    }

    @Test
    fun `parses cast with spell and target`() {
        assertEquals(Command.Cast("fireball", "rat"), CommandParser.parse("cast fireball rat"))
        assertEquals(Command.Cast("fireball", "rat"), CommandParser.parse("c fireball rat"))
    }

    @Test
    fun `parses cast with spell only (no target)`() {
        assertEquals(Command.Cast("heal", null), CommandParser.parse("cast heal"))
        assertEquals(Command.Cast("heal", null), CommandParser.parse("c heal"))
    }

    @Test
    fun `cast with no args returns Invalid`() {
        assertTrue(CommandParser.parse("cast") is Command.Invalid)
        assertTrue(CommandParser.parse("cast   ") is Command.Invalid)
        assertTrue(CommandParser.parse("c") is Command.Invalid) // "c" is a prefix for cast
    }

    @Test
    fun `parses spells and abilities`() {
        assertEquals(Command.Spells, CommandParser.parse("spells"))
        assertEquals(Command.Spells, CommandParser.parse("abilities"))
    }
}
