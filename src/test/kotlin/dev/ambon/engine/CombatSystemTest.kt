package dev.ambon.engine

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class CombatSystemTest {
    @Test
    fun `combat tick damages both sides`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10)
            mobs.upsert(mob)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val clock = MutableClock(0L)
            val combat =
                CombatSystem(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                )

            val sid = SessionId(1L)
            login(players, sid, "Player1")

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            assertTrue(player!!.hp < player.maxHp, "Expected player to take damage")

            val updatedMob = mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertTrue(updatedMob!!.hp < updatedMob.maxHp, "Expected mob to take damage")
        }

    @Test
    fun `mob death drops items and removes mob`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:owl"), "an owl", roomId, hp = 1, maxHp = 1)
            mobs.upsert(mob)
            items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:feather"), Item(keyword = "feather", displayName = "a black feather")),
            )

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val clock = MutableClock(0L)
            val combat =
                CombatSystem(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(2),
                    tickMillis = 1_000L,
                )

            val sid = SessionId(2L)
            login(players, sid, "Player2")

            val err = combat.startCombat(sid, "owl")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            assertNull(mobs.get(mob.id), "Expected mob to be removed after death")
            assertTrue(items.itemsInMob(mob.id).isEmpty(), "Expected mob inventory to be cleared")
            assertEquals(1, items.itemsInRoom(roomId).size, "Expected dropped item in room")
        }

    private suspend fun login(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        val res = players.login(sessionId, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
    }
}
