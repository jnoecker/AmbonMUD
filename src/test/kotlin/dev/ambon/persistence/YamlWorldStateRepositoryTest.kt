package dev.ambon.persistence

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class YamlWorldStateRepositoryTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `load returns null when no file exists`() =
        runTest {
            val repo = YamlWorldStateRepository(tmp)
            assertNull(repo.load())
        }

    @Test
    fun `save then load round-trips snapshot`() =
        runTest {
            val repo = YamlWorldStateRepository(tmp)
            val snap =
                WorldStateSnapshot(
                    doorStates = mapOf("zone:room/n" to "OPEN", "zone:room/s" to "LOCKED"),
                    containerStates = mapOf("zone:room/chest" to "CLOSED"),
                    leverStates = mapOf("zone:room/lever" to "DOWN"),
                    containerItems = mapOf("zone:room/chest" to listOf("zone:gold_coin", "zone:silver_coin")),
                )

            repo.save(snap)
            val loaded = repo.load()

            assertNotNull(loaded)
            assertEquals(snap.doorStates, loaded!!.doorStates)
            assertEquals(snap.containerStates, loaded.containerStates)
            assertEquals(snap.leverStates, loaded.leverStates)
            assertEquals(snap.containerItems, loaded.containerItems)
        }

    @Test
    fun `save overwrites previous snapshot`() =
        runTest {
            val repo = YamlWorldStateRepository(tmp)
            repo.save(WorldStateSnapshot(doorStates = mapOf("a:b/n" to "OPEN")))
            repo.save(WorldStateSnapshot(doorStates = mapOf("a:b/n" to "CLOSED")))

            val loaded = repo.load()
            assertNotNull(loaded)
            assertEquals("CLOSED", loaded!!.doorStates["a:b/n"])
        }

    @Test
    fun `empty snapshot round-trips cleanly`() =
        runTest {
            val repo = YamlWorldStateRepository(tmp)
            repo.save(WorldStateSnapshot())
            val loaded = repo.load()
            assertNotNull(loaded)
            assertEquals(emptyMap<String, String>(), loaded!!.doorStates)
            assertEquals(emptyMap<String, String>(), loaded.containerStates)
            assertEquals(emptyMap<String, String>(), loaded.leverStates)
            assertEquals(emptyMap<String, List<String>>(), loaded.containerItems)
        }
}
