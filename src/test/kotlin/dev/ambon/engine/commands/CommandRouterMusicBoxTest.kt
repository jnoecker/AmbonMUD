package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemType
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.MusicBoxSystem
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
class CommandRouterMusicBoxTest {
    private class TestEnv(
        val outbound: LocalOutboundBus,
        val router: CommandRouter,
        val items: ItemRegistry,
        val sid: SessionId,
    )

    private suspend fun setup(): TestEnv {
        val world = WorldLoader.loadFromResource("world/ok_musicbox.yaml")
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val combat = CombatSystem(players, mobs, items, outbound)
        val clock = MutableClock(0)
        val router = buildTestRouter(
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = combat,
            outbound = outbound,
            clock = clock,
            musicBoxSystem = MusicBoxSystem(clock = clock, enabled = true),
        )
        val sid = SessionId(1L)
        require(players.login(sid, "Hummer", "password") == LoginResult.Ok)
        outbound.drainAll()
        return TestEnv(outbound, router, items, sid)
    }

    private fun lyricSheets(
        items: ItemRegistry,
        sid: SessionId,
    ) = items.inventory(sid).filter { it.item.itemType == ItemType.KEEPSAKE }

    @Test
    fun `playing the music box tucks a lyric sheet into the inventory`() =
        runTest {
            val env = setup()

            env.router.handle(env.sid, Command.MusicBoxPlay)

            val sheets = lyricSheets(env.items, env.sid)
            assertEquals(1, sheets.size, "expected exactly one lyric sheet")
            val sheet = sheets.first().item
            assertTrue(sheet.displayName.contains("Scuttlefish's Lullaby"), "wrong sheet: ${sheet.displayName}")
            assertTrue(sheet.description.contains("scuttlefish sings soft"), "sheet should carry the lyrics")
            assertEquals(0, sheet.basePrice, "a keepsake should be valueless")
        }

    @Test
    fun `replaying the same song does not add a second sheet`() =
        runTest {
            val env = setup()

            env.router.handle(env.sid, Command.MusicBoxPlay)
            env.router.handle(env.sid, Command.MusicBoxPlay)

            assertEquals(1, lyricSheets(env.items, env.sid).size, "replays must not duplicate the sheet")
        }

    @Test
    fun `a lyric sheet is a bound keepsake that cannot be dropped`() =
        runTest {
            val env = setup()
            env.router.handle(env.sid, Command.MusicBoxPlay)
            env.outbound.drainAll()

            env.router.handle(env.sid, Command.Drop("sheet"))

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("keepsake") },
                "expected a keepsake drop rejection, got=$outs",
            )
            assertEquals(1, lyricSheets(env.items, env.sid).size, "the sheet should still be in the satchel")
        }
}
