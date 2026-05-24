package dev.ambon.engine.commands.handlers

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.BankConfig
import dev.ambon.config.StylistConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.QuestRegistry
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.dialogue.DialogueChoice
import dev.ambon.engine.dialogue.DialogueNode
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.dialogue.DialogueTree
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.TestWorlds
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DialogueQuestActionTest {
    private val world = TestWorlds.testWorld
    private val startRoom = world.startRoom
    private val giverMobId = "test_zone:giver"
    private val questId = "test_zone:fetch"

    /**
     * Quest with a single zero-count objective, so its objectives are
     * immediately complete on accept; `npc_turn_in` completion keeps it in the
     * active list (no auto-complete), so a subsequent turn-in dialogue choice
     * has something to hand in.
     */
    private val quest =
        QuestDef(
            id = questId,
            name = "Fetch",
            description = "A simple fetch.",
            giverMobId = giverMobId,
            objectives =
                listOf(
                    QuestObjectiveDef(
                        type = "kill",
                        targetId = "test_zone:nonexistent",
                        count = 0,
                        description = "Nothing to do",
                    ),
                ),
            rewards = QuestRewards(),
            completionType = "npc_turn_in",
        )

    private val dialogueTree =
        DialogueTree(
            rootNodeId = "root",
            nodes =
                mapOf(
                    "root" to
                        DialogueNode(
                            text = "Will you help?",
                            choices =
                                listOf(
                                    DialogueChoice(
                                        text = "Accept",
                                        nextNodeId = null,
                                        minLevel = null,
                                        requiredClass = null,
                                        action = "accept_quest:$questId",
                                    ),
                                    DialogueChoice(
                                        text = "Turn it in",
                                        nextNodeId = null,
                                        minLevel = null,
                                        requiredClass = null,
                                        action = "turn_in_quest:$questId",
                                    ),
                                    DialogueChoice(
                                        text = "Accept unknown",
                                        nextNodeId = null,
                                        minLevel = null,
                                        requiredClass = null,
                                        action = "accept_quest:test_zone:does_not_exist",
                                    ),
                                    DialogueChoice(
                                        text = "Accept (bare id)",
                                        nextNodeId = null,
                                        minLevel = null,
                                        requiredClass = null,
                                        // Unqualified id — the handler should auto-qualify
                                        // it against the conversation NPC's zone.
                                        action = "accept_quest:fetch",
                                    ),
                                ),
                        ),
                ),
        )

    private data class Env(
        val sid: SessionId,
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val outbound: LocalOutboundBus,
        val router: CommandRouter,
        val questSystem: QuestSystem,
    )

    private suspend fun setup(): Env {
        val repo = InMemoryPlayerRepository()
        val items = ItemRegistry()
        val outbound = LocalOutboundBus()
        val clock = MutableClock(0L)
        val players = buildTestPlayerRegistry(startRoom, repo, items, clock = clock)
        val mobs = MobRegistry()
        val combat = CombatSystem(players, mobs, items, outbound)

        mobs.upsert(
            MobState(
                id = MobId(giverMobId),
                name = "the giver",
                roomId = startRoom,
                dialogue = dialogueTree,
            ),
        )

        val questRegistry = QuestRegistry().apply { register(quest) }
        val questSystem =
            QuestSystem(
                registry = questRegistry,
                players = players,
                items = items,
                outbound = outbound,
                clock = clock,
            )
        val dialogueSystem = DialogueSystem(mobs, players, outbound)

        val ctx =
            EngineContext(
                players = players,
                mobs = mobs,
                world = world,
                items = items,
                outbound = outbound,
                combat = combat,
                gmcpEmitter = null,
                worldState = null,
                questSystem = questSystem,
                bankConfig = BankConfig(),
                stylistConfig = StylistConfig(),
            )
        val handler =
            DialogueQuestHandler(
                ctx = ctx,
                dialogueSystem = dialogueSystem,
                questSystem = questSystem,
                questRegistry = questRegistry,
            )

        val router = CommandRouter(outbound = outbound, players = players)
        handler.register(router)

        val sid = SessionId(1L)
        players.loginOrFail(sid, "Hero")
        outbound.drainAll()
        return Env(sid, players, mobs, outbound, router, questSystem)
    }

    @Test
    fun `accept_quest dialogue action adds the quest to active quests`() =
        runTest {
            val env = setup()

            env.router.handle(env.sid, Command.Talk("giver"))
            env.router.handle(env.sid, Command.DialogueChoice(1))
            env.outbound.drainAll()

            val ps = env.players.get(env.sid)!!
            assertTrue(
                ps.activeQuests.containsKey(questId),
                "Expected quest in activeQuests after dialogue accept. got=${ps.activeQuests}",
            )
        }

    @Test
    fun `turn_in_quest dialogue action moves a ready quest to completedQuestIds`() =
        runTest {
            val env = setup()
            // Pre-accept via QuestSystem so the quest is already in the active list
            // (npc_turn_in + zero-count objective leaves it ready to hand in).
            env.questSystem.acceptQuest(env.sid, questId)
            env.outbound.drainAll()

            env.router.handle(env.sid, Command.Talk("giver"))
            env.router.handle(env.sid, Command.DialogueChoice(2))
            env.outbound.drainAll()

            val ps = env.players.get(env.sid)!!
            assertFalse(
                ps.activeQuests.containsKey(questId),
                "Quest should be removed from activeQuests after turn-in. got=${ps.activeQuests}",
            )
            assertTrue(
                ps.completedQuestIds.contains(questId),
                "Quest should be in completedQuestIds after turn-in. got=${ps.completedQuestIds}",
            )
        }

    @Test
    fun `accept_quest with unknown quest id surfaces an error and accepts nothing`() =
        runTest {
            val env = setup()

            env.router.handle(env.sid, Command.Talk("giver"))
            env.router.handle(env.sid, Command.DialogueChoice(3))
            val events = env.outbound.drainAll()

            val ps = env.players.get(env.sid)!!
            assertTrue(ps.activeQuests.isEmpty(), "No quest should be accepted: ${ps.activeQuests}")
            val errors = events.filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(
                errors.any { it.contains("Unknown quest") },
                "Expected an 'Unknown quest' error. got=$errors",
            )
        }

    @Test
    fun `accept_quest auto-qualifies a bare quest id against the player's zone`() =
        runTest {
            val env = setup()

            env.router.handle(env.sid, Command.Talk("giver"))
            // Choice #4 carries action "accept_quest:fetch" — the engine should
            // qualify that to "test_zone:fetch" (the registered id) using the
            // player's current zone, the same convention `turnInMob` uses in
            // world YAML.
            env.router.handle(env.sid, Command.DialogueChoice(4))
            env.outbound.drainAll()

            val ps = env.players.get(env.sid)!!
            assertTrue(
                ps.activeQuests.containsKey(questId),
                "Bare-id accept_quest should resolve to '$questId'. got=${ps.activeQuests}",
            )
        }
}
