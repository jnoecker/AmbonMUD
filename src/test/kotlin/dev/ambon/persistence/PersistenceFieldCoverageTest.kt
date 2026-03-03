package dev.ambon.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ambon.domain.Progress
import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mail.MailMessage
import dev.ambon.domain.quest.QuestState
import dev.ambon.engine.toPlayerRecord
import dev.ambon.engine.toPlayerState
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * Safety-net tests that verify every [PlayerRecord] field is correctly wired through
 * all persistence layers. When a new field is added to [PlayerRecord] but not propagated
 * to the DB table or serialization, one of these tests will fail at CI time.
 *
 * All fields in the test record are set to **non-default** values so that a mapping
 * omission (which would silently fall back to the default) is caught as a mismatch.
 */
@Tag("integration")
class PersistenceFieldCoverageTest {
    companion object {
        private lateinit var hikari: HikariDataSource
        private lateinit var database: Database

        /**
         * A [PlayerRecord] with every field set to a non-default value.
         * If a new field is added to [PlayerRecord] with a default, the compiler won't
         * force it to appear here — but the round-trip assertions will fail because the
         * loaded record will carry the default instead of the non-default sentinel.
         */
        val FULLY_POPULATED: PlayerRecord =
            PlayerRecord(
                id = PlayerId(42L),
                name = "TestHero",
                roomId = RoomId("zone:room"),
                strength = 15,
                dexterity = 14,
                constitution = 13,
                intelligence = 18,
                wisdom = 16,
                charisma = 12,
                race = "ELF",
                playerClass = "MAGE",
                level = 7,
                xpTotal = 1234L,
                createdAtEpochMs = 1000L,
                lastSeenEpochMs = 2000L,
                passwordHash = "bcrypt_hash",
                ansiEnabled = true,
                isStaff = true,
                hp = 55,
                mana = 80,
                maxMana = 100,
                gold = 500L,
                activeQuests = mapOf(
                    "quest1" to QuestState(
                        questId = "quest1",
                        acceptedAtEpochMs = 900L,
                        objectives = listOf(Progress(current = 2, required = 5)),
                    ),
                ),
                completedQuestIds = setOf("quest0"),
                unlockedAchievementIds = setOf("ach1"),
                achievementProgress = mapOf(
                    "ach2" to AchievementState(
                        achievementId = "ach2",
                        progress = listOf(Progress(current = 1, required = 3)),
                    ),
                ),
                activeTitle = "Dragon Slayer",
                inbox = listOf(
                    MailMessage(
                        id = "mail1",
                        fromName = "Admin",
                        body = "Welcome!",
                        sentAtEpochMs = 1500L,
                        read = true,
                    ),
                ),
                guildId = "knights",
                recallRoomId = RoomId("town:square"),
                inventoryItems = listOf(
                    ItemInstance(ItemId("test:potion"), Item(keyword = "potion", displayName = "a healing potion")),
                ),
                equippedItems = mapOf(
                    "HAND" to ItemInstance(
                        ItemId("test:sword"),
                        Item(keyword = "sword", displayName = "a short sword", slot = ItemSlot.HAND),
                    ),
                ),
            )

        @BeforeAll
        @JvmStatic
        fun setup() {
            val config =
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:fieldcov;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                    username = "sa"
                    password = ""
                }
            hikari = HikariDataSource(config)
            database = Database.connect(hikari)
            transaction(database) {
                SchemaUtils.create(PlayersTable)
                exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_players_name_lower ON players (name_lower)")
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            hikari.close()
        }
    }

    @BeforeEach
    fun reset() {
        transaction(database) { PlayersTable.deleteAll() }
    }

    // -----------------------------------------------------------------------
    // 1. Reflective property-count guard
    // -----------------------------------------------------------------------

    @Test
    fun `PlayersTable has one column per PlayerRecord field plus nameLower`() {
        val recordProps = PlayerRecord::class.memberProperties.map { it.name }.toSet()
        val tableColumns = PlayersTable.columns.map { it.name }.toSet()

        // Known mapping differences:
        //   PlayersTable.nameLower  → derived, no PlayerRecord field
        //   PlayersTable.mailInbox  → maps to PlayerRecord.inbox
        val expectedExtra = setOf("name_lower")
        val nameMapping = mapOf("mail_inbox" to "inbox")

        val normalised =
            tableColumns
                .minus(expectedExtra)
                .map { col -> nameMapping[col] ?: col.snakeToCamel() }
                .toSet()

        val missing = recordProps - normalised
        val extra = normalised - recordProps

        assertEquals(emptySet<String>(), missing, "PlayerRecord fields missing from PlayersTable")
        assertEquals(emptySet<String>(), extra, "PlayersTable columns with no PlayerRecord field")
    }

    // -----------------------------------------------------------------------
    // 2. YAML round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `YAML round-trip preserves all fields`() {
        val mapper =
            ObjectMapper(YAMLFactory())
                .registerModule(KotlinModule.Builder().build())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val yaml = mapper.writeValueAsString(FULLY_POPULATED)
        val restored = mapper.readValue<PlayerRecord>(yaml)

        assertEquals(FULLY_POPULATED, restored)
    }

    // -----------------------------------------------------------------------
    // 3. PostgreSQL save-then-load round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `Postgres save and findById round-trip preserves all fields`() =
        runTest {
            val repo = PostgresPlayerRepository(database)
            // Create a seed record so the autoincrement id column is populated,
            // then save the fully-populated record over it.
            val seed =
                repo.create(
                    PlayerCreationRequest(
                        name = FULLY_POPULATED.name,
                        startRoomId = FULLY_POPULATED.roomId,
                        nowEpochMs = FULLY_POPULATED.createdAtEpochMs,
                        passwordHash = FULLY_POPULATED.passwordHash,
                        ansiEnabled = FULLY_POPULATED.ansiEnabled,
                        race = FULLY_POPULATED.race,
                        playerClass = FULLY_POPULATED.playerClass,
                        strength = FULLY_POPULATED.strength,
                        dexterity = FULLY_POPULATED.dexterity,
                        constitution = FULLY_POPULATED.constitution,
                        intelligence = FULLY_POPULATED.intelligence,
                        wisdom = FULLY_POPULATED.wisdom,
                        charisma = FULLY_POPULATED.charisma,
                    ),
                )
            val expected = FULLY_POPULATED.copy(id = seed.id)
            repo.save(expected)

            val loaded = repo.findById(seed.id)
            assertEquals(expected, loaded)
        }

    // -----------------------------------------------------------------------
    // 4. Redis JSON round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `Redis JSON round-trip preserves all fields`() {
        // Match the production redisObjectMapper config (ignores computed properties
        // like Progress.isComplete that Jackson serializes but cannot deserialize).
        val mapper =
            ObjectMapper()
                .registerModule(KotlinModule.Builder().build())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val json = mapper.writeValueAsString(FULLY_POPULATED)
        val restored = mapper.readValue<PlayerRecord>(json)

        assertEquals(FULLY_POPULATED, restored)
    }

    // -----------------------------------------------------------------------
    // 5. PlayerRecord ↔ PlayerState round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `PlayerState toPlayerRecord round-trip preserves all persisted fields`() {
        val sid = SessionId(99)
        val ps = FULLY_POPULATED.toPlayerState(sid)
        val roundTripped = ps.toPlayerRecord(lastSeenEpochMs = FULLY_POPULATED.lastSeenEpochMs)

        // Items are managed by ItemRegistry, not PlayerState, so they don't survive
        // the PlayerState round-trip. All other fields must match.
        val expected = FULLY_POPULATED.copy(inventoryItems = emptyList(), equippedItems = emptyMap())
        assertEquals(expected, roundTripped)
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** Converts a `snake_case` SQL column name to `camelCase` Kotlin property name. */
    private fun String.snakeToCamel(): String =
        split('_')
            .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }
            .joinToString("")
}
