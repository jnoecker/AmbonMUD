package dev.ambon.engine

import dev.ambon.config.PetConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
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
 * Regression tests for pet image URL prefix (issue #1068) and pet dismiss visibility
 * (issue #1069 — companion to #1066 on the removal side).
 *
 * Both bugs share the same shape: pet template state was used as-is when summoning,
 * skipping the asset-URL helper that other mob/item paths run through, and the
 * removal-side dismiss didn't notify other players in the room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PetImageAndDismissTest {
    private val roomId: RoomId = TEST_ROOM_ID
    private val ownerSid = SessionId(1L)
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
                image = "pets/fire_familiar.png",
            ),
        ),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #1068 — pet image must be resolved against the asset host.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `pet image is resolved against the configured asset host (issue 1068)`() {
        val pets = PetSystem(
            config = petConfig,
            mobs = MobRegistry(),
            clock = MutableClock(0L),
            imagesBaseUrl = "https://auringold.ambon.dev/img/",
        )

        val pet = pets.summon(ownerSid, "fire_familiar", roomId, ownerLevel = 1)!!

        assertTrue(
            pet.image == "https://auringold.ambon.dev/img/pets/fire_familiar.png",
            "expected prefixed asset URL, got=${pet.image}",
        )
    }

    @Test
    fun `pet image without trailing slash on base still resolves correctly`() {
        val pets = PetSystem(
            config = petConfig,
            mobs = MobRegistry(),
            clock = MutableClock(0L),
            imagesBaseUrl = "https://auringold.ambon.dev/img",
        )

        val pet = pets.summon(ownerSid, "fire_familiar", roomId, ownerLevel = 1)!!
        assertTrue(
            pet.image == "https://auringold.ambon.dev/img/pets/fire_familiar.png",
            "expected normalized URL, got=${pet.image}",
        )
    }

    @Test
    fun `pet image left untouched when template image is null`() {
        val cfg = PetConfig(definitions = mapOf("no_img" to PetTemplateConfig(name = "a wisp")))
        val pets = PetSystem(config = cfg, mobs = MobRegistry(), clock = MutableClock(0L), imagesBaseUrl = "https://x/")
        val pet = pets.summon(ownerSid, "no_img", roomId, ownerLevel = 1)!!
        assertNull(pet.image)
    }

    @Test
    fun `pet image left untouched when template path is already absolute`() {
        val cfg = PetConfig(
            definitions = mapOf(
                "abs" to PetTemplateConfig(name = "a wisp", image = "https://other.cdn.example/p.png"),
            ),
        )
        val pets = PetSystem(config = cfg, mobs = MobRegistry(), clock = MutableClock(0L), imagesBaseUrl = "https://x/")
        val pet = pets.summon(ownerSid, "abs", roomId, ownerLevel = 1)!!
        assertTrue(pet.image == "https://other.cdn.example/p.png", "got=${pet.image}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #1069 — dismissed pet must broadcast Room.RemoveMob immediately.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `manual dismiss broadcasts Room_RemoveMob to summoner and bystanders (issue 1069)`() = runTest {
        val fixture = AbilityTestFixture(roomId = roomId)
        val pets = PetSystem(
            config = petConfig,
            mobs = fixture.mobs,
            clock = fixture.clock,
            imagesBaseUrl = "https://auringold.ambon.dev/img/",
        )
        val gmcp = GmcpEmitter(outbound = fixture.outbound, supportsPackage = { _, _ -> true })

        fixture.players.loginOrFail(ownerSid, "Owner")
        fixture.players.loginOrFail(bystanderSid, "Bystander")
        val pet = pets.summon(ownerSid, "fire_familiar", roomId, ownerLevel = 1)!!
        fixture.outbound.drainAll()

        // Mirror what PetHandler/GameEngine do after dismissAll: broadcast removal
        // (and refreshed Room.MobInfo) for every dismissed pet.
        val dismissed = pets.dismissAll(ownerSid)
        for (entry in dismissed) {
            gmcp.broadcastRoomRemoveMob(entry.roomId, entry.petId.value, fixture.players)
            val mobInfo = gmcp.buildMobInfoEntries(fixture.mobs.mobsInRoom(entry.roomId))
            gmcp.broadcastRoomMobInfo(entry.roomId, mobInfo, fixture.players)
        }

        val gmcpEvents = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.GmcpData>()
        val ownerRemove = gmcpEvents.filter { it.sessionId == ownerSid && it.gmcpPackage == "Room.RemoveMob" }
        val bystanderRemove = gmcpEvents.filter { it.sessionId == bystanderSid && it.gmcpPackage == "Room.RemoveMob" }
        assertTrue(ownerRemove.isNotEmpty(), "summoner must receive Room.RemoveMob on dismiss")
        assertTrue(bystanderRemove.isNotEmpty(), "bystander must receive Room.RemoveMob on dismiss")
        assertTrue(
            ownerRemove.all { it.jsonData.contains(pet.id.value) },
            "Room.RemoveMob payload must reference the dismissed pet id — got=${ownerRemove.map { it.jsonData }}",
        )

        val ownerMobInfo = gmcpEvents.filter { it.sessionId == ownerSid && it.gmcpPackage == "Room.MobInfo" }
        val bystanderMobInfo = gmcpEvents.filter { it.sessionId == bystanderSid && it.gmcpPackage == "Room.MobInfo" }
        assertTrue(ownerMobInfo.isNotEmpty(), "summoner must receive companion Room.MobInfo")
        assertTrue(bystanderMobInfo.isNotEmpty(), "bystander must receive companion Room.MobInfo")
    }

    @Test
    fun `duration expiry returns the pet's room so callers can broadcast (issue 1069)`() {
        val clock = MutableClock(0L)
        val pets = PetSystem(
            config = petConfig,
            mobs = MobRegistry(),
            clock = clock,
            imagesBaseUrl = "/images/",
        )
        val pet = pets.summon(ownerSid, "fire_familiar", roomId, ownerLevel = 1, durationMs = 5_000L)!!

        clock.advance(5_000L)
        val expired = pets.tick()
        assertTrue(expired.size == 1, "expected one expired pet, got=$expired")
        val ex = expired.single()
        assertTrue(ex.petId == pet.id, "expired entry must carry the petId for broadcast routing")
        assertTrue(ex.roomId == roomId, "expired entry must carry the roomId for broadcast routing")
    }

    @Test
    fun `onOwnerDisconnect returns dismissed pets so callers can broadcast (issue 1069)`() {
        val pets = PetSystem(
            config = petConfig,
            mobs = MobRegistry(),
            clock = MutableClock(0L),
            imagesBaseUrl = "/images/",
        )
        val pet = pets.summon(ownerSid, "fire_familiar", roomId, ownerLevel = 1)!!

        val dismissed = pets.onOwnerDisconnect(ownerSid)
        assertTrue(dismissed.size == 1, "onOwnerDisconnect must return dismissed pets, got=$dismissed")
        val entry = dismissed.single()
        assertTrue(entry.petId == pet.id)
        assertTrue(entry.roomId == roomId)
    }
}
