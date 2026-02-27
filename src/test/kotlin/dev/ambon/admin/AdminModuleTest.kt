package dev.ambon.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.persistence.PlayerId
import dev.ambon.persistence.PlayerRecord
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URLEncoder
import java.util.Base64
import io.ktor.server.testing.TestApplication as buildTestApplication

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminModuleTest {
    private val token = "super-secret-token"
    private val authHeader = "Basic " + Base64.getEncoder().encodeToString("admin:$token".toByteArray())

    private val roomId = RoomId("testzone:start")
    private val room = Room(id = roomId, title = "Test Start Room", description = "A test room.", exits = emptyMap())
    private val world = World(rooms = mapOf(roomId to room), startRoom = roomId)
    private val repo = InMemoryPlayerRepository()
    private val mobs = MobRegistry()
    private val players = dev.ambon.test.buildTestPlayerRegistry(startRoom = roomId, repo = repo, items = ItemRegistry())
    private val json = jacksonObjectMapper()
    private lateinit var app: TestApplication

    @BeforeAll
    fun startApp() =
        runBlocking {
            app =
                buildTestApplication {
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
                }
            app.start()
        }

    @AfterAll
    fun stopApp() =
        runBlocking {
            app.stop()
        }

    @BeforeEach
    fun resetRepo() {
        repo.clear()
    }

    @Test
    fun `authentication is enforced on admin endpoints`() {
        val noAuth = get("/", auth = null)
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)

        val badAuth = "Basic " + Base64.getEncoder().encodeToString("admin:wrong".toByteArray())
        val wrongToken = get("/", auth = badAuth)
        assertEquals(HttpStatusCode.Unauthorized, wrongToken.status)

        val ok = get("/")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(bodyText(ok).contains("Overview"))
    }

    @Test
    fun `overview endpoints expose summary stats and configured links`() {
        val overview = get("/")
        assertEquals(HttpStatusCode.OK, overview.status)
        val html = bodyText(overview)
        assertTrue(html.contains("AmbonMUD"))
        assertTrue(html.contains("Zones"))
        assertTrue(html.contains("Rooms"))
        assertTrue(html.contains("Zone Activity"))
        assertTrue(html.contains("Staff Online"))
        assertTrue(html.contains("http://grafana.example.com"))

        val api = getJson("/api/overview")
        assertEquals(1, api["roomsTotal"].intValue())
        assertEquals(1, api["zonesLoaded"].intValue())
        assertEquals(0, api["playersOnline"].intValue())
        assertEquals("http://grafana.example.com", api["grafanaUrl"].textValue())
    }

    @Test
    fun `player pages api and staff actions work for offline players`() {
        savePlayer(offlinePlayerRecord("Lexa", level = 7, isStaff = true))
        savePlayer(offlinePlayerRecord("Mira", isStaff = false))
        savePlayer(offlinePlayerRecord("Gorn", isStaff = true))

        val playersPage = get("/players")
        assertEquals(HttpStatusCode.OK, playersPage.status)
        val playersHtml = bodyText(playersPage)
        assertTrue(playersHtml.contains("Players"))
        assertTrue(playersHtml.contains("Online only"))
        assertTrue(playersHtml.contains("Staff only"))
        assertTrue(playersHtml.contains("Sort"))

        val missingSearch = get("/players?q=nobody")
        assertEquals(HttpStatusCode.OK, missingSearch.status)
        assertTrue(bodyText(missingSearch).contains("nobody"))

        val foundSearch = get("/players?q=Lexa")
        assertTrue(bodyText(foundSearch).contains("Lexa"))

        val lexaDetail = get("/players/Lexa")
        assertEquals(HttpStatusCode.OK, lexaDetail.status)
        val lexaHtml = bodyText(lexaDetail)
        assertTrue(lexaHtml.contains("Lexa"))
        assertTrue(lexaHtml.contains("Revoke Staff"))

        val missingDetail = get("/players/nobody")
        assertEquals(HttpStatusCode.NotFound, missingDetail.status)

        val grantStaff = post("/players/Mira/staff")
        assertEquals(HttpStatusCode.Found, grantStaff.status)
        assertTrue(findPlayer("Mira")?.isStaff == true)

        val revokeStaff = post("/players/Gorn/staff")
        assertEquals(HttpStatusCode.Found, revokeStaff.status)
        assertFalse(findPlayer("Gorn")?.isStaff == true)

        val missingToggle = post("/players/nobody/staff")
        assertEquals(HttpStatusCode.NotFound, missingToggle.status)

        val playersApi = getJson("/api/players")
        assertTrue(playersApi.isArray)

        val lexaApi = getJson("/api/players/Lexa")
        assertEquals("Lexa", lexaApi["name"].textValue())
        assertEquals(7, lexaApi["level"].intValue())
        assertTrue(lexaApi["isStaff"].booleanValue())
        assertFalse(lexaApi["isOnline"].booleanValue())

        val missingApi = get("/api/players/nobody")
        assertEquals(HttpStatusCode.NotFound, missingApi.status)
    }

    @Test
    fun `world pages and api expose zones rooms and empty states`() {
        val worldPage = get("/world")
        assertEquals(HttpStatusCode.OK, worldPage.status)
        assertTrue(bodyText(worldPage).contains("testzone"))

        val filteredWorld = get("/world?q=missing")
        assertTrue(bodyText(filteredWorld).contains("No zones matched that filter."))

        val zoneDetail = get("/world/testzone")
        assertEquals(HttpStatusCode.OK, zoneDetail.status)
        val zoneHtml = bodyText(zoneDetail)
        assertTrue(zoneHtml.contains("testzone:start"))
        assertTrue(zoneHtml.contains("Test Start Room"))

        val missingZone = get("/world/nozone")
        assertEquals(HttpStatusCode.NotFound, missingZone.status)

        val zonesApi = getJson("/api/world/zones")
        assertTrue(zonesApi.isArray)
        assertEquals(1, zonesApi.size())
        assertEquals("testzone", zonesApi[0]["name"].textValue())
        assertEquals(1, zonesApi[0]["roomCount"].intValue())

        val zoneApi = getJson("/api/world/zones/testzone")
        assertEquals("testzone", zoneApi["name"].textValue())
        assertEquals("testzone:start", zoneApi["rooms"][0]["id"].textValue())
        assertEquals("Test Start Room", zoneApi["rooms"][0]["title"].textValue())
    }

    @Test
    fun `html pages escape special characters in player names`() {
        savePlayer(offlinePlayerRecord("<script>alert(1)</script>"))

        val encoded = URLEncoder.encode("<script>alert(1)</script>", Charsets.UTF_8)
        val response = get("/players?q=$encoded")
        val body = bodyText(response)

        assertFalse(body.contains("<script>"), "Raw script tag must not appear in HTML")
        assertTrue(body.contains("&lt;script&gt;"), "Script tag should be HTML-escaped")
    }

    private fun get(
        path: String,
        auth: String? = authHeader,
    ): HttpResponse =
        runBlocking {
            app.client.get(path) {
                auth?.let { header(HttpHeaders.Authorization, it) }
            }
        }

    private fun post(
        path: String,
        auth: String? = authHeader,
    ): HttpResponse =
        runBlocking {
            app.client.post(path) {
                auth?.let { header(HttpHeaders.Authorization, it) }
            }
        }

    private fun bodyText(response: HttpResponse): String = runBlocking { response.bodyAsText() }

    private fun getJson(path: String): JsonNode = json.readTree(bodyText(get(path)))

    private fun savePlayer(record: PlayerRecord) =
        runBlocking {
            repo.save(record)
        }

    private fun findPlayer(name: String): PlayerRecord? =
        runBlocking {
            repo.findByName(name)
        }

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
