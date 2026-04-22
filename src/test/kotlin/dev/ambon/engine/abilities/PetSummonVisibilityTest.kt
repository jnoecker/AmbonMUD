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
 * Regression test for issue #1066 — when a player summons a pet, the pet must become
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
                manaCost = 5,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.SummonPet(petTemplateKey = "fire_familiar"),
            ),
        )

        // Callback must mirror GameEngine.onSummonPet — issue #1066 fix lives there.
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
            "Room.AddMob payload must mention the pet's name — got=${summonerAddMob.map { it.jsonData }}",
        )

        val summonerMobInfo = gmcp.filter { it.sessionId == summonerSid && it.gmcpPackage == "Room.MobInfo" }
        val bystanderMobInfo = gmcp.filter { it.sessionId == bystanderSid && it.gmcpPackage == "Room.MobInfo" }
        assertTrue(summonerMobInfo.isNotEmpty(), "summoner must receive companion Room.MobInfo")
        assertTrue(bystanderMobInfo.isNotEmpty(), "bystander must receive companion Room.MobInfo")
    }
}
