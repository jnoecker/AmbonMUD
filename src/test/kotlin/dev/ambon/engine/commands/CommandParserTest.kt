package dev.ambon.engine.commands

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
        assertEquals(Command.Remove("head"), CommandParser.parse("remove head"))
        assertEquals(Command.Remove("body"), CommandParser.parse("unequip body"))
    }

    @Test
    fun `remove validates slot names`() {
        assertTrue(CommandParser.parse("remove") is Command.Invalid)
        assertEquals(Command.Remove("feet"), CommandParser.parse("remove feet"))
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
    fun `parses quickheal and quickmana with aliases`() {
        assertEquals(Command.QuickHeal, CommandParser.parse("quickheal"))
        assertEquals(Command.QuickHeal, CommandParser.parse("qh"))
        assertEquals(Command.QuickHeal, CommandParser.parse("QuickHeal"))
        assertEquals(Command.QuickMana, CommandParser.parse("quickmana"))
        assertEquals(Command.QuickMana, CommandParser.parse("qm"))
        assertEquals(Command.QuickMana, CommandParser.parse("QUICKMANA"))
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
    fun `parses consider with full and short aliases`() {
        assertEquals(Command.Consider("orc"), CommandParser.parse("consider orc"))
        assertEquals(Command.Consider("orc"), CommandParser.parse("con orc"))
        assertTrue(CommandParser.parse("consider") is Command.Invalid)
    }

    @Test
    fun `parses wimpy with and without arg`() {
        assertEquals(Command.Wimpy(null), CommandParser.parse("wimpy"))
        assertEquals(Command.Wimpy("off"), CommandParser.parse("wimpy off"))
        assertEquals(Command.Wimpy("25"), CommandParser.parse("wimpy 25"))
        assertEquals(Command.Wimpy("on"), CommandParser.parse("wimpy on"))
    }

    @Test
    fun `parses look direction`() {
        val c1 = CommandParser.parse("look north")
        assertEquals(Command.LookDir(Direction.NORTH), c1)

        val c2 = CommandParser.parse("l e")
        assertEquals(Command.LookDir(Direction.EAST), c2)
    }

    @Test
    fun `look at target when not a direction`() {
        val c = CommandParser.parse("look sideways")
        assertEquals(Command.LookAt("sideways"), c)
    }

    @Test
    fun `look at multi-word target`() {
        val c = CommandParser.parse("look goblin warrior")
        assertEquals(Command.LookAt("goblin warrior"), c)
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
    fun `parses reload with and without target`() {
        assertEquals(Command.Reload(null), CommandParser.parse("reload"))
        assertEquals(Command.Reload("world"), CommandParser.parse("reload world"))
        assertEquals(Command.Reload("abilities"), CommandParser.parse("reload abilities"))
        assertEquals(Command.Reload("effects"), CommandParser.parse("reload effects"))
        assertEquals(Command.Reload("all"), CommandParser.parse("reload all"))
        assertEquals(Command.Reload("world"), CommandParser.parse("  reload   world  "))
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
    fun `parses setlevel with player and level`() {
        assertEquals(Command.SetLevel("Alice", 50), CommandParser.parse("setlevel Alice 50"))
        assertEquals(Command.SetLevel("Bob", 1), CommandParser.parse("setlevel Bob 1"))
    }

    @Test
    fun `setlevel with missing args returns Invalid`() {
        assertTrue(CommandParser.parse("setlevel") is Command.Invalid)
        assertTrue(CommandParser.parse("setlevel Alice") is Command.Invalid)
        assertTrue(CommandParser.parse("setlevel Alice notanumber") is Command.Invalid)
    }

    @Test
    fun `parses setgold with player and amount`() {
        assertEquals(Command.SetGold("Alice", 500), CommandParser.parse("setgold Alice 500"))
        assertEquals(Command.SetGold("Bob", 0), CommandParser.parse("setgold Bob 0"))
    }

    @Test
    fun `setgold with missing args returns Invalid`() {
        assertTrue(CommandParser.parse("setgold") is Command.Invalid)
        assertTrue(CommandParser.parse("setgold Alice") is Command.Invalid)
        assertTrue(CommandParser.parse("setgold Alice notanumber") is Command.Invalid)
    }

    @Test
    fun `parses setrace with player and race`() {
        assertEquals(Command.SetRace("Alice", "DRAGON"), CommandParser.parse("setrace Alice DRAGON"))
        assertEquals(Command.SetRace("Bob", "human"), CommandParser.parse("setrace Bob human"))
    }

    @Test
    fun `setrace with missing args returns Invalid`() {
        assertTrue(CommandParser.parse("setrace") is Command.Invalid)
        assertTrue(CommandParser.parse("setrace Alice") is Command.Invalid)
    }

    @Test
    fun `parses setclass with player and class`() {
        assertEquals(Command.SetClass("Alice", "MAGE"), CommandParser.parse("setclass Alice MAGE"))
        assertEquals(Command.SetClass("Bob", "warrior"), CommandParser.parse("setclass Bob warrior"))
    }

    @Test
    fun `setclass with missing args returns Invalid`() {
        assertTrue(CommandParser.parse("setclass") is Command.Invalid)
        assertTrue(CommandParser.parse("setclass Alice") is Command.Invalid)
    }

    @Test
    fun `parses setgender with player and gender`() {
        assertEquals(Command.StaffSetGender("Alice", "female"), CommandParser.parse("setgender Alice female"))
        assertEquals(Command.StaffSetGender("Bob", "enby"), CommandParser.parse("setgender Bob enby"))
    }

    @Test
    fun `setgender with missing args returns Invalid`() {
        assertTrue(CommandParser.parse("setgender") is Command.Invalid)
        assertTrue(CommandParser.parse("setgender Alice") is Command.Invalid)
    }

    @Test
    fun `parses setxp with player and amount`() {
        assertEquals(Command.SetXp("Alice", 10000), CommandParser.parse("setxp Alice 10000"))
        assertEquals(Command.SetXp("Bob", 0), CommandParser.parse("setxp Bob 0"))
    }

    @Test
    fun `setxp with missing args returns Invalid`() {
        assertTrue(CommandParser.parse("setxp") is Command.Invalid)
        assertTrue(CommandParser.parse("setxp Alice") is Command.Invalid)
        assertTrue(CommandParser.parse("setxp Alice notanumber") is Command.Invalid)
    }

    @Test
    fun `parses heal with optional player`() {
        assertEquals(Command.Heal(null), CommandParser.parse("heal"))
        assertEquals(Command.Heal("Alice"), CommandParser.parse("heal Alice"))
    }

    @Test
    fun `parses pinfo with player`() {
        assertEquals(Command.Pinfo("Alice"), CommandParser.parse("pinfo Alice"))
    }

    @Test
    fun `pinfo with no player returns Invalid`() {
        assertTrue(CommandParser.parse("pinfo") is Command.Invalid)
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
        assertEquals(Command.Spells, CommandParser.parse("skills"))
    }

    @Test
    fun `parses phase with no target`() {
        assertEquals(Command.Phase(null), CommandParser.parse("phase"))
        assertEquals(Command.Phase(null), CommandParser.parse("phase   "))
        assertEquals(Command.Phase(null), CommandParser.parse("layer"))
    }

    @Test
    fun `parses phase with target hint`() {
        assertEquals(Command.Phase("e2"), CommandParser.parse("phase e2"))
        assertEquals(Command.Phase("Alice"), CommandParser.parse("layer Alice"))
        assertEquals(Command.Phase("3"), CommandParser.parse("phase 3"))
    }

    @Test
    fun `parses effects and aliases`() {
        assertEquals(Command.Effects, CommandParser.parse("effects"))
        assertEquals(Command.Effects, CommandParser.parse("buffs"))
        assertEquals(Command.Effects, CommandParser.parse("debuffs"))
        assertEquals(Command.Effects, CommandParser.parse("  effects  "))
    }

    @Test
    fun `parses dispel with target`() {
        assertEquals(Command.Dispel("Alice"), CommandParser.parse("dispel Alice"))
        assertEquals(Command.Dispel("gate_scout"), CommandParser.parse("dispel gate_scout"))
    }

    @Test
    fun `dispel with no target returns Invalid`() {
        assertTrue(CommandParser.parse("dispel") is Command.Invalid)
        assertTrue(CommandParser.parse("dispel   ") is Command.Invalid)
    }

    @Test
    fun `parses balance and gold aliases`() {
        assertEquals(Command.Balance, CommandParser.parse("balance"))
        assertEquals(Command.Balance, CommandParser.parse("gold"))
        assertEquals(Command.Balance, CommandParser.parse("wealth"))
    }

    @Test
    fun `parses time command`() {
        assertEquals(Command.Time, CommandParser.parse("time"))
    }

    @Test
    fun `parses shop list aliases`() {
        assertEquals(Command.ShopList, CommandParser.parse("list"))
        assertEquals(Command.ShopList, CommandParser.parse("shop"))
    }

    @Test
    fun `parses buy command`() {
        assertEquals(Command.Buy("sword"), CommandParser.parse("buy sword"))
        assertEquals(Command.Buy("sword"), CommandParser.parse("purchase sword"))
    }

    @Test
    fun `buy with no arg returns Invalid`() {
        assertTrue(CommandParser.parse("buy") is Command.Invalid)
        assertTrue(CommandParser.parse("buy   ") is Command.Invalid)
    }

    @Test
    fun `parses sell command`() {
        assertEquals(Command.Sell("dagger"), CommandParser.parse("sell dagger"))
    }

    @Test
    fun `sell with no arg returns Invalid`() {
        assertTrue(CommandParser.parse("sell") is Command.Invalid)
        assertTrue(CommandParser.parse("sell   ") is Command.Invalid)
    }

    @Test
    fun `parses talk command`() {
        assertEquals(Command.Talk("keeper"), CommandParser.parse("talk keeper"))
        assertEquals(Command.Talk("portal keeper"), CommandParser.parse("talk portal keeper"))
    }

    @Test
    fun `talk with no arg returns Invalid`() {
        assertTrue(CommandParser.parse("talk") is Command.Invalid)
        assertTrue(CommandParser.parse("talk   ") is Command.Invalid)
    }

    @Test
    fun `bare numbers parse as DialogueChoice`() {
        assertEquals(Command.DialogueChoice(1), CommandParser.parse("1"))
        assertEquals(Command.DialogueChoice(2), CommandParser.parse("2"))
        assertEquals(Command.DialogueChoice(9), CommandParser.parse("9"))
        assertEquals(Command.DialogueChoice(1), CommandParser.parse("  1  "))
    }

    @Test
    fun `numbers 10-99 parse as DialogueChoice`() {
        assertEquals(Command.DialogueChoice(10), CommandParser.parse("10"))
        assertEquals(Command.DialogueChoice(99), CommandParser.parse("99"))
    }

    @Test
    fun `numbers outside 1-99 are Unknown`() {
        assertTrue(CommandParser.parse("0") is Command.Unknown)
        assertTrue(CommandParser.parse("100") is Command.Unknown)
        assertTrue(CommandParser.parse("-1") is Command.Unknown)
    }

    @Test
    fun `parses group invite`() {
        assertEquals(Command.GroupCmd.Invite("Bob"), CommandParser.parse("group invite Bob"))
        assertEquals(Command.GroupCmd.Invite("Bob"), CommandParser.parse("group inv Bob"))
    }

    @Test
    fun `parses group accept`() {
        assertEquals(Command.GroupCmd.Accept, CommandParser.parse("group accept"))
        assertEquals(Command.GroupCmd.Accept, CommandParser.parse("group acc"))
    }

    @Test
    fun `parses group decline`() {
        assertEquals(Command.GroupCmd.Decline, CommandParser.parse("group decline"))
        assertEquals(Command.GroupCmd.Decline, CommandParser.parse("group reject"))
    }

    @Test
    fun `parses group leave`() {
        assertEquals(Command.GroupCmd.Leave, CommandParser.parse("group leave"))
    }

    @Test
    fun `parses group kick`() {
        assertEquals(Command.GroupCmd.Kick("Bob"), CommandParser.parse("group kick Bob"))
    }

    @Test
    fun `parses group list`() {
        assertEquals(Command.GroupCmd.List, CommandParser.parse("group list"))
        assertEquals(Command.GroupCmd.List, CommandParser.parse("group"))
    }

    @Test
    fun `parses gtell`() {
        assertEquals(Command.Gtell("hello group"), CommandParser.parse("gtell hello group"))
        assertEquals(Command.Gtell("hello group"), CommandParser.parse("gt hello group"))
    }

    @Test
    fun `group invite without target is Invalid`() {
        assertTrue(CommandParser.parse("group invite") is Command.Invalid)
        assertTrue(CommandParser.parse("group inv") is Command.Invalid)
    }

    @Test
    fun `group kick without target is Invalid`() {
        assertTrue(CommandParser.parse("group kick") is Command.Invalid)
    }

    @Test
    fun `gtell without message is Invalid`() {
        assertTrue(CommandParser.parse("gtell") is Command.Invalid)
        assertTrue(CommandParser.parse("gt") is Command.Invalid)
    }

    @Test
    fun `parses gchat and g shorthand`() {
        assertEquals(Command.Gchat("Hello guild!"), CommandParser.parse("gchat Hello guild!"))
        assertEquals(Command.Gchat("Hello guild!"), CommandParser.parse("g Hello guild!"))
    }

    @Test
    fun `gchat without message is Invalid`() {
        assertTrue(CommandParser.parse("gchat") is Command.Invalid)
        assertTrue(CommandParser.parse("g") is Command.Invalid)
    }

    @Test
    fun `parses guild info and plain guild`() {
        assertEquals(Command.Guild.Info, CommandParser.parse("guild"))
        assertEquals(Command.Guild.Info, CommandParser.parse("guild info"))
    }

    @Test
    fun `parses guild create with single-word name`() {
        assertEquals(Command.Guild.Create("Shadowblade", "SB"), CommandParser.parse("guild create Shadowblade SB"))
    }

    @Test
    fun `parses guild create with multi-word name`() {
        assertEquals(Command.Guild.Create("Shadow Blade", "SB"), CommandParser.parse("guild create Shadow Blade SB"))
    }

    @Test
    fun `guild create with only one token is Invalid`() {
        assertTrue(CommandParser.parse("guild create SB") is Command.Invalid)
        assertTrue(CommandParser.parse("guild create") is Command.Invalid)
    }

    @Test
    fun `parses guild invite`() {
        assertEquals(Command.Guild.Invite("Bob"), CommandParser.parse("guild invite Bob"))
    }

    @Test
    fun `guild invite without target is Invalid`() {
        assertTrue(CommandParser.parse("guild invite") is Command.Invalid)
    }

    @Test
    fun `parses guild accept and decline`() {
        assertEquals(Command.Guild.Accept, CommandParser.parse("guild accept"))
        assertEquals(Command.Guild.Decline, CommandParser.parse("guild decline"))
        assertEquals(Command.Guild.Decline, CommandParser.parse("guild reject"))
    }

    // ---- Guild Hall ----

    @Test
    fun `parses guild hall`() {
        assertEquals(Command.Guild.Hall, CommandParser.parse("guild hall"))
    }

    @Test
    fun `parses guild hall buy`() {
        assertEquals(Command.Guild.HallBuy, CommandParser.parse("guild hall buy"))
        assertEquals(Command.Guild.HallBuy, CommandParser.parse("guild hall purchase"))
    }

    @Test
    fun `parses guild hall expand with template`() {
        assertEquals(Command.Guild.HallExpand("vault"), CommandParser.parse("guild hall expand vault"))
        assertEquals(Command.Guild.HallExpand("training_room"), CommandParser.parse("guild hall expand training_room"))
    }

    @Test
    fun `guild hall expand without template is Invalid`() {
        assertTrue(CommandParser.parse("guild hall expand") is Command.Invalid)
    }

    @Test
    fun `parses guild hall enter`() {
        assertEquals(Command.Guild.HallEnter, CommandParser.parse("guild hall enter"))
    }

    @Test
    fun `parses guild hall leave`() {
        assertEquals(Command.Guild.HallLeave, CommandParser.parse("guild hall leave"))
        assertEquals(Command.Guild.HallLeave, CommandParser.parse("guild hall exit"))
    }

    @Test
    fun `guild hall with unknown subcommand returns Hall`() {
        assertEquals(Command.Guild.Hall, CommandParser.parse("guild hall unknown"))
    }

    // ---- Crafting & Gathering ----

    @Test
    fun `parses gather command and aliases`() {
        assertEquals(Command.Gather("copper"), CommandParser.parse("gather copper"))
        assertEquals(Command.Gather("copper"), CommandParser.parse("harvest copper"))
        assertEquals(Command.Gather("iron"), CommandParser.parse("mine iron"))
    }

    @Test
    fun `gather without argument is Invalid`() {
        assertTrue(CommandParser.parse("gather") is Command.Invalid)
        assertTrue(CommandParser.parse("harvest") is Command.Invalid)
    }

    @Test
    fun `parses craft command and aliases`() {
        assertEquals(Command.Craft("copper sword"), CommandParser.parse("craft copper sword"))
        assertEquals(Command.Craft("potion"), CommandParser.parse("make potion"))
        assertEquals(Command.Craft("helm"), CommandParser.parse("create helm"))
    }

    @Test
    fun `craft without argument is Invalid`() {
        assertTrue(CommandParser.parse("craft") is Command.Invalid)
    }

    @Test
    fun `parses recipes command with and without filter`() {
        assertEquals(Command.Recipes(null), CommandParser.parse("recipes"))
        assertEquals(Command.Recipes("smithing"), CommandParser.parse("recipes smithing"))
        assertEquals(Command.Recipes("copper"), CommandParser.parse("recipe copper"))
    }

    @Test
    fun `parses craftskills command aliases`() {
        assertEquals(Command.CraftSkills, CommandParser.parse("craftskills"))
        assertEquals(Command.CraftSkills, CommandParser.parse("professions"))
        assertEquals(Command.CraftSkills, CommandParser.parse("prof"))
    }

    // -------- currencies command --------

    @Test
    fun `parses currencies command aliases`() {
        assertEquals(Command.Currencies, CommandParser.parse("currencies"))
        assertEquals(Command.Currencies, CommandParser.parse("currency"))
        assertEquals(Command.Currencies, CommandParser.parse("wallet"))
    }

    // -------- friend commands --------

    @Test
    fun `parses friend list`() {
        assertEquals(Command.Friend.List, CommandParser.parse("friend"))
        assertEquals(Command.Friend.List, CommandParser.parse("friend list"))
        assertEquals(Command.Friend.List, CommandParser.parse("friends"))
    }

    @Test
    fun `parses friend add`() {
        assertEquals(Command.Friend.Add("Bob"), CommandParser.parse("friend add Bob"))
    }

    @Test
    fun `friend add without target is Invalid`() {
        assertTrue(CommandParser.parse("friend add") is Command.Invalid)
    }

    @Test
    fun `parses friend remove`() {
        assertEquals(Command.Friend.Remove("Bob"), CommandParser.parse("friend remove Bob"))
        assertEquals(Command.Friend.Remove("Bob"), CommandParser.parse("friend rem Bob"))
        assertEquals(Command.Friend.Remove("Bob"), CommandParser.parse("friend del Bob"))
        assertEquals(Command.Friend.Remove("Bob"), CommandParser.parse("friend delete Bob"))
    }

    @Test
    fun `friend remove without target is Invalid`() {
        assertTrue(CommandParser.parse("friend remove") is Command.Invalid)
    }

    @Test
    fun `friend unknown subcommand is Invalid`() {
        assertTrue(CommandParser.parse("friend xyz") is Command.Invalid)
    }

    // ---- Housing commands ----

    @Test
    fun `house with no args parses as Status`() {
        assertEquals(Command.House.Status, CommandParser.parse("house"))
    }

    @Test
    fun `house status parses as Status`() {
        assertEquals(Command.House.Status, CommandParser.parse("house status"))
    }

    @Test
    fun `house list parses as ListTemplates`() {
        assertEquals(Command.House.ListTemplates, CommandParser.parse("house list"))
    }

    @Test
    fun `house buy parses as Buy`() {
        assertEquals(Command.House.Buy, CommandParser.parse("house buy"))
    }

    @Test
    fun `house expand parses template and direction`() {
        assertEquals(
            Command.House.Expand("vault", Direction.NORTH),
            CommandParser.parse("house expand vault north"),
        )
        assertEquals(
            Command.House.Expand("workshop", Direction.EAST),
            CommandParser.parse("house expand workshop e"),
        )
    }

    @Test
    fun `house expand without args is Invalid`() {
        assertTrue(CommandParser.parse("house expand") is Command.Invalid)
        assertTrue(CommandParser.parse("house expand vault") is Command.Invalid)
    }

    @Test
    fun `house expand with bad direction is Invalid`() {
        assertTrue(CommandParser.parse("house expand vault nowhere") is Command.Invalid)
    }

    @Test
    fun `house describe title parses`() {
        assertEquals(
            Command.House.SetTitle("My Cozy Cabin"),
            CommandParser.parse("house describe title My Cozy Cabin"),
        )
    }

    @Test
    fun `house describe desc parses`() {
        assertEquals(
            Command.House.SetDescription("A warm place."),
            CommandParser.parse("house describe desc A warm place."),
        )
    }

    @Test
    fun `house describe without subcommand is Invalid`() {
        assertTrue(CommandParser.parse("house describe") is Command.Invalid)
    }

    @Test
    fun `house invite parses player name`() {
        assertEquals(Command.House.Invite("Bob"), CommandParser.parse("house invite Bob"))
    }

    @Test
    fun `house invite without name is Invalid`() {
        assertTrue(CommandParser.parse("house invite") is Command.Invalid)
    }

    @Test
    fun `house kick parses player name`() {
        assertEquals(Command.House.Kick("Bob"), CommandParser.parse("house kick Bob"))
    }

    @Test
    fun `house guests parses`() {
        assertEquals(Command.House.Guests, CommandParser.parse("house guests"))
    }

    @Test
    fun `home alias parses as house status`() {
        assertEquals(Command.House.Status, CommandParser.parse("home"))
    }

    // ---- Input length limit ----

    @Test
    fun `rejects input exceeding max length`() {
        val longInput = "a".repeat(CommandParser.MAX_INPUT_LENGTH + 1)
        val result = CommandParser.parse(longInput)
        assertTrue(result is Command.Invalid, "Expected Invalid for oversized input, got=$result")
        val invalid = result as Command.Invalid
        assertTrue(invalid.usage!!.contains("too long"), "Expected 'too long' in usage, got=${invalid.usage}")
    }

    @Test
    fun `accepts input at max length`() {
        val maxInput = "a".repeat(CommandParser.MAX_INPUT_LENGTH)
        val result = CommandParser.parse(maxInput)
        assertTrue(
            result !is Command.Invalid || !(result as Command.Invalid).usage!!.contains("too long"),
            "Input at exactly max length should not be rejected for length",
        )
    }

    // ---- Auction sell price validation ----

    @Test
    fun `auction sell rejects zero price`() {
        val result = CommandParser.parse("auction sell sword 0")
        assertTrue(result is Command.Invalid, "Expected Invalid for zero price, got=$result")
        assertTrue((result as Command.Invalid).usage!!.contains("greater than zero"))
    }

    @Test
    fun `auction sell rejects negative price`() {
        val result = CommandParser.parse("auction sell sword -5")
        // Negative numbers won't parse via toLongOrNull when preceded by space, but just in case:
        assertTrue(result is Command.Invalid, "Expected Invalid for negative price, got=$result")
    }

    @Test
    fun `auction sell rejects price exceeding cap`() {
        val overMax = CommandParser.MAX_AUCTION_PRICE + 1
        val result = CommandParser.parse("auction sell sword $overMax")
        assertTrue(result is Command.Invalid, "Expected Invalid for price exceeding cap, got=$result")
        assertTrue((result as Command.Invalid).usage!!.contains("cannot exceed"))
    }

    @Test
    fun `auction sell accepts valid price`() {
        val result = CommandParser.parse("auction sell sword 100")
        assertEquals(Command.AuctionSell("sword", 100), result)
    }

    @Test
    fun `auction sell accepts price at cap`() {
        val result = CommandParser.parse("auction sell sword ${CommandParser.MAX_AUCTION_PRICE}")
        assertEquals(Command.AuctionSell("sword", CommandParser.MAX_AUCTION_PRICE), result)
    }

    // ---- Lottery / gambling command parsing ----

    @Test
    fun `lottery parses as LotteryInfo`() {
        assertEquals(Command.LotteryInfo, CommandParser.parse("lottery"))
        assertEquals(Command.LotteryInfo, CommandParser.parse("lottery info"))
        assertEquals(Command.LotteryInfo, CommandParser.parse("lottery status"))
    }

    @Test
    fun `lottery buy parses count`() {
        assertEquals(Command.LotteryBuy(1), CommandParser.parse("lottery buy"))
        assertEquals(Command.LotteryBuy(3), CommandParser.parse("lottery buy 3"))
        assertEquals(Command.LotteryBuy(10), CommandParser.parse("lottery buy 10"))
    }

    @Test
    fun `lottery buy rejects invalid count`() {
        assertTrue(CommandParser.parse("lottery buy 0") is Command.Invalid)
        assertTrue(CommandParser.parse("lottery buy -1") is Command.Invalid)
        assertTrue(CommandParser.parse("lottery buy abc") is Command.Invalid)
    }

    @Test
    fun `lottery rejects unknown subcommand`() {
        assertTrue(CommandParser.parse("lottery foo") is Command.Invalid)
    }

    @Test
    fun `gamble parses amount`() {
        assertEquals(Command.Gamble(100), CommandParser.parse("gamble 100"))
        assertEquals(Command.Gamble(50), CommandParser.parse("dice 50"))
    }

    @Test
    fun `gamble rejects missing or invalid amount`() {
        assertTrue(CommandParser.parse("gamble") is Command.Invalid)
        assertTrue(CommandParser.parse("gamble abc") is Command.Invalid)
        assertTrue(CommandParser.parse("gamble 0") is Command.Invalid)
        assertTrue(CommandParser.parse("gamble -5") is Command.Invalid)
    }

    @Test
    fun `petition parses keyword`() {
        assertEquals(Command.Petition("peanut"), CommandParser.parse("petition peanut"))
        assertEquals(Command.Petition("noecker"), CommandParser.parse("petition Noecker"))
    }

    @Test
    fun `petition without keyword is Invalid`() {
        assertTrue(CommandParser.parse("petition") is Command.Invalid)
    }

    // ---- Prestige ----

    @Test
    fun `prestige parses to Prestige`() {
        assertEquals(Command.Prestige, CommandParser.parse("prestige"))
    }

    @Test
    fun `prestige info parses to PrestigeInfo`() {
        assertEquals(Command.PrestigeInfo, CommandParser.parse("prestige info"))
    }

    @Test
    fun `prestige status parses to PrestigeInfo`() {
        assertEquals(Command.PrestigeInfo, CommandParser.parse("prestige status"))
    }

    @Test
    fun `prestige with unknown subcommand defaults to PrestigeInfo`() {
        assertEquals(Command.PrestigeInfo, CommandParser.parse("prestige perks"))
    }

    // ---------- daily / weekly quests ----------

    @Test
    fun `daily parses to DailyQuests`() {
        assertEquals(Command.DailyQuests, CommandParser.parse("daily"))
    }

    @Test
    fun `dailies alias parses to DailyQuests`() {
        assertEquals(Command.DailyQuests, CommandParser.parse("dailies"))
    }

    @Test
    fun `weekly parses to WeeklyQuests`() {
        assertEquals(Command.WeeklyQuests, CommandParser.parse("weekly"))
    }

    // ── Quest accept / turn-in / offers ──────────────────────────────────

    @Test
    fun `accept by name hint parses to QuestAccept`() {
        assertEquals(Command.QuestAccept("grand tour"), CommandParser.parse("accept grand tour"))
    }

    @Test
    fun `accept hash id parses to QuestAcceptById`() {
        assertEquals(
            Command.QuestAcceptById("academy:grand_tour"),
            CommandParser.parse("accept #academy:grand_tour"),
        )
    }

    @Test
    fun `accept blank hash is invalid`() {
        val cmd = CommandParser.parse("accept #")
        assertTrue(cmd is Command.Invalid, "expected Invalid, got $cmd")
    }

    @Test
    fun `quest turnin by name hint parses to QuestTurnIn`() {
        assertEquals(Command.QuestTurnIn("grand tour"), CommandParser.parse("quest turnin grand tour"))
    }

    @Test
    fun `quest turnin hash id parses to QuestTurnInById`() {
        assertEquals(
            Command.QuestTurnInById("academy:grand_tour"),
            CommandParser.parse("quest turnin #academy:grand_tour"),
        )
    }

    @Test
    fun `quest offers parses to QuestOffers`() {
        assertEquals(Command.QuestOffers("aldric"), CommandParser.parse("quest offers aldric"))
    }

    @Test
    fun `qoffers alias parses to QuestOffers`() {
        assertEquals(Command.QuestOffers("aldric"), CommandParser.parse("qoffers aldric"))
    }

    // ── Auto-quest / bounty commands ─────────────────────────────────────

    @Test
    fun `bounty parses to QuestAuto`() {
        assertEquals(Command.QuestAuto, CommandParser.parse("bounty"))
    }

    @Test
    fun `bounty info parses to QuestAutoInfo`() {
        assertEquals(Command.QuestAutoInfo, CommandParser.parse("bounty info"))
    }

    @Test
    fun `bounty abandon parses to QuestAutoAbandon`() {
        assertEquals(Command.QuestAutoAbandon, CommandParser.parse("bounty abandon"))
    }

    @Test
    fun `quest auto parses to QuestAuto`() {
        assertEquals(Command.QuestAuto, CommandParser.parse("quest auto"))
    }

    @Test
    fun `quest auto info parses to QuestAutoInfo`() {
        assertEquals(Command.QuestAutoInfo, CommandParser.parse("quest auto info"))
    }

    @Test
    fun `quest auto abandon parses to QuestAutoAbandon`() {
        assertEquals(Command.QuestAutoAbandon, CommandParser.parse("quest auto abandon"))
    }

    @Test
    fun `quest request parses to QuestAuto`() {
        assertEquals(Command.QuestAuto, CommandParser.parse("quest request"))
    }

    @Test
    fun `quest request info parses to QuestAutoInfo`() {
        assertEquals(Command.QuestAutoInfo, CommandParser.parse("quest request info"))
    }

    // ── Global quest commands ───────────────────────────────────────────

    @Test
    fun `gquest parses to GlobalQuestInfo`() {
        assertEquals(Command.GlobalQuestInfo, CommandParser.parse("gquest"))
    }

    @Test
    fun `gq parses to GlobalQuestInfo`() {
        assertEquals(Command.GlobalQuestInfo, CommandParser.parse("gq"))
    }

    @Test
    fun `global parses to GlobalQuestInfo`() {
        assertEquals(Command.GlobalQuestInfo, CommandParser.parse("global"))
    }

    // ---- Describe ----

    @Test
    fun `describe with text parses to Describe`() {
        assertEquals(Command.Describe("A tall elf."), CommandParser.parse("describe A tall elf."))
    }

    @Test
    fun `describe clear parses to DescribeClear`() {
        assertEquals(Command.DescribeClear, CommandParser.parse("describe clear"))
        assertEquals(Command.DescribeClear, CommandParser.parse("describe CLEAR"))
    }

    @Test
    fun `describe check with player name parses to DescribeCheck`() {
        assertEquals(Command.DescribeCheck("Alice"), CommandParser.parse("describe check Alice"))
    }

    @Test
    fun `describe without text is Invalid`() {
        assertTrue(CommandParser.parse("describe") is Command.Invalid)
    }

    @Test
    fun `describe check without player name is Invalid`() {
        assertTrue(CommandParser.parse("describe check") is Command.Invalid)
    }

    // ---- Areas ----

    @Test
    fun `areas with no args returns Areas with nulls`() {
        assertEquals(Command.Areas(null, null), CommandParser.parse("areas"))
        assertEquals(Command.Areas(null, null), CommandParser.parse("area"))
    }

    @Test
    fun `areas with single level returns Areas with same min and max`() {
        assertEquals(Command.Areas(5, 5), CommandParser.parse("areas 5"))
    }

    @Test
    fun `areas with two levels returns Areas with range`() {
        assertEquals(Command.Areas(3, 10), CommandParser.parse("areas 3 10"))
    }

    @Test
    fun `areas with bad range returns Invalid`() {
        assertTrue(CommandParser.parse("areas 10 3") is Command.Invalid)
        assertTrue(CommandParser.parse("areas foo") is Command.Invalid)
        assertTrue(CommandParser.parse("areas 1 2 3") is Command.Invalid)
        assertTrue(CommandParser.parse("areas -1") is Command.Invalid)
    }

    // ---- Run ----

    @Test
    fun `run parses single direction`() {
        assertEquals(Command.Run(listOf(Direction.NORTH)), CommandParser.parse("run n"))
    }

    @Test
    fun `run parses consecutive single chars`() {
        assertEquals(
            Command.Run(listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)),
            CommandParser.parse("run nsew"),
        )
    }

    @Test
    fun `run parses count prefixes`() {
        val expected = Command.Run(
            listOf(
                Direction.NORTH,
                Direction.NORTH,
                Direction.NORTH,
                Direction.NORTH,
                Direction.NORTH,
                Direction.EAST,
                Direction.EAST,
                Direction.EAST,
            ),
        )
        assertEquals(expected, CommandParser.parse("run 5n3e"))
    }

    @Test
    fun `run accepts up and down`() {
        assertEquals(
            Command.Run(listOf(Direction.UP, Direction.UP, Direction.DOWN)),
            CommandParser.parse("run 2u1d"),
        )
    }

    @Test
    fun `run ignores internal whitespace`() {
        assertEquals(
            Command.Run(listOf(Direction.NORTH, Direction.NORTH, Direction.EAST)),
            CommandParser.parse("run 2n 1e"),
        )
    }

    @Test
    fun `run with no arg is Invalid`() {
        assertTrue(CommandParser.parse("run") is Command.Invalid)
        assertTrue(CommandParser.parse("run   ") is Command.Invalid)
    }

    @Test
    fun `run with bad direction is Invalid`() {
        assertTrue(CommandParser.parse("run nxn") is Command.Invalid)
        assertTrue(CommandParser.parse("run 5") is Command.Invalid)
        assertTrue(CommandParser.parse("run 0n") is Command.Invalid)
    }

    @Test
    fun `run with too many steps is Invalid`() {
        assertTrue(CommandParser.parse("run 200n") is Command.Invalid)
    }
}
