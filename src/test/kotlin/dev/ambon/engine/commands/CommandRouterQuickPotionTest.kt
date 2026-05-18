package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemUseEffect
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
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
class CommandRouterQuickPotionTest {
    @Test
    fun `quickheal with no potion sends error`() =
        runTest {
            val env = setup()
            env.player.hp = 10
            env.player.maxHp = 100

            env.router.handle(env.sid, Command.QuickHeal)

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("healing potion") },
                "Expected 'no healing potion' error. got=$outs",
            )
        }

    @Test
    fun `quickheal at full HP sends error`() =
        runTest {
            val env = setup()
            env.player.maxHp = 100
            env.player.hp = 100
            env.items.addToInventory(env.sid, hpPotion("lesser", heal = 25))

            env.router.handle(env.sid, Command.QuickHeal)

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("HP is already full") },
                "Expected 'already full' error. got=$outs",
            )
            assertEquals(1, env.items.inventory(env.sid).size)
        }

    @Test
    fun `quickheal picks least powerful potion that fully restores`() =
        runTest {
            val env = setup()
            env.player.maxHp = 100
            env.player.hp = 70 // missing 30
            env.items.addToInventory(env.sid, hpPotion("minor", heal = 10)) // insufficient
            env.items.addToInventory(env.sid, hpPotion("lesser", heal = 50)) // first sufficient — should pick
            env.items.addToInventory(env.sid, hpPotion("greater", heal = 200)) // sufficient but wasteful

            env.router.handle(env.sid, Command.QuickHeal)

            val remaining = env.items.inventory(env.sid).map { it.item.keyword }.toSet()
            assertEquals(setOf("minor", "greater"), remaining, "Lesser potion should be consumed")
            assertEquals(100, env.player.hp)
        }

    @Test
    fun `quickheal falls back to most powerful when none fully restore`() =
        runTest {
            val env = setup()
            env.player.maxHp = 200
            env.player.hp = 10 // missing 190
            env.items.addToInventory(env.sid, hpPotion("minor", heal = 10))
            env.items.addToInventory(env.sid, hpPotion("lesser", heal = 50))
            env.items.addToInventory(env.sid, hpPotion("medium", heal = 100))

            env.router.handle(env.sid, Command.QuickHeal)

            val remaining = env.items.inventory(env.sid).map { it.item.keyword }.toSet()
            assertEquals(setOf("minor", "lesser"), remaining, "Medium (most powerful) should be consumed")
            assertEquals(110, env.player.hp)
        }

    @Test
    fun `quickmana ignores HP-only potions and picks mana potion`() =
        runTest {
            val env = setup()
            env.player.maxMana = 50
            env.player.mana = 10 // missing 40
            env.items.addToInventory(env.sid, hpPotion("hp_only", heal = 100))
            env.items.addToInventory(env.sid, manaPotion("blue", restore = 50))

            env.router.handle(env.sid, Command.QuickMana)

            val remaining = env.items.inventory(env.sid).map { it.item.keyword }.toSet()
            assertEquals(setOf("hp_only"), remaining, "Mana potion should be consumed, HP potion untouched")
            assertEquals(50, env.player.mana)
        }

    @Test
    fun `quickheal ignores non-consumables and non-heal consumables`() =
        runTest {
            val env = setup()
            env.player.maxHp = 100
            env.player.hp = 50
            // A non-consumable item with a heal effect should be skipped.
            env.items.addToInventory(
                env.sid,
                ItemInstance(
                    ItemId("ok_small:wand"),
                    Item(
                        keyword = "wand",
                        displayName = "a wand",
                        consumable = false,
                        onUse = ItemUseEffect(healHp = 100),
                    ),
                ),
            )
            // A consumable XP scroll (no heal) should be skipped.
            env.items.addToInventory(
                env.sid,
                ItemInstance(
                    ItemId("ok_small:scroll"),
                    Item(
                        keyword = "scroll",
                        displayName = "a scroll",
                        consumable = true,
                        onUse = ItemUseEffect(grantXp = 100L),
                    ),
                ),
            )

            env.router.handle(env.sid, Command.QuickHeal)

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("healing potion") },
                "Expected 'no healing potion' error. got=$outs",
            )
            // Wand and scroll should both still be in inventory.
            assertEquals(2, env.items.inventory(env.sid).size)
            assertEquals(50, env.player.hp)
        }

    private fun hpPotion(keyword: String, heal: Int): ItemInstance =
        ItemInstance(
            ItemId("ok_small:$keyword"),
            Item(
                keyword = keyword,
                displayName = "a $keyword potion",
                consumable = true,
                onUse = ItemUseEffect(healHp = heal),
            ),
        )

    private fun manaPotion(keyword: String, restore: Int): ItemInstance =
        ItemInstance(
            ItemId("ok_small:$keyword"),
            Item(
                keyword = keyword,
                displayName = "a $keyword mana potion",
                consumable = true,
                onUse = ItemUseEffect(healMana = restore),
            ),
        )

    private data class TestEnv(
        val items: ItemRegistry,
        val players: PlayerRegistry,
        val outbound: LocalOutboundBus,
        val router: CommandRouter,
        val sid: SessionId,
        val player: dev.ambon.engine.PlayerState,
    )

    private suspend fun setup(): TestEnv {
        val world = WorldLoader.loadFromResource("world/ok_small.yaml")
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val combat = CombatSystem(players, mobs, items, outbound)
        val router = buildTestRouter(
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = combat,
            outbound = outbound,
        )

        val sid = SessionId(1L)
        val res = players.login(sid, "Quaffer", "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
        outbound.drainAll()

        val player = players.get(sid)!!
        return TestEnv(items, players, outbound, router, sid, player)
    }
}
