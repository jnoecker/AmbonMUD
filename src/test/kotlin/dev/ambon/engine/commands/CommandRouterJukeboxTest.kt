package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
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

    private fun musicLines(
        events: List<OutboundEvent>,
        sid: SessionId,
    ): List<String> =
        events.filterIsInstance<OutboundEvent.SendInfo>()
            .filter { it.sessionId == sid && it.text.startsWith("[music]") }
            .map { it.text }

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
    fun `playing a song sends an inline music link to audio-links players only`() =
        runTest {
            val env = setup()
            env.player.gold = 50L
            val watcherSid = SessionId(2L)
            val res = env.players.login(watcherSid, "Watcher", "password")
            require(res == LoginResult.Ok) { "Login failed: $res" }
            env.players.setAudioLinksEnabled(watcherSid, true)
            env.outbound.drainAll()

            env.router.handle(env.sid, Command.JukeboxPlay(1))

            val outs = env.outbound.drainAll()
            assertEquals(listOf("[music] /audio/jukebox/tavern_reel.mp3"), musicLines(outs, watcherSid))
            assertTrue(
                musicLines(outs, env.sid).isEmpty(),
                "Expected no inline music link for the buyer without audio links. got=$outs",
            )
        }

    @Test
    fun `walking in mid-song shows the jukebox track instead of the room default`() =
        runTest {
            val env = setup()
            env.player.gold = 50L
            val watcherSid = SessionId(2L)
            val res = env.players.login(watcherSid, "Watcher", "password")
            require(res == LoginResult.Ok) { "Login failed: $res" }
            env.players.setAudioLinksEnabled(watcherSid, true)
            env.players.moveTo(watcherSid, RoomId("ok_jukebox:cellar"))
            env.router.handle(env.sid, Command.JukeboxPlay(1))
            env.outbound.drainAll()

            env.clock.advance(10_000)
            env.router.handle(watcherSid, Command.Move(Direction.SOUTH))

            // The jukebox track, not the tavern's default /audio/tavern/ambient_loop.mp3.
            assertEquals(
                listOf("[music] /audio/jukebox/tavern_reel.mp3"),
                musicLines(env.outbound.drainAll(), watcherSid),
            )

            // Second look while the same track plays — nothing is reprinted.
            env.router.handle(watcherSid, Command.Look)
            assertTrue(
                musicLines(env.outbound.drainAll(), watcherSid).isEmpty(),
                "Expected no reprint of the unchanged jukebox track",
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
    fun `another player can queue the next song while one plays`() =
        runTest {
            val env = setup()
            env.player.gold = 50L
            val quillSid = SessionId(2L)
            val res = env.players.login(quillSid, "Quill", "password")
            require(res == LoginResult.Ok) { "Login failed: $res" }
            val quill = env.players.get(quillSid)!!
            quill.gold = 20L
            env.router.handle(env.sid, Command.JukeboxPlay(1))
            env.outbound.drainAll()

            env.router.handle(quillSid, Command.JukeboxQueue(2))

            assertEquals(15L, quill.gold) // song 2 costs the default 5 gold
            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == quillSid && it.text.contains("will play next") },
                "Expected the queue confirmation. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == env.sid && it.text.contains("will play next") },
                "Expected the room to see the queue announcement. got=$outs",
            )
        }

    @Test
    fun `the current buyer cannot queue the next song`() =
        runTest {
            val env = setup()
            env.player.gold = 50L
            env.router.handle(env.sid, Command.JukeboxPlay(1))
            env.outbound.drainAll()

            env.router.handle(env.sid, Command.JukeboxQueue(2))

            assertEquals(45L, env.player.gold) // charged only for the original play
            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("someone else") },
                "Expected the own-song rejection. got=$outs",
            )
        }

    @Test
    fun `only one song can be queued at a time`() =
        runTest {
            val env = setup()
            env.player.gold = 50L
            val quillSid = SessionId(2L)
            val resQuill = env.players.login(quillSid, "Quill", "password")
            require(resQuill == LoginResult.Ok) { "Login failed: $resQuill" }
            val nockSid = SessionId(3L)
            val resNock = env.players.login(nockSid, "Nock", "password")
            require(resNock == LoginResult.Ok) { "Login failed: $resNock" }
            val quill = env.players.get(quillSid)!!
            val nock = env.players.get(nockSid)!!
            quill.gold = 20L
            nock.gold = 20L
            env.router.handle(env.sid, Command.JukeboxPlay(1))
            env.router.handle(quillSid, Command.JukeboxQueue(2))
            env.outbound.drainAll()

            env.router.handle(nockSid, Command.JukeboxQueue(1))

            assertEquals(20L, nock.gold)
            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == nockSid && it.text.contains("already queued") },
                "Expected the queue-full rejection. got=$outs",
            )
        }

    @Test
    fun `queueing with nothing playing starts the song immediately`() =
        runTest {
            val env = setup()
            env.player.gold = 50L

            env.router.handle(env.sid, Command.JukeboxQueue(1))

            assertEquals(45L, env.player.gold)
            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("begins to play \"Tavern Reel\"") },
                "Expected the song to start immediately. got=$outs",
            )
        }

    @Test
    fun `busy play rejection points at the queue command`() =
        runTest {
            val env = setup()
            env.player.gold = 50L
            val quillSid = SessionId(2L)
            val res = env.players.login(quillSid, "Quill", "password")
            require(res == LoginResult.Ok) { "Login failed: $res" }
            val quill = env.players.get(quillSid)!!
            quill.gold = 20L
            env.router.handle(env.sid, Command.JukeboxPlay(1))
            env.outbound.drainAll()

            env.router.handle(quillSid, Command.JukeboxPlay(2))

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == quillSid && it.text.contains("jukebox queue 2") },
                "Expected the busy error to suggest queueing. got=$outs",
            )
        }

    @Test
    fun `list shows the queued track`() =
        runTest {
            val env = setup()
            env.player.gold = 50L
            val quillSid = SessionId(2L)
            val res = env.players.login(quillSid, "Quill", "password")
            require(res == LoginResult.Ok) { "Login failed: $res" }
            val quill = env.players.get(quillSid)!!
            quill.gold = 20L
            env.router.handle(env.sid, Command.JukeboxPlay(1))
            env.router.handle(quillSid, Command.JukeboxQueue(2))
            env.outbound.drainAll()

            env.router.handle(env.sid, Command.Jukebox)

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == env.sid &&
                        it.text.contains("Up next: \"Aineroia's Ascension\"") &&
                        it.text.contains("queued by Quill")
                },
                "Expected the up-next line in the list. got=$outs",
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
