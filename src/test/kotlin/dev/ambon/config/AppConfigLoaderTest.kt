package dev.ambon.config

import com.sksamuel.hoplite.PropertySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppConfigLoaderTest {
    private val testResourcePath = "/test-application.yaml"

    /** Minimal valid world config used when the test is exercising a non-world validation path. */
    private val validWorld = WorldConfig(startRoom = "test:room")

    @Test
    fun `system property overrides default config`() {
        val key = "config.override.ambonmud.server.telnetPort"
        val previous = System.getProperty(key)
        System.setProperty(key, "4444")

        try {
            val config = AppConfigLoader.load(resourcePath = testResourcePath)
            assertEquals(4444, config.server.telnetPort)
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    @Test
    fun `default application config uses yaml persistence with redis disabled`() {
        val config = AppConfigLoader.load()

        assertEquals(PersistenceBackend.YAML, config.persistence.backend)
        assertTrue(!config.redis.enabled)
        assertTrue(!config.engine.debug.enableSwarmClass)
        assertEquals("thornhaven_city:new_arrivals_hall", config.engine.classStartRooms["SWARM"])
    }

    @Test
    fun `validation rejects invalid values`() {
        val invalid = AppConfig(server = ServerConfig(telnetPort = 0), world = validWorld)
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects invalid progression values`() {
        val invalid = AppConfig(progression = ProgressionConfig(maxLevel = 0), world = validWorld)
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects tier with baseHp 0`() {
        val badTier = MobTierConfig(baseHp = 0)
        val invalid =
            AppConfig(
                engine = EngineConfig(mob = MobEngineConfig(tiers = MobTiersConfig(standard = badTier))),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects combat room feedback when feedback is disabled`() {
        val invalid =
            AppConfig(
                engine =
                    EngineConfig(
                        combat =
                            CombatEngineConfig(
                                feedback = CombatFeedbackConfig(enabled = false, roomBroadcastEnabled = true),
                            ),
                    ),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects inboundBudgetMs of zero`() {
        val invalid = AppConfig(server = ServerConfig(inboundBudgetMs = 0L), world = validWorld)
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects inboundBudgetMs equal to tickMillis`() {
        val invalid = AppConfig(
            server = ServerConfig(tickMillis = 100L, inboundBudgetMs = 100L),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects inboundBudgetMs greater than tickMillis`() {
        val invalid = AppConfig(
            server = ServerConfig(tickMillis = 100L, inboundBudgetMs = 101L),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation accepts valid inboundBudgetMs`() {
        val valid = AppConfig(
            server = ServerConfig(tickMillis = 100L, inboundBudgetMs = 30L),
            world = validWorld,
        )
        valid.validated() // should not throw
    }

    @Test
    fun `redis validation skipped when disabled`() {
        val config = AppConfig(redis = RedisConfig(enabled = false, uri = ""), world = validWorld)
        config.validated()
    }

    @Test
    fun `redis validation rejects blank uri when enabled`() {
        val invalid = AppConfig(redis = RedisConfig(enabled = true, uri = ""), world = validWorld)
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `redis validation rejects non-positive cacheTtlSeconds`() {
        val invalid = AppConfig(
            redis = RedisConfig(enabled = true, cacheTtlSeconds = 0L),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `redis bus validation rejects blank shared secret when enabled`() {
        val invalid =
            AppConfig(
                redis =
                    RedisConfig(
                        enabled = true,
                        bus = RedisBusConfig(enabled = true, sharedSecret = ""),
                    ),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `redis bus validation accepts non-blank shared secret`() {
        val config =
            AppConfig(
                redis =
                    RedisConfig(
                        enabled = true,
                        bus = RedisBusConfig(enabled = true, sharedSecret = "secret"),
                    ),
                world = validWorld,
            )
        config.validated()
    }

    @Test
    fun `validation accepts empty resources list for auto-discovery`() {
        AppConfig(world = WorldConfig(resources = emptyList(), startRoom = "z:r")).validated()
    }

    @Test
    fun `validation accepts explicit startRoom in zone-room format`() {
        AppConfig(world = WorldConfig(startRoom = "ambon_hub:hall_of_portals")).validated()
    }

    @Test
    fun `validation rejects startRoom without colon`() {
        val invalid = AppConfig(world = WorldConfig(startRoom = "hall_of_portals"))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects null startRoom`() {
        val invalid = AppConfig(world = WorldConfig(startRoom = null))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated warns on stat binding referencing undefined stat`() {
        val config =
            AppConfig(
                engine =
                    EngineConfig(
                        stats =
                            StatsEngineConfig(
                                bindings = StatBindingsConfig(meleeDamageStat = "UNKNOWN"),
                            ),
                    ),
                world = validWorld,
            )
        // Should warn but not throw — degraded config is acceptable in production
        config.validated()
    }

    @Test
    fun `validated rejects stat binding with zero divisor`() {
        val invalid =
            AppConfig(
                engine =
                    EngineConfig(
                        stats =
                            StatsEngineConfig(
                                bindings = StatBindingsConfig(meleeDamageDivisor = 0),
                            ),
                    ),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects maxDodgePercent out of range`() {
        val invalid =
            AppConfig(
                engine =
                    EngineConfig(
                        stats =
                            StatsEngineConfig(
                                bindings = StatBindingsConfig(maxDodgePercent = 101),
                            ),
                    ),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects baseHp less than 1`() {
        val invalid =
            AppConfig(
                progression = ProgressionConfig(rewards = LevelRewardsConfig(baseHp = 0)),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects negative baseMana`() {
        val invalid =
            AppConfig(
                progression = ProgressionConfig(rewards = LevelRewardsConfig(baseMana = -1)),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects negative startingGold`() {
        val invalid =
            AppConfig(
                engine = EngineConfig(characterCreation = CharacterCreationConfig(startingGold = -1L)),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated warns on negative class threatMultiplier`() {
        val config =
            AppConfig(
                engine =
                    EngineConfig(
                        classes =
                            ClassEngineConfig(
                                definitions =
                                    mapOf(
                                        "WARRIOR" to ClassDefinitionConfig(threatMultiplier = -0.1),
                                    ),
                            ),
                    ),
                world = validWorld,
            )
        // Should warn but not throw — degraded config is acceptable in production
        config.validated()
    }

    @Test
    fun `validated rejects world time hours out of order`() {
        val invalid = AppConfig(
            engine = EngineConfig(
                worldTime = WorldTimeConfig(dawnHour = 10, dayHour = 5, duskHour = 18, nightHour = 21),
            ),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects dusk before day hour`() {
        val invalid = AppConfig(
            engine = EngineConfig(
                worldTime = WorldTimeConfig(dawnHour = 5, dayHour = 18, duskHour = 8, nightHour = 21),
            ),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects night before dusk hour`() {
        val invalid = AppConfig(
            engine = EngineConfig(
                worldTime = WorldTimeConfig(dawnHour = 5, dayHour = 8, duskHour = 21, nightHour = 18),
            ),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects faction referencing undefined enemy`() {
        val invalid = AppConfig(
            engine = EngineConfig(
                factions = FactionConfig(
                    definitions = mapOf(
                        "guild_a" to FactionDefinition(name = "Guild A", enemies = listOf("nonexistent")),
                    ),
                ),
            ),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated accepts faction with valid enemy cross-reference`() {
        val config = AppConfig(
            engine = EngineConfig(
                factions = FactionConfig(
                    definitions = mapOf(
                        "guild_a" to FactionDefinition(name = "Guild A", enemies = listOf("guild_b")),
                        "guild_b" to FactionDefinition(name = "Guild B", enemies = listOf("guild_a")),
                    ),
                ),
            ),
            world = validWorld,
        )
        config.validated()
    }

    @Test
    fun `validated rejects duplicate equipment slot orders`() {
        val invalid = AppConfig(
            engine = EngineConfig(
                equipment = EquipmentConfig(
                    slots = linkedMapOf(
                        "head" to EquipmentSlotConfig(displayName = "Head", order = 0),
                        "body" to EquipmentSlotConfig(displayName = "Body", order = 0),
                    ),
                ),
            ),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects xp exponent below 1`() {
        val invalid = AppConfig(
            progression = ProgressionConfig(xp = XpCurveConfig(exponent = 0.5)),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects sharding without redis`() {
        val invalid = AppConfig(
            sharding = ShardingConfig(
                enabled = true,
                engineId = "e1",
                advertiseHost = "localhost",
            ),
            redis = RedisConfig(enabled = false),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects instancing without sharding`() {
        val invalid = AppConfig(
            sharding = ShardingConfig(
                enabled = false,
                instancing = InstanceConfig(enabled = true),
            ),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `env-var normalised path overrides persistence backend`() {
        // Regression for #320: AMBONMUD_PERSISTENCE_BACKEND was silently ignored because
        // Hoplite normalises env vars to ambonmud.persistence.backend but the root field
        // was named ambonMUD, causing a key-normalisation mismatch. Renaming the field to
        // ambonmud (all-lowercase) fixes the lookup.
        val source = PropertySource.map(mapOf("ambonmud.persistence.backend" to "POSTGRES"))
        val config = AppConfigLoader.load(
            resourcePath = testResourcePath,
            extraSources = listOf(source),
        )
        assertEquals(PersistenceBackend.POSTGRES, config.persistence.backend)
    }

    @Test
    fun `validated rejects sessionOutboundQueueCapacity exceeding upper bound`() {
        val invalid = AppConfig(server = ServerConfig(sessionOutboundQueueCapacity = 100_001), world = validWorld)
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated accepts sessionOutboundQueueCapacity at upper bound`() {
        val valid = AppConfig(server = ServerConfig(sessionOutboundQueueCapacity = 100_000), world = validWorld)
        valid.validated() // should not throw
    }
}
