package dev.ambon.config

import com.sksamuel.hoplite.PropertySource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

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
    fun `validation rejects world event recurrence with duration not less than period`() {
        val invalid = AppConfig(
            engine = EngineConfig(
                worldEvents = WorldEventsConfig(
                    definitions = mapOf(
                        "star" to WorldEventDefinition(
                            displayName = "Star",
                            recurrence = WorldEventRecurrence(periodMs = 1000L, durationMs = 1000L),
                        ),
                    ),
                ),
            ),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects world event with malformed date`() {
        val invalid = AppConfig(
            engine = EngineConfig(
                worldEvents = WorldEventsConfig(
                    definitions = mapOf("star" to WorldEventDefinition(startDate = "April 1")),
                ),
            ),
            world = validWorld,
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation accepts dated world event with recurrence`() {
        val valid = AppConfig(
            engine = EngineConfig(
                worldEvents = WorldEventsConfig(
                    definitions = mapOf(
                        "star" to WorldEventDefinition(
                            displayName = "Star",
                            startDate = "2026-04-01",
                            endDate = "2026-05-01",
                            recurrence = WorldEventRecurrence(periodMs = 3_600_000L, durationMs = 600_000L),
                        ),
                    ),
                ),
            ),
            world = validWorld,
        )
        valid.validated() // should not throw
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
    fun `validated rejects negative melee stat multiplier`() {
        val invalid =
            AppConfig(
                engine =
                    EngineConfig(
                        stats =
                            StatsEngineConfig(
                                bindings = StatBindingsConfig(meleeStatMultiplier = -0.1),
                            ),
                    ),
                world = validWorld,
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validated rejects melee level scaling rate below 1`() {
        val invalid =
            AppConfig(
                engine =
                    EngineConfig(
                        stats =
                            StatsEngineConfig(
                                bindings = StatBindingsConfig(meleeLevelScalingRate = 0.95),
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
    fun `validation rejects negative ability skill point cost`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppConfig(
                engine =
                    EngineConfig(
                        abilities =
                            AbilityEngineConfig(
                                definitions =
                                    mapOf(
                                        "free_spell" to AbilityDefinitionConfig(skillPointCost = -1),
                                    ),
                            ),
                    ),
                world = validWorld,
            )
        }
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
    fun `grpc validation rejects blank shared secret in ENGINE mode`() {
        val invalid = AppConfig(mode = DeploymentMode.ENGINE, grpc = GrpcConfig(sharedSecret = ""))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `grpc validation rejects blank shared secret in GATEWAY mode`() {
        val invalid = AppConfig(
            mode = DeploymentMode.GATEWAY,
            grpc = GrpcConfig(sharedSecret = ""),
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `grpc validation accepts non-blank shared secret in ENGINE mode`() {
        val config = AppConfig(
            mode = DeploymentMode.ENGINE,
            grpc = GrpcConfig(sharedSecret = "my-secret"),
            world = WorldConfig(startRoom = "zone:room"),
        )
        config.validated()
    }

    @Test
    fun `grpc shared secret not required in STANDALONE mode`() {
        val config = AppConfig(
            mode = DeploymentMode.STANDALONE,
            grpc = GrpcConfig(sharedSecret = ""),
            world = WorldConfig(startRoom = "zone:room"),
        )
        config.validated()
    }

    @Test
    fun `grpc validation rejects non-positive timestampToleranceMs`() {
        val invalid = AppConfig(
            mode = DeploymentMode.ENGINE,
            grpc = GrpcConfig(sharedSecret = "secret", timestampToleranceMs = 0L),
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
    fun `secrets overlay outranks extra sources and base config`(
        @TempDir tmp: Path,
    ) {
        // The secrets overlay is the highest-priority source so real secret
        // values injected by the deployment pipeline always beat whatever
        // placeholder the creator-generated overlay carries. Simulated here
        // by adding a map source with a value for admin.token and confirming
        // the PropertySource.file for secrets.yaml wins.
        val secretsFile = tmp.resolve("secrets.yaml")
        secretsFile.writeText(
            """
            ambonmud:
              admin:
                token: "REAL_SECRET_FROM_SSM"
            """.trimIndent(),
        )
        val overlayLikePlaceholder =
            PropertySource.map(
                mapOf(
                    "ambonmud.admin.enabled" to "true",
                    "ambonmud.admin.token" to "OVERRIDE_ME_FROM_ENV",
                ),
            )

        val previous = System.getProperty("ambon.secretsFile")
        System.setProperty("ambon.secretsFile", secretsFile.toString())
        try {
            val config =
                AppConfigLoader.load(
                    resourcePath = testResourcePath,
                    extraSources = listOf(overlayLikePlaceholder),
                )
            assertEquals("REAL_SECRET_FROM_SSM", config.admin.token)
            assertTrue(config.admin.enabled)
        } finally {
            if (previous == null) {
                System.clearProperty("ambon.secretsFile")
            } else {
                System.setProperty("ambon.secretsFile", previous)
            }
        }
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

    private fun configWithRacialAbility(ability: RacialAbilityConfig): AppConfig =
        AppConfig(
            engine = EngineConfig(
                races = RaceEngineConfig(
                    definitions = mapOf(
                        "testrace" to RaceDefinitionConfig(displayName = "Test", racialAbility = ability),
                    ),
                ),
                // Seed the definitions a fully-configured ability references so existence checks pass.
                pets = PetConfig(
                    definitions = mapOf(
                        "spore_mushroom" to PetTemplateConfig(name = "a mushroom"),
                    ),
                ),
                statusEffects = StatusEffectEngineConfig(
                    definitions = mapOf(
                        "dazzle_stun" to StatusEffectDefinitionConfig(effectType = "stun"),
                        "stoneform_root" to StatusEffectDefinitionConfig(effectType = "root"),
                    ),
                ),
            ),
            world = validWorld,
        )

    @Test
    fun `validation rejects racial ability with unknown kind`() {
        val invalid = configWithRacialAbility(RacialAbilityConfig(kind = "NOT_A_KIND"))
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects low-health racial ability with no trigger threshold`() {
        val invalid =
            configWithRacialAbility(
                RacialAbilityConfig(kind = "PYRAE_IMMOLATE", triggerHealthPct = 0, aoeDamagePctOfMaxHp = 0.6),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects dazzle racial ability without a stun status id`() {
        val invalid =
            configWithRacialAbility(
                RacialAbilityConfig(kind = "AURELIA_DAZZLE", triggerHealthPct = 15, stunStatusId = null),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects summon racial ability without a pet template`() {
        val invalid =
            configWithRacialAbility(
                RacialAbilityConfig(kind = "MYCORAE_SPORES", triggerHealthPct = 25, petTemplateKey = null),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects dazzle referencing an undefined status effect`() {
        val invalid =
            configWithRacialAbility(
                RacialAbilityConfig(kind = "AURELIA_DAZZLE", triggerHealthPct = 15, stunStatusId = "no_such_effect"),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects dazzle referencing a non-stun status effect`() {
        // stoneform_root exists in the fixture but is a 'root', not a 'stun' — the dazzle would no-op.
        val invalid =
            configWithRacialAbility(
                RacialAbilityConfig(kind = "AURELIA_DAZZLE", triggerHealthPct = 15, stunStatusId = "stoneform_root"),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation rejects summon referencing an undefined pet template`() {
        val invalid =
            configWithRacialAbility(
                RacialAbilityConfig(kind = "MYCORAE_SPORES", triggerHealthPct = 25, petTemplateKey = "no_such_pet"),
            )
        assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }

    @Test
    fun `validation accepts a fully configured racial ability`() {
        val valid =
            configWithRacialAbility(
                RacialAbilityConfig(
                    kind = "MYCORAE_SPORES",
                    triggerHealthPct = 25,
                    petTemplateKey = "spore_mushroom",
                    petCountMin = 1,
                    petCountMax = 3,
                ),
            )
        valid.validated() // should not throw
    }

    @Test
    fun `multiclass maxClasses decodes JavaScript MAX_SAFE_INTEGER sentinel`() {
        // Regression: prod overlay YAML carried `maxClasses: 9007199254740991` (Number.MAX_SAFE_INTEGER,
        // the JS-side "unlimited" sentinel). Hoplite refused to decode that into the original `Int`
        // field and crash-looped the server. Widening the field to `Long` makes the sentinel round-trip.
        val jsMaxSafeInteger = 9_007_199_254_740_991L
        val source = PropertySource.map(
            mapOf("ambonmud.engine.multiclass.maxClasses" to jsMaxSafeInteger.toString()),
        )
        val config = AppConfigLoader.load(
            resourcePath = testResourcePath,
            extraSources = listOf(source),
        )
        assertEquals(jsMaxSafeInteger, config.engine.multiclass.maxClasses)
    }
}
