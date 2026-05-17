package dev.ambon.engine.abilities

import dev.ambon.config.PetConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.PetSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.AbilityTestFixture
import dev.ambon.test.MutableClock
import dev.ambon.test.TEST_ROOM_ID
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression test for issue #1066 â€” when a player summons a pet, the pet must become
 * visible in the room (canvas + mob list) without waiting for the next room change.
 *
 * The summon happens via [AbilitySystem] invoking the `onSummonPet` callback; in
 * production that callback is wired in `GameEngine` and must broadcast `Room.AddMob`
 * (and the matching `Room.MobInfo`) to every player in the summoner's room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetSummonVisibilityTest {
    private val roomId = TEST_ROOM_ID
    private val summonerSid = SessionId(1L)
    private val bystanderSid = SessionId(2L)

    private val petConfig = PetConfig(
        definitions = mapOf(
            "fire_familiar" to PetTemplateConfig(
                name = "a fire familiar",
                description = "A small elemental of living flame.",
                hp = 20,
                minDamage = 2,
                maxDamage = 5,
                armor = 1,
            ),
            "stone_golem" to PetTemplateConfig(
                name = "a stone golem",
                description = "A lumbering construct of packed earth.",
                hp = 30,
                minDamage = 3,
                maxDamage = 6,
                armor = 2,
            ),
        ),
    )

    @Test
    fun `summoning a pet emits Room_AddMob to summoner and bystanders`() = runTest {
        val clock = MutableClock(0L)
        val fixture = AbilityTestFixture(roomId = roomId, clock = clock)
        val petSystem = PetSystem(config = petConfig, mobs = fixture.mobs, clock = clock)

        // Mirror the GameEngine wiring: emitter that reports "all packages supported"
        // so the Room.Mobs support gate does not filter events out.
        val gmcpEmitter = GmcpEmitter(
            outbound = fixture.outbound,
            supportsPackage = { _, _ -> true },
        )

        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("summon_familiar"),
                displayName = "Summon Familiar",
                description = "Summons a small elemental.",
                manaCostPct = 25.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.SummonPet(petTemplateKey = "fire_familiar"),
            ),
        )

        // Callback must mirror GameEngine.onSummonPet â€” issue #1066 fix lives there.
        val abilitySystem = AbilitySystem(
            players = fixture.players,
            registry = registry,
            outbound = fixture.outbound,
            combat = fixture.combat,
            clock = clock,
            mobs = fixture.mobs,
            onSummonPet = { sid, templateKey, durationMs ->
                val player = fixture.players.get(sid)
                if (player != null) {
                    val pet = petSystem.summon(sid, templateKey, player.roomId, player.level, durationMs, player.name)
                    if (pet != null) {
                        gmcpEmitter.broadcastRoomAddMob(player.roomId, pet, fixture.players)
                        val mobsInRoom = fixture.mobs.mobsInRoom(player.roomId)
                        val mobInfoEntries = gmcpEmitter.buildMobInfoEntries(mobsInRoom)
                        gmcpEmitter.broadcastRoomMobInfo(player.roomId, mobInfoEntries, fixture.players)
                    }
                }
            },
        )

        fixture.players.loginOrFail(summonerSid, "Summoner")
        fixture.players.loginOrFail(bystanderSid, "Bystander")
        abilitySystem.syncAbilities(summonerSid, 1)
        val summoner = fixture.players.get(summonerSid)!!
        summoner.mana = 20

        fixture.outbound.drainAll()

        val err = abilitySystem.cast(summonerSid, "summon_familiar", null)
        assertNull(err, "expected cast to succeed")

        val events = fixture.outbound.drainAll()
        val gmcp = events.filterIsInstance<OutboundEvent.GmcpData>()

        val summonerAddMob = gmcp.filter { it.sessionId == summonerSid && it.gmcpPackage == "Room.AddMob" }
        val bystanderAddMob = gmcp.filter { it.sessionId == bystanderSid && it.gmcpPackage == "Room.AddMob" }

        assertTrue(summonerAddMob.isNotEmpty(), "summoner must receive Room.AddMob for the new pet")
        assertTrue(bystanderAddMob.isNotEmpty(), "bystander in same room must receive Room.AddMob")
        assertTrue(
            summonerAddMob.all { it.jsonData.contains("a fire familiar") },
            "Room.AddMob payload must mention the pet's name â€” got=${summonerAddMob.map { it.jsonData }}",
        )

        val summonerMobInfo = gmcp.filter { it.sessionId == summonerSid && it.gmcpPackage == "Room.MobInfo" }
        val bystanderMobInfo = gmcp.filter { it.sessionId == bystanderSid && it.gmcpPackage == "Room.MobInfo" }
        assertTrue(summonerMobInfo.isNotEmpty(), "summoner must receive companion Room.MobInfo")
        assertTrue(bystanderMobInfo.isNotEmpty(), "bystander must receive companion Room.MobInfo")
    }

    /**
     * Regression test for issue #1093 â€” re-summoning while a pet already exists must
     * emit `Room.RemoveMob` for the replaced pet so clients don't leave a ghost sprite
     * stacked in the room. The server already dismisses the old pet internally
     * (PetSystem.summon -> dismissAll), but without the broadcast the web client keeps
     * rendering the old pet until the owner changes rooms.
     */
    @Test
    fun `re-summoning a pet emits Room_RemoveMob for the replaced pet`() = runTest {
        val clock = MutableClock(0L)
        val fixture = AbilityTestFixture(roomId = roomId, clock = clock)
        val petSystem = PetSystem(config = petConfig, mobs = fixture.mobs, clock = clock)

        val gmcpEmitter = GmcpEmitter(
            outbound = fixture.outbound,
            supportsPackage = { _, _ -> true },
        )

        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("summon_familiar"),
                displayName = "Summon Familiar",
                description = "Summons a small elemental.",
                manaCostPct = 25.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.SummonPet(petTemplateKey = "fire_familiar"),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("summon_golem"),
                displayName = "Summon Golem",
                description = "Summons a stone golem.",
                manaCostPct = 25.0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.SummonPet(petTemplateKey = "stone_golem"),
            ),
        )

        // Mirror the production GameEngine.onSummonPet callback â€” issue #1093 fix lives there.
        val abilitySystem = AbilitySystem(
            players = fixture.players,
            registry = registry,
            outbound = fixture.outbound,
            combat = fixture.combat,
            clock = clock,
            mobs = fixture.mobs,
            onSummonPet = { sid, templateKey, durationMs ->
                val player = fixture.players.get(sid)
                if (player != null) {
                    val replacedPets = petSystem.getPets(sid).map { Triple(it.id, it.roomId, it.name) }
                    val pet = petSystem.summon(sid, templateKey, player.roomId, player.level, durationMs, player.name)
                    if (pet != null) {
                        for ((oldId, oldRoomId, _) in replacedPets) {
                            if (oldId == pet.id) continue
                            gmcpEmitter.broadcastRoomRemoveMob(oldRoomId, oldId.value, fixture.players)
                            val oldRoomMobs = fixture.mobs.mobsInRoom(oldRoomId)
                            val oldMobInfo = gmcpEmitter.buildMobInfoEntries(oldRoomMobs)
                            gmcpEmitter.broadcastRoomMobInfo(oldRoomId, oldMobInfo, fixture.players)
                        }
                        gmcpEmitter.broadcastRoomAddMob(player.roomId, pet, fixture.players)
                        val mobsInRoom = fixture.mobs.mobsInRoom(player.roomId)
                        val mobInfoEntries = gmcpEmitter.buildMobInfoEntries(mobsInRoom)
                        gmcpEmitter.broadcastRoomMobInfo(player.roomId, mobInfoEntries, fixture.players)
                    }
                }
            },
        )

        fixture.players.loginOrFail(summonerSid, "Summoner")
        fixture.players.loginOrFail(bystanderSid, "Bystander")
        abilitySystem.syncAbilities(summonerSid, 1)
        val summoner = fixture.players.get(summonerSid)!!
        summoner.mana = 100

        // First summon â€” capture the pet id so we can assert its removal on re-summon.
        assertNull(abilitySystem.cast(summonerSid, "summon_familiar", null))
        val firstPet = petSystem.getActivePet(summonerSid)!!
        val firstPetId = firstPet.id.value
        fixture.outbound.drainAll()

        // Second summon should replace the first pet and broadcast its removal.
        assertNull(abilitySystem.cast(summonerSid, "summon_golem", null))

        val gmcp = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.GmcpData>()
        val summonerRemoves = gmcp.filter { it.sessionId == summonerSid && it.gmcpPackage == "Room.RemoveMob" }
        val bystanderRemoves = gmcp.filter { it.sessionId == bystanderSid && it.gmcpPackage == "Room.RemoveMob" }

        assertTrue(
            summonerRemoves.any { it.jsonData.contains(firstPetId) },
            "summoner must receive Room.RemoveMob for replaced pet id=$firstPetId â€” got=${summonerRemoves.map { it.jsonData }}",
        )
        assertTrue(
            bystanderRemoves.any { it.jsonData.contains(firstPetId) },
            "bystander must receive Room.RemoveMob for replaced pet id=$firstPetId â€” got=${bystanderRemoves.map { it.jsonData }}",
        )
    }
}
