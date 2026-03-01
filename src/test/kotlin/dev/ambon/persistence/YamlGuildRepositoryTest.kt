package dev.ambon.persistence

import dev.ambon.domain.guild.GuildRank
import dev.ambon.domain.guild.GuildRecord
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class YamlGuildRepositoryTest {
    @TempDir
    lateinit var tmp: Path

    private val leaderId = PlayerId(1L)

    private fun makeRecord(
        id: String = "shadowblade",
        name: String = "Shadowblade",
        tag: String = "SB",
    ) = GuildRecord(
        id = id,
        name = name,
        tag = tag,
        leaderId = leaderId,
        createdAtEpochMs = 1000L,
    )

    @Test
    fun `create then findById round-trips guild`() =
        runTest {
            val repo = YamlGuildRepository(tmp)
            repo.create(makeRecord())

            val found = repo.findById("shadowblade")
            assertNotNull(found)
            assertEquals("Shadowblade", found!!.name)
            assertEquals("SB", found.tag)
            assertEquals(leaderId, found.leaderId)
            assertNull(found.motd)
        }

    @Test
    fun `save persists member and motd changes`() =
        runTest {
            val repo = YamlGuildRepository(tmp)
            val member = PlayerId(2L)
            val record = repo.create(makeRecord())
            val updated =
                record.copy(
                    members = mapOf(leaderId to GuildRank.LEADER, member to GuildRank.MEMBER),
                    motd = "Hello guild!",
                )

            repo.save(updated)

            val loaded = repo.findById("shadowblade")!!
            assertEquals(2, loaded.members.size)
            assertEquals(GuildRank.LEADER, loaded.members[leaderId])
            assertEquals(GuildRank.MEMBER, loaded.members[member])
            assertEquals("Hello guild!", loaded.motd)
        }

    @Test
    fun `delete removes guild file`() =
        runTest {
            val repo = YamlGuildRepository(tmp)
            repo.create(makeRecord())
            assertNotNull(repo.findById("shadowblade"))

            repo.delete("shadowblade")
            assertNull(repo.findById("shadowblade"))
        }

    @Test
    fun `findByName is case-insensitive`() =
        runTest {
            val repo = YamlGuildRepository(tmp)
            repo.create(makeRecord())

            assertNotNull(repo.findByName("Shadowblade"))
            assertNotNull(repo.findByName("shadowblade"))
            assertNotNull(repo.findByName("SHADOWBLADE"))
            assertNull(repo.findByName("nonexistent"))
        }

    @Test
    fun `findAll returns all guilds`() =
        runTest {
            val repo = YamlGuildRepository(tmp)
            repo.create(makeRecord("guild1", "GuildOne", "G1"))
            repo.create(makeRecord("guild2", "GuildTwo", "G2"))

            val all = repo.findAll()
            assertEquals(2, all.size)
            assertTrue(all.any { it.id == "guild1" })
            assertTrue(all.any { it.id == "guild2" })
        }

    @Test
    fun `findById returns null for unknown id`() =
        runTest {
            val repo = YamlGuildRepository(tmp)
            assertNull(repo.findById("unknown"))
        }

    @Test
    fun `findAll returns empty list when no guilds exist`() =
        runTest {
            val repo = YamlGuildRepository(tmp)
            assertEquals(emptyList<GuildRecord>(), repo.findAll())
        }

    @Test
    fun `create returns the record unchanged`() =
        runTest {
            val repo = YamlGuildRepository(tmp)
            val record = makeRecord()
            val returned = repo.create(record)
            assertEquals(record, returned)
        }
}
