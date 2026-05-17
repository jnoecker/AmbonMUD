package dev.ambon.engine.abilities

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.AbilityDefinitionConfig
import dev.ambon.config.AbilityEffectConfig
import dev.ambon.config.AbilityEngineConfig
import dev.ambon.domain.DamageRange
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.test.AbilityTestFixture
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_ROOM_ID
import dev.ambon.test.TEST_SESSION_ID
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class SkillTreeTest {
    private val roomId = TEST_ROOM_ID
    private val sid = TEST_SESSION_ID

    // ---- AbilityRegistryLoader: DAG validation ----

    @Test
    fun `DAG validation passes for valid prerequisite chain`() {
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("a"),
                displayName = "A",
                description = "",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("b"),
                displayName = "B",
                description = "",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 5,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                prerequisites = setOf(AbilityId("a")),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("c"),
                displayName = "C",
                description = "",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 10,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                prerequisites = setOf(AbilityId("b")),
            ),
        )
        // Should not throw
        AbilityRegistryLoader.validateNoPrerequisiteCycles(registry)
    }

    @Test
    fun `DAG validation detects direct cycle`() {
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("x"),
                displayName = "X",
                description = "",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                prerequisites = setOf(AbilityId("y")),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("y"),
                displayName = "Y",
                description = "",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                prerequisites = setOf(AbilityId("x")),
            ),
        )
        val ex = assertThrows<IllegalStateException> {
            AbilityRegistryLoader.validateNoPrerequisiteCycles(registry)
        }
        assertTrue(ex.message!!.contains("Circular prerequisite chain"))
    }

    @Test
    fun `DAG validation detects transitive cycle`() {
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("a"),
                displayName = "A",
                description = "",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                prerequisites = setOf(AbilityId("c")),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("b"),
                displayName = "B",
                description = "",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                prerequisites = setOf(AbilityId("a")),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("c"),
                displayName = "C",
                description = "",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                prerequisites = setOf(AbilityId("b")),
            ),
        )
        val ex = assertThrows<IllegalStateException> {
            AbilityRegistryLoader.validateNoPrerequisiteCycles(registry)
        }
        assertTrue(ex.message!!.contains("Circular prerequisite chain"))
    }

    @Test
    fun `loader passes prerequisites and tree fields through from config`() {
        val config = AbilityEngineConfig(
            definitions = mapOf(
                "base_spell" to AbilityDefinitionConfig(
                    displayName = "Base Spell",
                    description = "Foundation spell.",
                    manaCostPct = 5.0,
                    cooldownMs = 0,
                    levelRequired = 1,
                    skillPointCost = 0,
                    targetType = "ENEMY",
                    effect = AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 1, maxDamage = 2),
                    requiredClass = "MAGE",
                    tree = "mage_fire",
                    tier = 0,
                ),
                "advanced_spell" to AbilityDefinitionConfig(
                    displayName = "Advanced Spell",
                    description = "Requires base.",
                    manaCostPct = 10.0,
                    cooldownMs = 1000,
                    levelRequired = 5,
                    targetType = "ENEMY",
                    effect = AbilityEffectConfig(type = "DIRECT_DAMAGE", minDamage = 3, maxDamage = 6),
                    requiredClass = "MAGE",
                    prerequisites = listOf("base_spell"),
                    tree = "mage_fire",
                    tier = 1,
                ),
            ),
        )
        val registry = AbilityRegistry()
        AbilityRegistryLoader.load(config, registry)

        val base = registry.get(AbilityId("base_spell"))!!
        assertEquals("mage_fire", base.tree)
        assertEquals(0, base.tier)
        assertEquals(0, base.skillPointCost)
        assertTrue(base.prerequisites.isEmpty())

        val advanced = registry.get(AbilityId("advanced_spell"))!!
        assertEquals("mage_fire", advanced.tree)
        assertEquals(1, advanced.tier)
        assertEquals(setOf(AbilityId("base_spell")), advanced.prerequisites)
    }

    // ---- AbilityRegistry: query methods ----

    @Test
    fun `abilitiesInTree returns abilities sorted by tier`() {
        val registry = buildTreeRegistry()
        val treeAbilities = registry.abilitiesInTree("test_tree")
        assertEquals(3, treeAbilities.size)
        assertEquals(0, treeAbilities[0].tier)
        assertEquals(1, treeAbilities[1].tier)
        assertEquals(2, treeAbilities[2].tier)
    }

    @Test
    fun `treesForClass returns distinct tree names`() {
        val registry = buildTreeRegistry()
        val trees = registry.treesForClass("WARRIOR")
        assertEquals(setOf("test_tree", "other_tree", "free_chain"), trees)
    }

    @Test
    fun `isPrerequisiteMet returns true when all prereqs learned`() {
        val registry = buildTreeRegistry()
        assertTrue(registry.isPrerequisiteMet(AbilityId("tier1"), setOf("tier0")))
    }

    @Test
    fun `isPrerequisiteMet returns false when prereqs not learned`() {
        val registry = buildTreeRegistry()
        assertFalse(registry.isPrerequisiteMet(AbilityId("tier1"), emptySet()))
    }

    @Test
    fun `isPrerequisiteMet returns true for ability with no prereqs`() {
        val registry = buildTreeRegistry()
        assertTrue(registry.isPrerequisiteMet(AbilityId("tier0"), emptySet()))
    }

    // ---- AbilitySystem: prerequisite enforcement ----

    @Test
    fun `learnAbility fails when prerequisite not met`() = runTest {
        val h = buildSkillTreeHarness()
        h.players.loginOrFail(sid, "Learner")

        val error = h.abilitySystem.learnAbility(
            sessionId = sid,
            abilityId = AbilityId("tier1"),
            level = 50,
            unlockedClasses = setOf("WARRIOR"),
            skillPointInterval = 1,
            learnedIds = emptySet(),
        )
        assertNotNull(error)
        assertTrue(error!!.contains("Missing prerequisites"))
        assertTrue(error.contains("Tier Zero"))
    }

    @Test
    fun `learnAbility succeeds when prerequisite is met`() = runTest {
        val h = buildSkillTreeHarness()
        h.players.loginOrFail(sid, "Learner")
        h.abilitySystem.loadAbilities(sid, setOf("tier0"))

        val error = h.abilitySystem.learnAbility(
            sessionId = sid,
            abilityId = AbilityId("tier1"),
            level = 50,
            unlockedClasses = setOf("WARRIOR"),
            skillPointInterval = 1,
            learnedIds = setOf("tier0"),
        )
        assertNull(error)
    }

    @Test
    fun `learnAbility succeeds for ability with no prerequisites`() = runTest {
        val h = buildSkillTreeHarness()
        h.players.loginOrFail(sid, "Learner")
        h.abilitySystem.loadAbilities(sid, emptySet())

        val error = h.abilitySystem.learnAbility(
            sessionId = sid,
            abilityId = AbilityId("other_tree_ability"),
            level = 50,
            unlockedClasses = setOf("WARRIOR"),
            skillPointInterval = 1,
        )
        assertNull(error)
    }

    @Test
    fun `trainableAbilities includes locked abilities for UI display`() {
        val h = buildSkillTreeHarness()
        // With empty learned set, tier1 should still appear (for UI display)
        // since it meets level requirement â€” just locked by prereqs
        val trainable = h.abilitySystem.trainableAbilities("WARRIOR", 50, emptySet())
        val ids = trainable.map { it.id.value }
        assertTrue("tier0" in ids, "tier0 should be trainable")
        assertTrue("tier1" in ids, "tier1 should be trainable (shown as locked)")
        assertTrue("tier2" in ids, "tier2 should be trainable (shown as locked)")
    }

    @Test
    fun `availableSkillPoints uses total spent cost instead of learned ability count`() {
        val h = buildSkillTreeHarness()
        val spent = h.abilitySystem.spentSkillPoints(setOf("tier0", "tier1"))
        assertEquals(3, spent)
        assertEquals(2, h.abilitySystem.availableSkillPoints(level = 10, spentPoints = spent, interval = 2))
    }

    @Test
    fun `loadAbilities auto learns zero cost chain on login`() = runTest {
        val h = buildSkillTreeHarness()
        h.players.loginOrFail(sid, "Learner")
        val me = h.players.get(sid)!!
        me.level = 50
        me.unlockedClasses.add("WARRIOR")

        h.abilitySystem.loadAbilities(sid, emptySet())

        val known = h.abilitySystem.knownAbilityIds(sid)
        assertTrue("free_chain_start" in known)
        assertTrue("free_chain_end" in known)
    }

    @Test
    fun `zero cost ability with paid prerequisite does not auto learn until prerequisite is known`() = runTest {
        val h = buildSkillTreeHarness()
        h.players.loginOrFail(sid, "Learner")
        val me = h.players.get(sid)!!
        me.level = 50
        me.unlockedClasses.add("WARRIOR")

        h.abilitySystem.loadAbilities(sid, emptySet())
        assertTrue("tier2" !in h.abilitySystem.knownAbilityIds(sid))

        val error = h.abilitySystem.learnAbility(
            sessionId = sid,
            abilityId = AbilityId("tier1"),
            level = me.level,
            unlockedClasses = me.unlockedClasses,
            skillPointInterval = 1,
        )
        assertNull(error)

        h.abilitySystem.recomputeKnownAbilities(sid, me.level, me.unlockedClasses)
        assertTrue("tier2" in h.abilitySystem.knownAbilityIds(sid))
    }

    @Test
    fun `already learned zero cost abilities consume no skill points`() {
        val h = buildSkillTreeHarness()
        val spent = h.abilitySystem.spentSkillPoints(setOf("tier0"))
        assertEquals(0, spent)
        assertEquals(5, h.abilitySystem.availableSkillPoints(level = 10, spentPoints = spent, interval = 2))
    }

    @Test
    fun `learnAbility fails when ability cost exceeds remaining points`() = runTest {
        val h = buildSkillTreeHarness()
        h.players.loginOrFail(sid, "Learner")
        val me = h.players.get(sid)!!
        me.level = 5
        me.unlockedClasses.add("WARRIOR")
        h.abilitySystem.loadAbilities(sid, emptySet())

        val error = h.abilitySystem.learnAbility(
            sessionId = sid,
            abilityId = AbilityId("tier1"),
            level = me.level,
            unlockedClasses = me.unlockedClasses,
            skillPointInterval = 5,
        )

        assertEquals("You need 3 skill points to learn Tier One.", error)
    }

    // ---- Helpers ----

    private fun buildTreeRegistry(): AbilityRegistry {
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("tier0"),
                displayName = "Tier Zero",
                description = "Base ability.",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                requiredClass = "WARRIOR",
                tree = "test_tree",
                tier = 0,
                skillPointCost = 0,
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("tier1"),
                displayName = "Tier One",
                description = "Requires tier0.",
                manaCostPct = 10.0,
                cooldownMs = 0,
                levelRequired = 5,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(2, 2)),
                requiredClass = "WARRIOR",
                tree = "test_tree",
                tier = 1,
                prerequisites = setOf(AbilityId("tier0")),
                skillPointCost = 3,
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("tier2"),
                displayName = "Tier Two",
                description = "Requires tier1.",
                manaCostPct = 15.0,
                cooldownMs = 0,
                levelRequired = 10,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(3, 3)),
                requiredClass = "WARRIOR",
                tree = "test_tree",
                tier = 2,
                prerequisites = setOf(AbilityId("tier1")),
                skillPointCost = 0,
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("other_tree_ability"),
                displayName = "Other Tree",
                description = "In a different tree.",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                requiredClass = "WARRIOR",
                tree = "other_tree",
                tier = 0,
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("free_chain_start"),
                displayName = "Free Chain Start",
                description = "Automatically learned first.",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                requiredClass = "WARRIOR",
                tree = "free_chain",
                tier = 0,
                skillPointCost = 0,
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("free_chain_end"),
                displayName = "Free Chain End",
                description = "Automatically learned second.",
                manaCostPct = 5.0,
                cooldownMs = 0,
                levelRequired = 2,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
                requiredClass = "WARRIOR",
                tree = "free_chain",
                tier = 1,
                prerequisites = setOf(AbilityId("free_chain_start")),
                skillPointCost = 0,
            ),
        )
        return registry
    }

    private data class SkillTreeHarness(
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val items: ItemRegistry,
        val outbound: LocalOutboundBus,
        val registry: AbilityRegistry,
        val abilitySystem: AbilitySystem,
        val clock: MutableClock,
    )

    private fun buildSkillTreeHarness(): SkillTreeHarness {
        val fixture = AbilityTestFixture(roomId = roomId)
        val registry = buildTreeRegistry()
        val abilitySystem = fixture.buildAbilitySystem(registry = registry)
        return SkillTreeHarness(
            players = fixture.players,
            mobs = fixture.mobs,
            items = fixture.items,
            outbound = fixture.outbound,
            registry = registry,
            abilitySystem = abilitySystem,
            clock = fixture.clock,
        )
    }
}
