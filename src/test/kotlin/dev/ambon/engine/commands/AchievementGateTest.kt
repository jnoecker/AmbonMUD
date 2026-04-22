package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementGateTest {
    private val world = dev.ambon.test.TestWorlds.okAchievementGate
    private val courtyard: RoomId = world.startRoom
    private val cottage = RoomId("ok_achievement_gate:cottage")

    @Test
    fun `gated exit blocks movement without achievement`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.Move(Direction.NORTH))
            val outs = h.outbound.drainAll()
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.text.contains("prove yourself", ignoreCase = true)
                },
                "Expected locked-message feedback, got: $outs",
            )
            assertEquals(courtyard, h.players.get(h.sid)?.roomId)
        }

    @Test
    fun `gated exit opens after achievement is unlocked`() =
        runTest {
            val h = harness()
            // Grant the gating achievement to this character.
            val player = h.players.get(h.sid)!!
            player.unlockedAchievementIds = player.unlockedAchievementIds + "academy_boss_slain"

            h.router.handle(h.sid, Command.Move(Direction.NORTH))
            h.outbound.drainAll()
            assertEquals(cottage, h.players.get(h.sid)?.roomId)
        }

    private data class Harness(
        val sid: SessionId,
        val players: dev.ambon.engine.PlayerRegistry,
        val router: CommandRouter,
        val outbound: LocalOutboundBus,
    )

    private suspend fun harness(): Harness {
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val outbound = LocalOutboundBus()
        val players = dev.ambon.test.buildTestPlayerRegistry(courtyard, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val worldState = WorldStateRegistry(world)
        val router =
            buildTestRouter(
                world = world,
                players = players,
                mobs = mobs,
                items = items,
                combat = CombatSystem(players, mobs, items, outbound),
                outbound = outbound,
                worldState = worldState,
            )
        val sid = SessionId(1)
        val res = players.login(sid, "Tester", "password")
        require(res == LoginResult.Ok)
        outbound.drainAll()
        return Harness(sid, players, router, outbound)
    }
}
