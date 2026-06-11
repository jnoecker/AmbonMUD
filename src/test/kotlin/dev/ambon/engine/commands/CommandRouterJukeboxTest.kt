package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.JukeboxSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterJukeboxTest {
    private class TestEnv(
        val outbound: LocalOutboundBus,
        val router: CommandRouter,
        val players: dev.ambon.engine.PlayerRegistry,
        val clock: MutableClock,
        val sid: SessionId,
        val player: dev.ambon.engine.PlayerState,
    )

    private suspend fun setup(): TestEnv {
        val world = WorldLoader.loadFromResource("world/ok_jukebox.yaml")
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val combat = CombatSystem(players, mobs, items, outbound)
        val clock = MutableClock(0)
        val jukeboxSystem = JukeboxSystem(clock = clock, enabled = true)
        val router = buildTestRouter(
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = combat,
            outbound = outbound,
            clock = clock,
            jukeboxSystem = jukeboxSystem,
        )

        val sid = SessionId(1L)
        val res = players.login(sid, "Lyric", "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
        outbound.drainAll()
        val player = players.get(sid)!!
        return TestEnv(outbound, router, players, clock, sid, player)
    }

    @Test
    fun `playing a song deducts gold and announces the track`() =
        runTest {
            val env = setup()
            env.player.gold = 50L

            env.router.handle(env.sid, Command.JukeboxPlay(1))

            assertEquals(45L, env.player.gold)
            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("Tavern Reel") },
                "Expected the song title in the feed. got=$outs",
            )
        }

    @Test
    fun `playing a song broadcasts its description to everyone in the room`() =
        runTest {
            val env = setup()
            env.player.gold = 50L
            val watcherSid = SessionId(2L)
            val res = env.players.login(watcherSid, "Watcher", "password")
            require(res == LoginResult.Ok) { "Login failed: $res" }
            env.outbound.drainAll()

            env.router.handle(env.sid, Command.JukeboxPlay(1))

            val outs = env.outbound.drainAll()
            val description = "A foot-stomping reel about a barkeep's lost cat."
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == watcherSid && it.text.contains("feeds the jukebox") },
                "Expected the watcher to see the play announcement. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == watcherSid && it.text == description },
                "Expected the watcher to see the song description. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == env.sid && it.text == description },
                "Expected the buyer to see the song description. got=$outs",
            )
        }

    @Test
    fun `a playing jukebox is busy until the track ends`() =
        runTest {
            val env = setup()
            env.player.gold = 100L

            env.router.handle(env.sid, Command.JukeboxPlay(1))
            env.outbound.drainAll()

            env.clock.advance(10_000)
            env.router.handle(env.sid, Command.JukeboxPlay(2))

            // Second play is rejected; the player was not charged for it.
            assertEquals(95L, env.player.gold)
            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("busy") },
                "Expected a busy error. got=$outs",
            )
        }

    @Test
    fun `insufficient gold is rejected`() =
        runTest {
            val env = setup()
            env.player.gold = 2L

            env.router.handle(env.sid, Command.JukeboxPlay(1))

            assertEquals(2L, env.player.gold)
            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("gold") },
                "Expected an insufficient-gold error. got=$outs",
            )
        }

    @Test
    fun `list shows the playlist`() =
        runTest {
            val env = setup()

            env.router.handle(env.sid, Command.Jukebox)

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("Tavern Reel") },
                "Expected the playlist in the feed. got=$outs",
            )
        }

    @Test
    fun `jukebox is unavailable outside a jukebox room`() =
        runTest {
            val env = setup()
            env.players.moveTo(env.sid, RoomId("ok_jukebox:cellar"))
            env.outbound.drainAll()

            env.router.handle(env.sid, Command.Jukebox)

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("no jukebox", ignoreCase = true) },
                "Expected a no-jukebox error. got=$outs",
            )
        }
}
