package dev.ambon.engine.dialogue

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DialogueSystemTest {
    private val testRoom = RoomId("test:plaza")
    private val otherRoom = RoomId("test:alley")

    private val simpleDialogue =
        DialogueTree(
            rootNodeId = "root",
            nodes =
                mapOf(
                    "root" to
                        DialogueNode(
                            text = "Hello there!",
                            choices =
                                listOf(
                                    DialogueChoice("Tell me more.", "more", null, null),
                                    DialogueChoice("Goodbye.", null, null, null),
                                ),
                        ),
                    "more" to
                        DialogueNode(
                            text = "This is more info.",
                            choices =
                                listOf(
                                    DialogueChoice("Thanks.", null, null, null),
                                ),
                        ),
                ),
        )

    private val conditionalDialogue =
        DialogueTree(
            rootNodeId = "root",
            nodes =
                mapOf(
                    "root" to
                        DialogueNode(
                            text = "Welcome.",
                            choices =
                                listOf(
                                    DialogueChoice("Always visible.", "always", null, null),
                                    DialogueChoice("Level 5 only.", "level5", 5, null),
                                    DialogueChoice("Warriors only.", "warrior", null, "WARRIOR"),
                                ),
                        ),
                    "always" to
                        DialogueNode(
                            text = "You chose the open path.",
                            choices = emptyList(),
                        ),
                    "level5" to
                        DialogueNode(
                            text = "You are strong.",
                            choices = emptyList(),
                        ),
                    "warrior" to
                        DialogueNode(
                            text = "A true fighter.",
                            choices = emptyList(),
                        ),
                ),
        )

    private fun createEnv(withGmcp: Boolean = false): TestEnv {
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val items = ItemRegistry()
        val players = dev.ambon.test.buildTestPlayerRegistry(testRoom, InMemoryPlayerRepository(), items)
        val gmcpEmitter = if (withGmcp) {
            GmcpEmitter(outbound = outbound, supportsPackage = { _, _ -> true })
        } else {
            null
        }
        val system = DialogueSystem(mobs, players, outbound, gmcpEmitter)
        return TestEnv(mobs, players, outbound, system, items)
    }

    private data class TestEnv(
        val mobs: MobRegistry,
        val players: PlayerRegistry,
        val outbound: LocalOutboundBus,
        val system: DialogueSystem,
        val items: ItemRegistry,
    )

    private suspend fun TestEnv.loginPlayer(
        name: String = "Tester",
        playerClass: String = "WARRIOR",
        level: Int = 1,
    ): SessionId {
        val sid = SessionId(System.nanoTime())
        val res = players.login(sid, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
        val player = players.get(sid)!!
        player.playerClass = playerClass
        player.level = level
        outbound.drainAll()
        return sid
    }

    private fun TestEnv.addDialogueMob(
        dialogue: DialogueTree = simpleDialogue,
        room: RoomId = testRoom,
    ): MobId {
        val mobId = MobId("test:sage")
        mobs.upsert(
            MobState(
                id = mobId,
                name = "a wise sage",
                roomId = room,
                dialogue = dialogue,
            ),
        )
        return mobId
    }

    private fun TestEnv.addSilentMob(room: RoomId = testRoom): MobId {
        val mobId = MobId("test:grunt")
        mobs.upsert(
            MobState(
                id = mobId,
                name = "a burly grunt",
                roomId = room,
            ),
        )
        return mobId
    }

    @Test
    fun `start conversation with dialogue mob shows root text`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            env.addDialogueMob()

            val err = env.system.startConversation(sid, "sage")
            assertNull(err)

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("Hello there!") },
                "Expected root text. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("1.") },
                "Expected choice listing. got=$outs",
            )
        }

    @Test
    fun `start conversation with no mob returns error`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()

            val err = env.system.startConversation(sid, "sage")
            assertTrue(err != null && err.contains("don't see"), "Expected error. got=$err")
        }

    @Test
    fun `start conversation with silent mob returns error`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            env.addSilentMob()

            val err = env.system.startConversation(sid, "grunt")
            assertTrue(err != null && err.contains("nothing to say"), "Expected error. got=$err")
        }

    @Test
    fun `select valid choice advances to next node`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            val outcome = env.system.selectChoice(sid, 1) // "Tell me more."
            assertTrue(outcome is DialogueOutcome.Ok, "Expected Ok outcome. got=$outcome")

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("more info") },
                "Expected next node text. got=$outs",
            )
        }

    @Test
    fun `select choice that ends conversation`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            val outcome = env.system.selectChoice(sid, 2) // "Goodbye." (null next)
            assertTrue(outcome is DialogueOutcome.Ok, "Expected Ok outcome. got=$outcome")
            assertFalse(env.system.isInConversation(sid))
        }

    @Test
    fun `select invalid choice number returns error`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            val outcome = env.system.selectChoice(sid, 5)
            assertTrue(
                outcome is DialogueOutcome.Err && outcome.message.contains("between"),
                "Expected range error. got=$outcome",
            )
        }

    @Test
    fun `condition hides choice when level too low`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer(level = 1) // Level 5 required for choice 2
            env.addDialogueMob(dialogue = conditionalDialogue)
            env.system.startConversation(sid, "sage")

            val outs = env.outbound.drainAll()
            val infoTexts =
                outs
                    .filterIsInstance<OutboundEvent.SendInfo>()
                    .map { it.text }
            assertTrue(
                infoTexts.any { it.contains("Always visible") },
                "Always-visible choice should appear. got=$infoTexts",
            )
            assertFalse(
                infoTexts.any { it.contains("Level 5 only") },
                "Level-gated choice should be hidden. got=$infoTexts",
            )
        }

    @Test
    fun `condition shows choice when level sufficient`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer(level = 5)
            env.addDialogueMob(dialogue = conditionalDialogue)
            env.system.startConversation(sid, "sage")

            val outs = env.outbound.drainAll()
            val infoTexts =
                outs
                    .filterIsInstance<OutboundEvent.SendInfo>()
                    .map { it.text }
            assertTrue(
                infoTexts.any { it.contains("Level 5 only") },
                "Level-gated choice should appear at level 5. got=$infoTexts",
            )
        }

    @Test
    fun `condition hides choice for wrong class`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer(playerClass = "MAGE")
            env.addDialogueMob(dialogue = conditionalDialogue)
            env.system.startConversation(sid, "sage")

            val outs = env.outbound.drainAll()
            val infoTexts =
                outs
                    .filterIsInstance<OutboundEvent.SendInfo>()
                    .map { it.text }
            assertFalse(
                infoTexts.any { it.contains("Warriors only") },
                "Warrior-only choice should be hidden for mage. got=$infoTexts",
            )
        }

    @Test
    fun `condition shows choice for matching class`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer(playerClass = "WARRIOR")
            env.addDialogueMob(dialogue = conditionalDialogue)
            env.system.startConversation(sid, "sage")

            val outs = env.outbound.drainAll()
            val infoTexts =
                outs
                    .filterIsInstance<OutboundEvent.SendInfo>()
                    .map { it.text }
            assertTrue(
                infoTexts.any { it.contains("Warriors only") },
                "Warrior-only choice should appear for warrior. got=$infoTexts",
            )
        }

    @Test
    fun `conversation ends on player move with notification`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            assertTrue(env.system.isInConversation(sid))
            env.system.onPlayerMoved(sid)
            assertFalse(env.system.isInConversation(sid))

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("walk away") },
                "Expected walk-away notification. got=$outs",
            )
        }

    @Test
    fun `conversation ends on player disconnect`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            assertTrue(env.system.isInConversation(sid))
            env.system.onPlayerDisconnected(sid)
            assertFalse(env.system.isInConversation(sid))
        }

    @Test
    fun `starting a new conversation replaces the old one`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            env.addDialogueMob()

            env.system.startConversation(sid, "sage")
            assertTrue(env.system.isInConversation(sid))
            env.outbound.drainAll()

            // Start again — should reset to root
            env.system.startConversation(sid, "sage")
            assertTrue(env.system.isInConversation(sid))

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("Hello there!") },
                "Expected root text on re-start. got=$outs",
            )
        }

    @Test
    fun `onMobRemoved ends conversations with that mob and notifies player`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()
            val mobId = env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            assertTrue(env.system.isInConversation(sid))
            env.system.onMobRemoved(mobId)
            assertFalse(env.system.isInConversation(sid))

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("no longer available") },
                "Expected mob-removed notification. got=$outs",
            )
        }

    @Test
    fun `select choice when not in conversation returns error`() =
        runTest {
            val env = createEnv()
            val sid = env.loginPlayer()

            val outcome = env.system.selectChoice(sid, 1)
            assertTrue(
                outcome is DialogueOutcome.Err && outcome.message.contains("not in a conversation"),
                "Expected error. got=$outcome",
            )
        }

    // ── GMCP dialogue tests ──

    private fun List<OutboundEvent>.gmcpPackages(): List<OutboundEvent.GmcpData> =
        filterIsInstance<OutboundEvent.GmcpData>()

    @Test
    fun `start conversation emits Dialogue Node GMCP`() =
        runTest {
            val env = createEnv(withGmcp = true)
            val sid = env.loginPlayer()
            env.addDialogueMob()

            env.system.startConversation(sid, "sage")

            val gmcp = env.outbound.drainAll().gmcpPackages()
            val node = gmcp.find { it.gmcpPackage == "Dialogue.Node" }
            assertTrue(node != null, "Expected Dialogue.Node GMCP. got=$gmcp")
            assertTrue(node!!.jsonData.contains("\"mobName\":\"a wise sage\""))
            assertTrue(node.jsonData.contains("\"text\":\"Hello there!\""))
            assertTrue(node.jsonData.contains("\"index\":1"))
            assertTrue(node.jsonData.contains("\"index\":2"))
        }

    @Test
    fun `select terminal choice emits Dialogue End GMCP`() =
        runTest {
            val env = createEnv(withGmcp = true)
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            env.system.selectChoice(sid, 2) // "Goodbye." (null next)

            val gmcp = env.outbound.drainAll().gmcpPackages()
            val end = gmcp.find { it.gmcpPackage == "Dialogue.End" }
            assertTrue(end != null, "Expected Dialogue.End GMCP. got=$gmcp")
            assertTrue(end!!.jsonData.contains("\"reason\":\"ended\""))
        }

    @Test
    fun `advancing to next node emits Dialogue Node GMCP`() =
        runTest {
            val env = createEnv(withGmcp = true)
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            env.system.selectChoice(sid, 1) // "Tell me more."

            val gmcp = env.outbound.drainAll().gmcpPackages()
            val node = gmcp.find { it.gmcpPackage == "Dialogue.Node" }
            assertTrue(node != null, "Expected Dialogue.Node GMCP. got=$gmcp")
            assertTrue(node!!.jsonData.contains("\"text\":\"This is more info.\""))
        }

    @Test
    fun `player move emits Dialogue End GMCP with moved reason`() =
        runTest {
            val env = createEnv(withGmcp = true)
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            env.system.onPlayerMoved(sid)

            val gmcp = env.outbound.drainAll().gmcpPackages()
            val end = gmcp.find { it.gmcpPackage == "Dialogue.End" }
            assertTrue(end != null, "Expected Dialogue.End GMCP. got=$gmcp")
            assertTrue(end!!.jsonData.contains("\"reason\":\"moved\""))
        }

    @Test
    fun `mob removal emits Dialogue End GMCP with mob_removed reason`() =
        runTest {
            val env = createEnv(withGmcp = true)
            val sid = env.loginPlayer()
            val mobId = env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            env.system.onMobRemoved(mobId)

            val gmcp = env.outbound.drainAll().gmcpPackages()
            val end = gmcp.find { it.gmcpPackage == "Dialogue.End" }
            assertTrue(end != null, "Expected Dialogue.End GMCP. got=$gmcp")
            assertTrue(end!!.jsonData.contains("\"reason\":\"mob_removed\""))
        }

    @Test
    fun `disconnect emits Dialogue End GMCP with disconnected reason`() =
        runTest {
            val env = createEnv(withGmcp = true)
            val sid = env.loginPlayer()
            env.addDialogueMob()
            env.system.startConversation(sid, "sage")
            env.outbound.drainAll()

            env.system.onPlayerDisconnected(sid)

            val gmcp = env.outbound.drainAll().gmcpPackages()
            val end = gmcp.find { it.gmcpPackage == "Dialogue.End" }
            assertTrue(end != null, "Expected Dialogue.End GMCP. got=$gmcp")
            assertTrue(end!!.jsonData.contains("\"reason\":\"disconnected\""))
        }

    @Test
    fun `auto-end node emits Dialogue End GMCP`() =
        runTest {
            val autoEndDialogue = DialogueTree(
                rootNodeId = "root",
                nodes = mapOf(
                    "root" to DialogueNode(
                        text = "The gate closes behind you.",
                        choices = emptyList(),
                    ),
                ),
            )
            val env = createEnv(withGmcp = true)
            val sid = env.loginPlayer()
            env.addDialogueMob(dialogue = autoEndDialogue)

            env.system.startConversation(sid, "sage")

            val gmcp = env.outbound.drainAll().gmcpPackages()
            val end = gmcp.find { it.gmcpPackage == "Dialogue.End" }
            assertTrue(end != null, "Expected Dialogue.End for auto-end node. got=$gmcp")
            assertTrue(end!!.jsonData.contains("\"reason\":\"ended\""))
        }
}
