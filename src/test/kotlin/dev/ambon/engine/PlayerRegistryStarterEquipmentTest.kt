package dev.ambon.engine

import dev.ambon.domain.PlayerClassDef
import dev.ambon.domain.StarterEquipmentEntry
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerRegistryStarterEquipmentTest {
    private val startRoom = RoomId("test:start")
    private val swordId = ItemId("test:sword")
    private val helmId = ItemId("test:helm")
    private val potionId = ItemId("test:potion")
    private val missingId = ItemId("test:does_not_exist")

    private fun seededItems(): ItemRegistry =
        ItemRegistry().also { reg ->
            reg.loadSpawns(
                listOf(
                    ItemSpawn(
                        instance = ItemInstance(
                            id = swordId,
                            item = Item(
                                keyword = "sword",
                                displayName = "a sword",
                                slot = ItemSlot.WEAPON,
                                damage = 4,
                            ),
                        ),
                    ),
                    ItemSpawn(
                        instance = ItemInstance(
                            id = helmId,
                            item = Item(
                                keyword = "helm",
                                displayName = "a helm",
                                slot = ItemSlot.HEAD,
                                armor = 2,
                            ),
                        ),
                    ),
                    ItemSpawn(
                        instance = ItemInstance(
                            id = potionId,
                            item = Item(
                                keyword = "potion",
                                displayName = "a potion",
                                consumable = true,
                            ),
                        ),
                    ),
                ),
            )
        }

    private fun classRegistryWith(entries: List<StarterEquipmentEntry>): PlayerClassRegistry =
        PlayerClassRegistry().also { reg ->
            reg.register(
                PlayerClassDef(
                    id = "WARRIOR",
                    displayName = "Warrior",
                    hpScalingRate = 1.0,
                    manaScalingRate = 1.0,
                    starterEquipment = entries,
                ),
            )
        }

    @Test
    fun `new character receives starter equipment in correct slots and inventory`() =
        runTest {
            val items = seededItems()
            val repo = InMemoryPlayerRepository()
            val classRegistry = classRegistryWith(
                listOf(
                    StarterEquipmentEntry(itemId = swordId.value, equip = true),
                    StarterEquipmentEntry(itemId = helmId.value, equip = true),
                    StarterEquipmentEntry(itemId = potionId.value, equip = false),
                ),
            )
            val registry = buildTestPlayerRegistry(
                startRoom = startRoom,
                repo = repo,
                items = items,
                classRegistry = classRegistry,
            )

            val result = registry.create(
                sessionId = SessionId(1),
                nameRaw = "Hero",
                passwordRaw = "password",
                playerClass = "WARRIOR",
            )
            assertEquals(CreateResult.Ok, result)

            val equipped = items.equipment(SessionId(1))
            assertEquals(2, equipped.size, "weapon + head should be equipped")
            assertEquals("a sword", equipped[ItemSlot.WEAPON]?.item?.displayName)
            assertEquals("a helm", equipped[ItemSlot.HEAD]?.item?.displayName)

            val inv = items.inventory(SessionId(1))
            assertEquals(1, inv.size, "potion should land in inventory")
            assertEquals("a potion", inv.first().item.displayName)
        }

    @Test
    fun `equip false routes slotted items into inventory`() =
        runTest {
            val items = seededItems()
            val repo = InMemoryPlayerRepository()
            val classRegistry = classRegistryWith(
                listOf(
                    StarterEquipmentEntry(itemId = swordId.value, equip = false),
                ),
            )
            val registry = buildTestPlayerRegistry(
                startRoom = startRoom,
                repo = repo,
                items = items,
                classRegistry = classRegistry,
            )

            registry.create(SessionId(1), "Hero", "password", playerClass = "WARRIOR")

            assertTrue(items.equipment(SessionId(1)).isEmpty())
            val inv = items.inventory(SessionId(1))
            assertEquals(1, inv.size)
            assertEquals("a sword", inv.first().item.displayName)
        }

    @Test
    fun `unknown item ids are skipped silently`() =
        runTest {
            val items = seededItems()
            val repo = InMemoryPlayerRepository()
            val classRegistry = classRegistryWith(
                listOf(
                    StarterEquipmentEntry(itemId = missingId.value, equip = true),
                    StarterEquipmentEntry(itemId = swordId.value, equip = true),
                ),
            )
            val registry = buildTestPlayerRegistry(
                startRoom = startRoom,
                repo = repo,
                items = items,
                classRegistry = classRegistry,
            )

            val result = registry.create(SessionId(1), "Hero", "password", playerClass = "WARRIOR")
            assertEquals(CreateResult.Ok, result)

            val equipped = items.equipment(SessionId(1))
            assertEquals(1, equipped.size)
            assertNotNull(equipped[ItemSlot.WEAPON])
        }

    @Test
    fun `duplicate slot keeps first entry, drops second into inventory`() =
        runTest {
            val items = seededItems()
            val repo = InMemoryPlayerRepository()
            val secondHelm = ItemId("test:helm2")
            items.updateTemplates(
                listOf(
                    ItemSpawn(
                        instance = ItemInstance(
                            id = secondHelm,
                            item = Item(
                                keyword = "helm2",
                                displayName = "another helm",
                                slot = ItemSlot.HEAD,
                                armor = 1,
                            ),
                        ),
                    ),
                ),
            )
            val classRegistry = classRegistryWith(
                listOf(
                    StarterEquipmentEntry(itemId = helmId.value, equip = true),
                    StarterEquipmentEntry(itemId = secondHelm.value, equip = true),
                ),
            )
            val registry = buildTestPlayerRegistry(
                startRoom = startRoom,
                repo = repo,
                items = items,
                classRegistry = classRegistry,
            )

            registry.create(SessionId(1), "Hero", "password", playerClass = "WARRIOR")

            val equipped = items.equipment(SessionId(1))
            assertEquals("a helm", equipped[ItemSlot.HEAD]?.item?.displayName)
            val inv = items.inventory(SessionId(1))
            assertEquals(1, inv.size)
            assertEquals("another helm", inv.first().item.displayName)
        }

    @Test
    fun `no class definition results in no starter equipment`() =
        runTest {
            val items = seededItems()
            val repo = InMemoryPlayerRepository()
            val registry = buildTestPlayerRegistry(
                startRoom = startRoom,
                repo = repo,
                items = items,
            )

            registry.create(SessionId(1), "Hero", "password", playerClass = "WARRIOR")

            assertTrue(items.equipment(SessionId(1)).isEmpty())
            assertTrue(items.inventory(SessionId(1)).isEmpty())
            assertNull(repo.findByName("Hero")?.equippedItems?.takeIf { it.isNotEmpty() })
        }
}
