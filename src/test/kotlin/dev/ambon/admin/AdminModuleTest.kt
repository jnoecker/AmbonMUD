package dev.ambon.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.persistence.PlayerId
import dev.ambon.persistence.PlayerRecord
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64

class AdminModuleTest {
    private val token = "super-secret-token"
    private val authHeader = "Basic " + Base64.getEncoder().encodeToString("admin:$token".toByteArray())

    private val roomId = RoomId("testzone:start")
    private val room = Room(id = roomId, title = "Test Start Room", description = "A test room.", exits = emptyMap())
    private val world = World(rooms = mapOf(roomId to room), startRoom = roomId)
    private val repo = InMemoryPlayerRepository()
    private val mobs = MobRegistry()
    private val players = PlayerRegistry(startRoom = roomId, repo = repo, items = ItemRegistry())
    private val json = jacksonObjectMapper()

    @BeforeEach
    fun resetRepo() {
        repo.clear()
    }

    private fun testAdmin(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                adminModule(
                    token = token,
                    players = players,
                    playerRepo = repo,
                    mobs = mobs,
                    world = world,
                    grafanaUrl = "http://grafana.example.com",
                    metricsUrl = "http://localhost:8080/metrics",
                    json = json,
                )
            }
            block()
        }

    // ── Auth ─────────────────────────────────────────────────────────────────

    @Test
    fun `GET overview without credentials returns 401`() =
        testAdmin {
            val resp = client.get("/")
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `GET overview with wrong token returns 401`() =
        testAdmin {
            val badAuth = "Basic " + Base64.getEncoder().encodeToString("admin:wrong".toByteArray())
            val resp = client.get("/") { header(HttpHeaders.Authorization, badAuth) }
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `GET overview with correct token returns 200 and HTML`() =
        testAdmin {
            val resp = client.get("/") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("Overview"))
            assertTrue(body.contains("AmbonMUD"))
        }

    // ── Overview page ─────────────────────────────────────────────────────────

    @Test
    fun `GET overview page shows world stats`() =
        testAdmin {
            val resp = client.get("/") { header(HttpHeaders.Authorization, authHeader) }
            val body = resp.bodyAsText()
            assertTrue(body.contains("Zones"), "Should show Zones stat")
            assertTrue(body.contains("Rooms"), "Should show Rooms stat")
        }

    @Test
    fun `GET overview page shows Grafana link when configured`() =
        testAdmin {
            val body = client.get("/") { header(HttpHeaders.Authorization, authHeader) }.bodyAsText()
            assertTrue(body.contains("http://grafana.example.com"), "Should include Grafana URL")
        }

    // ── JSON API - overview ───────────────────────────────────────────────────

    @Test
    fun `GET api-overview returns JSON with correct room count`() =
        testAdmin {
            val resp = client.get("/api/overview") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val node = json.readTree(resp.bodyAsText())
            assertEquals(1, node["roomsTotal"].intValue())
            assertEquals(1, node["zonesLoaded"].intValue())
            assertEquals(0, node["playersOnline"].intValue())
            assertEquals("http://grafana.example.com", node["grafanaUrl"].textValue())
        }

    // ── Players page ──────────────────────────────────────────────────────────

    @Test
    fun `GET players page returns 200`() =
        testAdmin {
            val resp = client.get("/players") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(resp.bodyAsText().contains("Players"))
        }

    @Test
    fun `GET players page search for unknown player shows not-found message`() =
        testAdmin {
            val resp = client.get("/players?q=nobody") { header(HttpHeaders.Authorization, authHeader) }
            val body = resp.bodyAsText()
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(body.contains("nobody"), "Should echo the search term")
        }

    @Test
    fun `GET players page search finds offline player in repo`() =
        testAdmin {
            repo.save(offlinePlayerRecord("Xandar"))
            val resp = client.get("/players?q=Xandar") { header(HttpHeaders.Authorization, authHeader) }
            val body = resp.bodyAsText()
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(body.contains("Xandar"), "Should show found player name")
        }

    // ── Player detail page ────────────────────────────────────────────────────

    @Test
    fun `GET player detail for unknown player returns 404`() =
        testAdmin {
            val resp = client.get("/players/nobody") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `GET player detail for existing offline player returns 200 with stats`() =
        testAdmin {
            repo.save(offlinePlayerRecord("Theron", level = 5, isStaff = false))
            val resp = client.get("/players/Theron") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("Theron"), "Should show player name")
            assertTrue(body.contains("Grant Staff"), "Should have staff grant button")
        }

    @Test
    fun `GET player detail for staff player shows revoke button`() =
        testAdmin {
            repo.save(offlinePlayerRecord("Zorak", isStaff = true))
            val resp = client.get("/players/Zorak") { header(HttpHeaders.Authorization, authHeader) }
            val body = resp.bodyAsText()
            assertTrue(body.contains("Revoke Staff"), "Should have staff revoke button")
        }

    // ── Staff toggle ──────────────────────────────────────────────────────────

    @Test
    fun `POST staff toggle grants staff to non-staff player`() =
        testAdmin {
            repo.save(offlinePlayerRecord("Mira", isStaff = false))
            val resp = client.post("/players/Mira/staff") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.Found, resp.status)
            val updated = repo.findByName("Mira")
            assertTrue(updated?.isStaff == true, "Player should have been granted staff")
        }

    @Test
    fun `POST staff toggle revokes staff from staff player`() =
        testAdmin {
            repo.save(offlinePlayerRecord("Gorn", isStaff = true))
            client.post("/players/Gorn/staff") { header(HttpHeaders.Authorization, authHeader) }
            val updated = repo.findByName("Gorn")
            assertFalse(updated?.isStaff == true, "Player staff should have been revoked")
        }

    @Test
    fun `POST staff toggle for unknown player returns 404`() =
        testAdmin {
            val resp = client.post("/players/nobody/staff") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    // ── JSON API - players ─────────────────────────────────────────────────────

    @Test
    fun `GET api players returns JSON array of online players`() =
        testAdmin {
            val resp = client.get("/api/players") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val node = json.readTree(resp.bodyAsText())
            assertTrue(node.isArray)
        }

    @Test
    fun `GET api player detail for unknown returns 404`() =
        testAdmin {
            val resp = client.get("/api/players/nobody") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `GET api player detail for repo player returns correct JSON`() =
        testAdmin {
            repo.save(offlinePlayerRecord("Lexa", level = 7, isStaff = true))
            val resp = client.get("/api/players/Lexa") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val node = json.readTree(resp.bodyAsText())
            assertEquals("Lexa", node["name"].textValue())
            assertEquals(7, node["level"].intValue())
            assertTrue(node["isStaff"].booleanValue())
            assertFalse(node["isOnline"].booleanValue())
        }

    // ── World page ────────────────────────────────────────────────────────────

    @Test
    fun `GET world page returns 200 with zone table`() =
        testAdmin {
            val resp = client.get("/world") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(resp.bodyAsText().contains("testzone"))
        }

    @Test
    fun `GET world zone detail returns 200 with room rows`() =
        testAdmin {
            val resp = client.get("/world/testzone") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("testzone:start"), "Should show room ID")
            assertTrue(body.contains("Test Start Room"), "Should show room title")
        }

    @Test
    fun `GET world zone detail for unknown zone returns 404`() =
        testAdmin {
            val resp = client.get("/world/nozone") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    // ── JSON API - world ──────────────────────────────────────────────────────

    @Test
    fun `GET api world zones returns JSON zone list`() =
        testAdmin {
            val resp = client.get("/api/world/zones") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val node = json.readTree(resp.bodyAsText())
            assertTrue(node.isArray)
            assertEquals(1, node.size())
            assertEquals("testzone", node[0]["name"].textValue())
            assertEquals(1, node[0]["roomCount"].intValue())
        }

    @Test
    fun `GET api world zone detail returns rooms`() =
        testAdmin {
            val resp = client.get("/api/world/zones/testzone") { header(HttpHeaders.Authorization, authHeader) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val node = json.readTree(resp.bodyAsText())
            assertEquals("testzone", node["name"].textValue())
            val rooms = node["rooms"]
            assertTrue(rooms.isArray)
            assertEquals(1, rooms.size())
            assertEquals("testzone:start", rooms[0]["id"].textValue())
            assertEquals("Test Start Room", rooms[0]["title"].textValue())
        }

    // ── XSS safety ───────────────────────────────────────────────────────────

    @Test
    fun `HTML pages escape special characters in player names`() =
        testAdmin {
            repo.save(offlinePlayerRecord("<script>alert(1)</script>"))
            val resp =
                client.get("/players?q=${java.net.URLEncoder.encode("<script>alert(1)</script>", "UTF-8")}") {
                    header(HttpHeaders.Authorization, authHeader)
                }
            val body = resp.bodyAsText()
            assertFalse(body.contains("<script>"), "Raw script tag must not appear in HTML")
            assertTrue(body.contains("&lt;script&gt;"), "Script tag should be HTML-escaped")
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun offlinePlayerRecord(
        name: String,
        level: Int = 1,
        isStaff: Boolean = false,
    ): PlayerRecord =
        PlayerRecord(
            id = PlayerId(System.nanoTime()),
            name = name,
            roomId = roomId,
            level = level,
            isStaff = isStaff,
            createdAtEpochMs = 0L,
            lastSeenEpochMs = 0L,
        )
}
