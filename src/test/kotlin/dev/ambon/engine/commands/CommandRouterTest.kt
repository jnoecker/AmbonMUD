package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.PrestigeConfig
import dev.ambon.domain.crafting.GatheringNodeDef
import dev.ambon.domain.crafting.GatheringYield
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.LoginResult
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PrestigePerkPayload
import dev.ambon.engine.PrestigeSystem
import dev.ambon.engine.commands.handlers.NavigationHandler
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterTest {
    @Test
    fun `look emits room title description exits and prompt`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(1)
            h.loginPlayer(sid, "Player1")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)

            h.router.handle(sid, Command.Look)

            val outs = h.drain()

            // Expected: SendText(title), SendText(desc), SendInfo(exits), SendPrompt
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == startRoom.title },
                "Missing title '${startRoom.title}'. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains(startRoom.description.take(10)) },
                "Missing description containing '${startRoom.description.take(10)}...'. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.startsWith("Exits:") },
                "Missing exits. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendPrompt },
                "Missing prompt. got=$outs",
            )
        }

    @Test
    fun `look includes players currently in room`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Look)

            val outs = h.drain()
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo && it.text == "Players here: Alice, Bob"
                },
                "Expected room roster line. got=$outs",
            )
        }

    @Test
    fun `move north changes room and then look describes new room`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(2)
            h.loginPlayer(sid, "Player2")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val northTargetId = startRoom.exits[Direction.NORTH]

            // If the demo world doesn't have a north exit, that's a test setup issue; fail loudly.
            Assertions.assertNotNull(
                northTargetId,
                "Demo world start room '${startRoom.id}' must have a NORTH exit for this test",
            )

            val northRoom = h.world.rooms.getValue(northTargetId!!)

            h.router.handle(sid, Command.Move(Direction.NORTH))

            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == northRoom.title },
                "Expected to see new room title '${northRoom.title}' after moving north. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendPrompt },
                "Missing prompt. got=$outs",
            )
        }

    @Test
    fun `move broadcasts leave and enter to room members`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            val charlie = SessionId(3)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.loginPlayer(charlie, "Charlie")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val northTargetId = startRoom.exits[Direction.NORTH]
            Assertions.assertNotNull(
                northTargetId,
                "Demo world start room '${startRoom.id}' must have a NORTH exit for this test",
            )

            // Move Charlie to the north room so Alice will "enter" his room.
            h.router.handle(charlie, Command.Move(Direction.NORTH))
            h.drain()

            h.router.handle(alice, Command.Move(Direction.NORTH))
            val outs = h.drain()

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText && it.sessionId == bob && it.text == "Alice exits to the north."
                },
                "Bob should see directional leave broadcast. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText && it.sessionId == charlie && it.text == "Alice arrives from the south."
                },
                "Charlie should see directional enter broadcast. got=$outs",
            )
        }

    @Test
    fun `move blocked emits can't go that way and prompt`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(3)
            h.loginPlayer(sid, "Player3")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val missingDir =
                Direction.entries.firstOrNull { it !in startRoom.exits.keys }
                    ?: error("Demo world start room must be missing at least one direction for this test")

            h.router.handle(sid, Command.Move(missingDir))

            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("can't go that way", ignoreCase = true) },
                "Expected blocked movement message. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    @Test
    fun `unknown typed input emits UI feedback for GMCP clients`() =
        runTest {
            val outbound = LocalOutboundBus()
            val h =
                CommandRouterHarness.create(
                    outbound = outbound,
                    gmcpEmitter = GmcpEmitter(outbound = outbound, supportsPackage = { _, pkg -> pkg == "UI.Feedback" }),
                )
            val sid = SessionId(30)
            h.loginPlayer(sid, "Player30")
            h.drain()

            h.router.handle(sid, Command.Unknown("dance wildly"))

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == sid && it.text == "Huh?" },
                "Expected legacy text error to remain. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendPrompt && it.sessionId == sid },
                "Expected prompt after unknown command. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.GmcpData &&
                        it.sessionId == sid &&
                        it.gmcpPackage == "UI.Feedback" &&
                        it.jsonData.contains("\"code\":\"UNRECOGNIZED_TYPED_INPUT\"") &&
                        it.jsonData.contains("\"scope\":\"input\"") &&
                        it.jsonData.contains("\"command\":\"dance\"") &&
                        it.jsonData.contains("on-screen controls")
                },
                "Expected UI.Feedback payload for unknown typed input. got=$outs",
            )
        }

    @Test
    fun `invalid typed input emits UI feedback for GMCP clients`() =
        runTest {
            val outbound = LocalOutboundBus()
            val h =
                CommandRouterHarness.create(
                    outbound = outbound,
                    gmcpEmitter = GmcpEmitter(outbound = outbound, supportsPackage = { _, pkg -> pkg == "UI.Feedback" }),
                )
            val sid = SessionId(31)
            h.loginPlayer(sid, "Player31")
            h.drain()

            h.router.handle(sid, Command.Invalid("buy", "buy <item>"))

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == sid && it.text == "Invalid command: buy" },
                "Expected invalid command error text. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == sid && it.text == "Usage: buy <item>" },
                "Expected usage text to remain. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.GmcpData &&
                        it.sessionId == sid &&
                        it.gmcpPackage == "UI.Feedback" &&
                        it.jsonData.contains("\"code\":\"UNSUPPORTED_TYPED_INPUT\"") &&
                        it.jsonData.contains("\"scope\":\"input\"") &&
                        it.jsonData.contains("\"command\":\"buy\"") &&
                        it.jsonData.contains("isn't supported in this format")
                },
                "Expected UI.Feedback payload for invalid typed input. got=$outs",
            )
        }

    @Test
    fun `prestige command emits refreshed Prestige Info for GMCP clients`() =
        runTest {
            val outbound = LocalOutboundBus()
            val progression = PlayerProgression()
            val prestigeSystem = PrestigeSystem(PrestigeConfig(), progression)
            val h =
                CommandRouterHarness.create(
                    outbound = outbound,
                    progression = progression,
                    gmcpEmitter =
                        GmcpEmitter(
                            outbound = outbound,
                            supportsPackage = { _, pkg -> pkg == "Prestige" || pkg == "Char.Vitals" || pkg == "UI.Feedback" },
                            progression = progression,
                            prestigeEnabled = { prestigeSystem.isEnabled() },
                            prestigeMaxRank = { prestigeSystem.maxRank },
                            prestigeAvailableXp = { player -> prestigeSystem.availableXp(player) },
                            prestigeNextCost = { rank -> prestigeSystem.xpCostForNextRank(rank) },
                            prestigePerkPayloads = { currentRank, maxRank ->
                                (1..maxRank).map { rank ->
                                    val perk = prestigeSystem.perkForRank(rank)
                                    PrestigePerkPayload(
                                        rank = rank,
                                        type = perk?.type?.uppercase() ?: "",
                                        description = perk?.description ?: "-",
                                        earned = rank <= currentRank,
                                    )
                                }
                            },
                        ),
                )
            val sid = SessionId(32)
            h.loginPlayer(sid, "Player32")
            h.players.get(sid)!!.apply {
                level = progression.maxLevel
                xpTotal = progression.totalXpForLevel(level) + prestigeSystem.xpCostForNextRank(0) + 500L
            }
            h.drain()

            h.router.handle(sid, Command.Prestige)

            val outs = h.drain()
            assertTrue(
                outs.any {
                    it is OutboundEvent.GmcpData &&
                        it.sessionId == sid &&
                        it.gmcpPackage == "Char.Vitals" &&
                        it.jsonData.contains("\"prestigeLevel\":1")
                },
                "Expected Char.Vitals payload with updated prestige rank. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.GmcpData &&
                        it.sessionId == sid &&
                        it.gmcpPackage == "Prestige.Info" &&
                        it.jsonData.contains("\"currentRank\":1") &&
                        it.jsonData.contains("\"earned\":true")
                },
                "Expected Prestige.Info payload after prestige action. got=$outs",
            )
        }

    @Test
    fun `tell to unknown name emits error to sender only`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Tell("Charlie", "hi"))
            val outs = h.drain()

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError &&
                        it.sessionId == alice &&
                        it.text.contains(
                            "No such player",
                            ignoreCase = true,
                        )
                },
                "Expected error to sender for unknown name. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bob && it.text.contains("hi") },
                "Other players should not receive tells to unknown. got=$outs",
            )
        }

    @Test
    fun `tell delivers to target only and not to third party`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            val eve = SessionId(3)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.loginPlayer(eve, "Eve")
            h.drain()

            h.router.handle(alice, Command.Tell("Bob", "secret"))
            val outs = h.drain()

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == alice &&
                        it.text.contains("You tell", ignoreCase = true) &&
                        it.text.contains("secret")
                },
                "Sender should get confirmation. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == bob &&
                        it.text.contains("tells you", ignoreCase = true) &&
                        it.text.contains("secret")
                },
                "Target should receive tell. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == eve && it.text.contains("secret") },
                "Third party should not see tell. got=$outs",
            )
        }

    @Test
    fun `say broadcasts only to room members and echoes to sender`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.Say("hello"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == alice && it.text == "You say: hello" },
                "Sender should see 'You say'. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bob && it.text == "Alice says: hello" },
                "Other room member should see broadcast. got=$outs",
            )
        }

    @Test
    fun `gossip broadcasts to all connected`() =
        runTest {
            val h = CommandRouterHarness.create()

            val alice = SessionId(1)
            val bob = SessionId(2)
            val eve = SessionId(3)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.loginPlayer(eve, "Eve")
            h.drain()

            h.router.handle(alice, Command.Gossip("hello all"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == alice && it.text.contains("You gossip: hello all") },
                "Sender should see self-gossip line. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == bob && it.text.contains("[GOSSIP] Alice: hello all") },
                "Other players should see gossip. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == eve && it.text.contains("[GOSSIP] Alice: hello all") },
                "All players should see gossip. got=$outs",
            )
        }

    @Test
    fun `login name uniqueness is case-insensitive`() =
        runTest {
            val h = CommandRouterHarness.create()

            val a = SessionId(1)
            val b = SessionId(2)

            val res1 = h.players.login(a, "Alice", "password")
            val res2 = h.players.login(b, "alice", "password")

            assertEquals(LoginResult.Ok, res1)
            assertTrue(res2 is LoginResult.Takeover, "Expected Takeover for duplicate name with correct password. got=$res2")
        }

    @Test
    fun `exits emits exits line and prompt only`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(10)
            h.loginPlayer(sid, "Player10")

            h.router.handle(sid, Command.Exits)

            val outs = h.drain()

            // Only exits + prompt (no title/description)
            assertTrue(outs.any { it is OutboundEvent.SendInfo && it.text.startsWith("Exits:") }, "Missing exits line. got=$outs")
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
            assertFalse(outs.any { it is OutboundEvent.SendText }, "Should not send title/desc for exits. got=$outs")
        }

    @Test
    fun `look dir shows adjacent room title when exit exists`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(11)
            h.loginPlayer(sid, "Player11")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val (dir, targetId) =
                startRoom.exits.entries.firstOrNull()
                    ?: error("Demo world start room must have at least one exit for this test")

            val target = h.world.rooms.getValue(targetId)

            h.router.handle(sid, Command.LookDir(dir))

            val outs = h.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == target.title },
                "Expected target title '${target.title}'. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    @Test
    fun `look dir shows message when no exit exists`() =
        runTest {
            val h = CommandRouterHarness.create()

            val sid = SessionId(12)
            h.loginPlayer(sid, "Player12")

            val startRoom = h.world.rooms.getValue(h.world.startRoom)
            val missingDir =
                Direction.entries.firstOrNull { it !in startRoom.exits.keys }
                    ?: error("Demo world start room must be missing at least one direction for this test")

            h.router.handle(sid, Command.LookDir(missingDir))

            val outs = h.drain()
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendError && it.text.contains("nothing", ignoreCase = true)
                },
                "Expected 'nothing that way' message. got=$outs",
            )
            assertTrue(outs.any { it is OutboundEvent.SendPrompt }, "Missing prompt. got=$outs")
        }

    @Test
    fun `look shows gathering nodes in room`() =
        runTest {
            val startRoom = RoomId("zone:hub")
            val world = World(
                rooms = mapOf(
                    startRoom to Room(
                        id = startRoom,
                        title = "Mine Tunnel",
                        description = "A rough tunnel.",
                        exits = emptyMap(),
                    ),
                ),
                startRoom = startRoom,
            )
            val registry = GatheringRegistry()
            registry.register(
                listOf(
                    GatheringNodeDef(
                        id = "zone:copper_vein",
                        displayName = "a copper ore vein",
                        keyword = "copper",
                        skill = "mining",
                        yields = listOf(GatheringYield(ItemId("zone:copper_ore"))),
                        roomId = startRoom,
                    ),
                    GatheringNodeDef(
                        id = "zone:iron_vein",
                        displayName = "an iron ore vein",
                        keyword = "iron",
                        skill = "mining",
                        skillRequired = 15,
                        yields = listOf(GatheringYield(ItemId("zone:iron_ore"))),
                        roomId = startRoom,
                    ),
                ),
            )
            val h = CommandRouterHarness.create(world = world, gatheringRegistry = registry)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Player1")
            h.drain()

            h.router.handle(sid, Command.Look)
            val outs = h.drain()

            val resourceLine = outs.filterIsInstance<OutboundEvent.SendInfo>()
                .firstOrNull { it.text.startsWith("Resources:") }
            assertTrue(resourceLine != null, "Expected Resources line in look output. got=$outs")
            assertTrue(
                resourceLine!!.text.contains("a copper ore vein"),
                "Expected copper vein in resources. got=${resourceLine.text}",
            )
            assertTrue(
                resourceLine.text.contains("an iron ore vein"),
                "Expected iron vein in resources. got=${resourceLine.text}",
            )
        }

    @Test
    fun `look shows crafting station when room has one`() =
        runTest {
            val startRoom = RoomId("zone:forge")
            val world = World(
                rooms = mapOf(
                    startRoom to Room(
                        id = startRoom,
                        title = "The Forge",
                        description = "A sweltering room.",
                        exits = emptyMap(),
                        station = "forge",
                    ),
                ),
                startRoom = startRoom,
            )
            val h = CommandRouterHarness.create(world = world)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Player1")
            h.drain()

            h.router.handle(sid, Command.Look)
            val outs = h.drain()

            val stationLine = outs.filterIsInstance<OutboundEvent.SendInfo>()
                .firstOrNull { it.text.startsWith("Crafting station:") }
            assertTrue(stationLine != null, "Expected Crafting station line. got=$outs")
            assertEquals("Crafting station: Forge", stationLine!!.text)
        }

    @Test
    fun `look omits resources line when no nodes in room`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Player1")
            h.drain()

            h.router.handle(sid, Command.Look)
            val outs = h.drain()

            val hasResources = outs.filterIsInstance<OutboundEvent.SendInfo>()
                .any { it.text.startsWith("Resources:") }
            assertFalse(hasResources, "Should not have Resources line when no nodes exist. got=$outs")
        }

    @Test
    fun `look shows alchemy table station display name`() =
        runTest {
            val startRoom = RoomId("zone:lab")
            val world = World(
                rooms = mapOf(
                    startRoom to Room(
                        id = startRoom,
                        title = "Alchemy Lab",
                        description = "Glass vials everywhere.",
                        exits = emptyMap(),
                        station = "alchemy_table",
                    ),
                ),
                startRoom = startRoom,
            )
            val h = CommandRouterHarness.create(world = world)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Player1")
            h.drain()

            h.router.handle(sid, Command.Look)
            val outs = h.drain()

            val stationLine = outs.filterIsInstance<OutboundEvent.SendInfo>()
                .firstOrNull { it.text.startsWith("Crafting station:") }
            assertTrue(stationLine != null, "Expected Crafting station line. got=$outs")
            assertEquals("Crafting station: Alchemy Table", stationLine!!.text)
        }

    // ---- Describe ----

    @Test
    fun `describe sets description on player`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            h.drain()

            h.router.handle(sid, Command.Describe("A scarred warrior."))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text == "Description set." },
                "Expected confirmation. got=$outs",
            )
            assertEquals("A scarred warrior.", h.players.get(sid)!!.description)
        }

    @Test
    fun `describe clear resets description`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            h.players.get(sid)!!.description = "Old text"
            h.drain()

            h.router.handle(sid, Command.DescribeClear)
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text == "Description cleared." },
                "Expected confirmation. got=$outs",
            )
            assertEquals("", h.players.get(sid)!!.description)
        }

    @Test
    fun `describe rejects text over 500 characters`() =
        runTest {
            val h = CommandRouterHarness.create()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Hero")
            h.drain()

            val longText = "x".repeat(501)
            h.router.handle(sid, Command.Describe(longText))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("too long") },
                "Expected error for long description. got=$outs",
            )
            assertEquals("", h.players.get(sid)!!.description)
        }

    @Test
    fun `look at player shows description when set`() =
        runTest {
            val h = CommandRouterHarness.create()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.players.get(bob)!!.description = "A mysterious figure in a dark cloak."
            h.drain()

            h.router.handle(alice, Command.LookAt("Bob"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("A mysterious figure") },
                "Expected custom description. got=$outs",
            )
        }

    @Test
    fun `look at player without description shows only level line`() =
        runTest {
            val h = CommandRouterHarness.create()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.drain()

            h.router.handle(alice, Command.LookAt("Bob"))
            val outs = h.drain()

            val textLines = outs.filterIsInstance<OutboundEvent.SendText>()
            assertEquals(1, textLines.size, "Expected only the level line. got=$textLines")
            assertTrue(textLines[0].text.startsWith("You see Bob"), "got=${textLines[0].text}")
        }

    @Test
    fun `look at pledged player shows the Akathavae pledge line`() =
        runTest {
            val h = CommandRouterHarness.create()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.players.get(bob)!!.isAkathavae = true
            h.drain()

            h.router.handle(alice, Command.LookAt("Bob"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text == NavigationHandler.AKATHAVAE_PLEDGE_LINE },
                "Expected the Akathavae pledge line. got=$outs",
            )
        }

    @Test
    fun `pledge line in look derives from live state and vanishes after renounce`() =
        runTest {
            val h = CommandRouterHarness.create()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.players.get(bob)!!.isAkathavae = true
            h.drain()

            h.router.handle(alice, Command.LookAt("Bob"))
            val pledged = h.drain()
            assertTrue(
                pledged.any { it is OutboundEvent.SendText && it.text == NavigationHandler.AKATHAVAE_PLEDGE_LINE },
                "Expected the pledge line while pledged. got=$pledged",
            )

            h.players.get(bob)!!.isAkathavae = false
            h.router.handle(alice, Command.LookAt("Bob"))
            val renounced = h.drain()
            assertFalse(
                renounced.any { it is OutboundEvent.SendText && it.text == NavigationHandler.AKATHAVAE_PLEDGE_LINE },
                "Pledge line must disappear after renouncing. got=$renounced",
            )
        }

    @Test
    fun `describe check requires staff`() =
        runTest {
            val h = CommandRouterHarness.create()
            val alice = SessionId(1)
            val bob = SessionId(2)
            h.loginPlayer(alice, "Alice")
            h.loginPlayer(bob, "Bob")
            h.players.get(bob)!!.description = "Some desc"
            h.drain()

            h.router.handle(alice, Command.DescribeCheck("Bob"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text == "You are not staff." },
                "Expected staff-only error. got=$outs",
            )
        }

    @Test
    fun `describe check by staff shows target description`() =
        runTest {
            val h = CommandRouterHarness.create()
            val staffSid = SessionId(1)
            val bob = SessionId(2)
            h.loginStaff(staffSid, "Admin")
            h.loginPlayer(bob, "Bob")
            h.players.get(bob)!!.description = "Tall and imposing."
            h.drain()

            h.router.handle(staffSid, Command.DescribeCheck("Bob"))
            val outs = h.drain()

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("Tall and imposing.") },
                "Expected target's description. got=$outs",
            )
        }
}
