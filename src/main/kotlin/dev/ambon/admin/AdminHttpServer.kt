package dev.ambon.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.config.AdminConfig
import dev.ambon.config.HousingConfig
import dev.ambon.domain.achievement.AchievementDef
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.world.World
import dev.ambon.engine.AchievementRegistry
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.QuestRegistry
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.persistence.HouseRepository
import dev.ambon.persistence.PlayerRecord
import dev.ambon.persistence.PlayerRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

class AdminHttpServer(
    private val config: AdminConfig,
    private val players: PlayerRegistry,
    private val playerRepo: PlayerRepository,
    private val mobs: MobRegistry,
    private val world: World,
    private val metricsUrl: String = "",
    private val onReload: (suspend (String?) -> String)? = null,
    private val onBroadcast: (suspend (String) -> Int)? = null,
    private val abilityRegistry: AbilityRegistry? = null,
    private val statusEffectRegistry: StatusEffectRegistry? = null,
    private val questRegistry: QuestRegistry? = null,
    private val achievementRegistry: AchievementRegistry? = null,
    private val shopRegistry: ShopRegistry? = null,
    private val houseRepo: HouseRepository? = null,
    private val housingConfig: HousingConfig? = null,
    private val startTime: Long = System.currentTimeMillis(),
) {
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val json: ObjectMapper = jacksonObjectMapper()

    fun start() {
        engine =
            embeddedServer(Netty, host = config.host, port = config.port) {
                adminModule(
                    token = config.token,
                    players = players,
                    playerRepo = playerRepo,
                    mobs = mobs,
                    world = world,
                    grafanaUrl = config.grafanaUrl,
                    metricsUrl = metricsUrl,
                    json = json,
                    onReload = onReload,
                    onBroadcast = onBroadcast,
                    abilityRegistry = abilityRegistry,
                    statusEffectRegistry = statusEffectRegistry,
                    questRegistry = questRegistry,
                    achievementRegistry = achievementRegistry,
                    shopRegistry = shopRegistry,
                    houseRepo = houseRepo,
                    housingConfig = housingConfig,
                    corsOrigins = config.corsOrigins,
                    startTime = startTime,
                    basePath = config.basePath,
                )
            }.start(wait = false)
        log.info { "Admin HTTP server started on port ${config.port}" }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        engine = null
    }
}

// --- JSON response DTOs ---

private data class OverviewDto(
    val playersOnline: Int,
    val mobsAlive: Int,
    val zonesLoaded: Int,
    val roomsTotal: Int,
    val grafanaUrl: String,
    val metricsUrl: String,
)

private data class PlayerListItemDto(
    val name: String,
    val level: Int,
    val playerClass: String,
    val race: String,
    val room: String,
    val isOnline: Boolean,
    val isStaff: Boolean,
    val hp: Int,
    val maxHp: Int,
)

private data class PlayerDetailDto(
    val name: String,
    val level: Int,
    val playerClass: String,
    val race: String,
    val room: String,
    val isOnline: Boolean,
    val isStaff: Boolean,
    val hp: Int,
    val maxHp: Int,
    val mana: Int,
    val maxMana: Int,
    val xpTotal: Long,
    val gold: Long,
    val stats: Map<String, Int>,
    val activeTitle: String?,
    val activeQuestIds: List<String>,
    val completedQuestIds: List<String>,
    val achievementIds: List<String>,
)

private fun PlayerState.toListItemDto() =
    toListItemDto(
        name = name,
        level = level,
        playerClass = playerClass,
        race = race,
        room = roomId.value,
        isStaff = isStaff,
        isOnline = true,
        hp = hp,
        maxHp = maxHp,
    )

private fun PlayerRecord.toListItemDto() =
    toListItemDto(
        name = name,
        level = level,
        playerClass = playerClass,
        race = race,
        room = roomId.value,
        isStaff = isStaff,
        isOnline = false,
        hp = 0,
        maxHp = 0,
    )

private fun toListItemDto(
    name: String,
    level: Int,
    playerClass: String,
    race: String,
    room: String,
    isStaff: Boolean,
    isOnline: Boolean,
    hp: Int,
    maxHp: Int,
) = PlayerListItemDto(
    name = name,
    level = level,
    playerClass = playerClass,
    race = race,
    room = room,
    isOnline = isOnline,
    isStaff = isStaff,
    hp = hp,
    maxHp = maxHp,
)

private fun PlayerState.toDetailDto() =
    toDetailDto(
        name = name,
        level = level,
        playerClass = playerClass,
        race = race,
        room = roomId.value,
        isStaff = isStaff,
        mana = mana,
        maxMana = maxMana,
        xpTotal = xpTotal,
        gold = gold,
        stats = stats.values,
        activeTitle = activeTitle,
        activeQuestIds = activeQuests.keys.sorted(),
        completedQuestIds = completedQuestIds.sorted(),
        achievementIds = unlockedAchievementIds.sorted(),
        isOnline = true,
        hp = hp,
        maxHp = maxHp,
    )

private fun PlayerRecord.toDetailDto() =
    toDetailDto(
        name = name,
        level = level,
        playerClass = playerClass,
        race = race,
        room = roomId.value,
        isStaff = isStaff,
        mana = mana,
        maxMana = maxMana,
        xpTotal = xpTotal,
        gold = gold,
        stats = stats,
        activeTitle = activeTitle,
        activeQuestIds = activeQuests.keys.sorted(),
        completedQuestIds = completedQuestIds.sorted(),
        achievementIds = unlockedAchievementIds.sorted(),
        isOnline = false,
        hp = 0,
        maxHp = 0,
    )

private fun toDetailDto(
    name: String,
    level: Int,
    playerClass: String,
    race: String,
    room: String,
    isStaff: Boolean,
    mana: Int,
    maxMana: Int,
    xpTotal: Long,
    gold: Long,
    stats: Map<String, Int>,
    activeTitle: String?,
    activeQuestIds: List<String>,
    completedQuestIds: List<String>,
    achievementIds: List<String>,
    isOnline: Boolean,
    hp: Int,
    maxHp: Int,
) = PlayerDetailDto(
    name = name,
    level = level,
    playerClass = playerClass,
    race = race,
    room = room,
    isOnline = isOnline,
    isStaff = isStaff,
    hp = hp,
    maxHp = maxHp,
    mana = mana,
    maxMana = maxMana,
    xpTotal = xpTotal,
    gold = gold,
    stats = stats,
    activeTitle = activeTitle,
    activeQuestIds = activeQuestIds,
    completedQuestIds = completedQuestIds,
    achievementIds = achievementIds,
)

private data class ZoneInfoDto(
    val name: String,
    val roomCount: Int,
    val playersOnline: Int,
    val mobsAlive: Int,
)

private data class RoomInfoDto(
    val id: String,
    val title: String,
    val exits: List<String>,
    val players: List<String>,
    val mobs: List<String>,
)

private data class ZoneDetailDto(
    val name: String,
    val rooms: List<RoomInfoDto>,
)

private fun buildZoneInfoDtos(
    world: World,
    players: PlayerRegistry,
    mobs: MobRegistry,
): List<ZoneInfoDto> =
    world.rooms.keys
        .groupBy { it.zone }
        .entries
        .map { (zone, rooms) ->
            ZoneInfoDto(
                name = zone,
                roomCount = rooms.size,
                playersOnline = players.playersInZone(zone).size,
                mobsAlive = rooms.sumOf { roomId -> mobs.mobsInRoom(roomId).size },
            )
        }
        .sortedBy { it.name }

// --- Ktor module ---

internal fun Application.adminModule(
    token: String,
    players: PlayerRegistry,
    playerRepo: PlayerRepository,
    mobs: MobRegistry,
    world: World,
    grafanaUrl: String = "",
    metricsUrl: String = "",
    json: ObjectMapper,
    onReload: (suspend (String?) -> String)? = null,
    onBroadcast: (suspend (String) -> Int)? = null,
    abilityRegistry: AbilityRegistry? = null,
    statusEffectRegistry: StatusEffectRegistry? = null,
    questRegistry: QuestRegistry? = null,
    achievementRegistry: AchievementRegistry? = null,
    shopRegistry: ShopRegistry? = null,
    houseRepo: HouseRepository? = null,
    housingConfig: HousingConfig? = null,
    corsOrigins: List<String> = emptyList(),
    startTime: Long = System.currentTimeMillis(),
    basePath: String = "/",
) {
    // Guard: prevents concurrent hot-reload operations
    val reloadInProgress = AtomicBoolean(false)

    // Simple rate limiter: tracks last execution time per operation type.
    // Uses System.currentTimeMillis() since this is admin HTTP code, not engine code.
    val rateLimitTimestamps = ConcurrentHashMap<String, Long>()

    /**
     * Returns true if the operation should be rejected due to rate limiting.
     * [operationKey] identifies the operation type, [cooldownMs] is the minimum interval.
     */
    fun isRateLimited(operationKey: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = rateLimitTimestamps[operationKey]
        if (last != null && (now - last) < cooldownMs) return true
        rateLimitTimestamps[operationKey] = now
        return false
    }

    routing {
        intercept(ApplicationCallPipeline.Plugins) {
            // CORS preflight requests skip auth
            if (call.request.local.method == HttpMethod.Options) {
                if (corsOrigins.isNotEmpty()) {
                    call.applyCorsHeaders(corsOrigins)
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
                finish()
                return@intercept
            }
            if (!call.requireBasicAuth(token)) {
                finish()
                return@intercept
            }
            if (corsOrigins.isNotEmpty()) {
                call.applyCorsHeaders(corsOrigins)
            }
        }

        // ── Overview ─────────────────────────────────────────────────────────
        get("/") {
            val online = players.allPlayers()
            val zones = world.rooms.keys.mapTo(mutableSetOf()) { it.zone }
            val mobCount = mobs.all().size
            val zoneSummaries =
                buildZoneInfoDtos(world, players, mobs)
                    .sortedWith(compareByDescending<ZoneInfoDto> { it.playersOnline }.thenBy { it.name })
            val body =
                buildString {
                    append("<h1>Overview</h1>")
                    append("<div class=\"stats\">")
                    appendStatCard("Players Online", online.size.toString())
                    appendStatCard("Staff Online", online.count { it.isStaff }.toString())
                    appendStatCard("Mobs Alive", mobCount.toString())
                    appendStatCard("Zones", zones.size.toString())
                    appendStatCard("Rooms", world.rooms.size.toString())
                    append("</div>")
                    if (grafanaUrl.isNotBlank()) {
                        append("<p><a class=\"link-btn\" href=\"${grafanaUrl.esc()}\" target=\"_blank\">Open Grafana</a></p>")
                    }
                    if (metricsUrl.isNotBlank()) {
                        append("<p><a class=\"link-btn\" href=\"${metricsUrl.esc()}\" target=\"_blank\">Prometheus Metrics</a></p>")
                    }
                    if (online.isEmpty()) {
                        append("<p>No players currently online.</p>")
                    } else {
                        append("<h2>Online Players</h2>")
                        append("<table><tr><th>Name</th><th>Level</th><th>Class</th><th>Room</th><th>HP</th></tr>")
                        for (p in online.sortedBy { it.name }) {
                            append("<tr>")
                            append("<td><a href=\"players/${p.name.esc()}\">${p.name.esc()}</a>")
                            if (p.isStaff) append(" <span class=\"badge badge-staff\">staff</span>")
                            append("</td>")
                            append("<td>${p.level}</td>")
                            append("<td>${p.playerClass.esc()}</td>")
                            append("<td>${p.roomId.value.esc()}</td>")
                            append("<td>${p.hp}/${p.maxHp}</td>")
                            append("</tr>")
                        }
                        append("</table>")
                    }
                    append("<div class=\"section\">")
                    append("<h2>Zone Activity</h2>")
                    append("<table><tr><th>Zone</th><th>Players</th><th>Mobs</th><th>Rooms</th></tr>")
                    for (zone in zoneSummaries) {
                        append("<tr>")
                        append("<td><a href=\"world/${zone.name.esc()}\">${zone.name.esc()}</a></td>")
                        append("<td>${zone.playersOnline}</td>")
                        append("<td>${zone.mobsAlive}</td>")
                        append("<td>${zone.roomCount}</td>")
                        append("</tr>")
                    }
                    append("</table>")
                    append("</div>")
                }
            call.respondText(htmlPage("Overview", body, basePath), ContentType.Text.Html)
        }

        // ── Players list ──────────────────────────────────────────────────────
        get("/players") {
            val query = call.request.queryParameters["q"]?.trim() ?: ""
            val onlineOnly = call.request.queryParameters["online"] == "1"
            val staffOnly = call.request.queryParameters["staff"] == "1"
            val sort = call.request.queryParameters["sort"]?.trim()?.lowercase() ?: "name"
            val online = players.allPlayers()
            val onlineNames = online.associateBy { it.name.lowercase() }
            val searched: PlayerListItemDto? =
                if (query.isNotBlank()) {
                    val ps = onlineNames[query.lowercase()]
                    if (ps != null) {
                        ps.toListItemDto()
                    } else {
                        playerRepo.findByName(query)?.toListItemDto()
                    }
                } else {
                    null
                }
            val body =
                buildString {
                    append("<h1>Players</h1>")
                    append("<form method=\"get\" action=\"players\" class=\"search-row\">")
                    append("<input type=\"text\" name=\"q\" placeholder=\"Search by name\" value=\"${query.esc()}\">")
                    append(
                        "<label><input type=\"checkbox\" name=\"online\" value=\"1\"${if (onlineOnly) " checked" else ""}> Online only</label>",
                    )
                    append(
                        "<label><input type=\"checkbox\" name=\"staff\" value=\"1\"${if (staffOnly) " checked" else ""}> Staff only</label>",
                    )
                    append("<label>Sort <select name=\"sort\">")
                    append("<option value=\"name\"${if (sort == "name") " selected" else ""}>Name</option>")
                    append("<option value=\"level\"${if (sort == "level") " selected" else ""}>Level</option>")
                    append("<option value=\"class\"${if (sort == "class") " selected" else ""}>Class</option>")
                    append("</select></label>")
                    append("<button type=\"submit\">Search</button>")
                    append("</form>")
                    append("<p class=\"muted\">Online: ${online.size} • Staff online: ${online.count { it.isStaff }}</p>")
                    if (query.isNotBlank() && searched == null) {
                        append("<p>No player found with name <strong>${query.esc()}</strong>.</p>")
                    }
                    if (searched != null && (!onlineOnly || searched.isOnline) && (!staffOnly || searched.isStaff)) {
                        append("<h2>Search Result</h2>")
                        append(playerRowsHtml(listOf(searched)))
                    }
                    append("<h2>Online Now</h2>")
                    if (online.isEmpty()) {
                        append("<p>No players currently online.</p>")
                    } else {
                        val filteredOnline =
                            online.filter { !staffOnly || it.isStaff }
                        val items =
                            filteredOnline
                                .map { it.toListItemDto() }
                                .sortedWith(playerComparator(sort))
                        append(playerRowsHtml(items))
                    }
                }
            call.respondText(htmlPage("Players", body, basePath), ContentType.Text.Html)
        }

        // ── Player detail ─────────────────────────────────────────────────────
        get("/players/{name}") {
            val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val ps = players.allPlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }
            val dto: PlayerDetailDto? =
                ps?.toDetailDto() ?: playerRepo.findByName(name)?.toDetailDto()
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val body =
                buildString {
                    append("<p><a href=\"players\">← Players</a></p>")
                    append("<h1>${dto.name.esc()}")
                    if (dto.isOnline) append(" <span class=\"badge badge-online\">online</span>")
                    if (dto.isStaff) append(" <span class=\"badge badge-staff\">staff</span>")
                    append("</h1>")
                    append("<div class=\"section\">")
                    append("<div class=\"dl\">")
                    appendDlRow("Level", dto.level.toString())
                    appendDlRow("Class", dto.playerClass)
                    appendDlRow("Race", dto.race)
                    appendDlRow("Room", dto.room)
                    appendDlRow("XP", dto.xpTotal.toString())
                    appendDlRow("Gold", dto.gold.toString())
                    appendDlRow("Active Title", dto.activeTitle ?: "—")
                    append("</div></div>")
                    append("<div class=\"section\">")
                    append("<h2>Stats</h2>")
                    append("<div class=\"dl\">")
                    if (dto.isOnline) {
                        appendDlRow("HP", "${dto.hp}/${dto.maxHp}")
                        appendDlRow("Mana", "${dto.mana}/${dto.maxMana}")
                    }
                    for ((stat, value) in dto.stats) {
                        appendDlRow(stat, value.toString())
                    }
                    append("</div></div>")
                    if (dto.activeQuestIds.isNotEmpty() || dto.completedQuestIds.isNotEmpty()) {
                        append("<div class=\"section\">")
                        append("<h2>Quests</h2>")
                        if (dto.activeQuestIds.isNotEmpty()) {
                            append("<p><strong>Active:</strong> ${dto.activeQuestIds.joinToString(", ") { it.esc() }}</p>")
                        }
                        if (dto.completedQuestIds.isNotEmpty()) {
                            append("<p><strong>Completed:</strong> ${dto.completedQuestIds.joinToString(", ") { it.esc() }}</p>")
                        }
                        append("</div>")
                    }
                    if (dto.achievementIds.isNotEmpty()) {
                        append("<div class=\"section\">")
                        append("<h2>Achievements</h2>")
                        append("<p>${dto.achievementIds.joinToString(", ") { it.esc() }}</p>")
                        append("</div>")
                    }
                    // Staff toggle
                    append("<div class=\"section\">")
                    append("<h2>Admin Actions</h2>")
                    val staffLabel = if (dto.isStaff) "Revoke Staff" else "Grant Staff"
                    val staffClass = if (dto.isStaff) "danger" else ""
                    append("<form method=\"post\" action=\"players/${dto.name.esc()}/staff\" class=\"inline\">")
                    append("<button class=\"$staffClass\" type=\"submit\">$staffLabel</button>")
                    append("</form>")
                    if (!dto.isOnline) {
                        append(" <small style=\"color:#888\">(takes effect on next login)</small>")
                    }
                    append("</div>")
                }
            call.respondText(htmlPage(dto.name, body, basePath), ContentType.Text.Html)
        }

        // ── Toggle staff ───────────────────────────────────────────────────────
        post("/players/{name}/staff") {
            val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            // consume form body (required by Ktor to avoid connection reset)
            runCatching { call.receiveParameters() }
            val record = playerRepo.findByName(name)
            if (record == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            playerRepo.save(record.copy(isStaff = !record.isStaff))
            // If online, update in-memory state immediately
            players
                .allPlayers()
                .firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { it.isStaff = !it.isStaff }
            call.respondRedirect("${basePath}players/${record.name}")
        }

        // ── World inspector ───────────────────────────────────────────────────
        get("/world") {
            val query = call.request.queryParameters["q"]?.trim()?.lowercase() ?: ""
            val allZones = buildZoneInfoDtos(world, players, mobs)
            val filtered = allZones.filter { query.isBlank() || it.name.lowercase().contains(query) }
            val body =
                buildString {
                    append("<h1>World</h1>")
                    append("<form method=\"get\" action=\"world\" class=\"search-row\">")
                    append("<input type=\"text\" name=\"q\" placeholder=\"Filter zones\" value=\"${query.esc()}\">")
                    append("<button type=\"submit\">Filter</button>")
                    append("</form>")
                    append("<table>")
                    append("<tr><th>Zone</th><th>Rooms</th><th>Players Online</th><th>Mobs Alive</th></tr>")
                    for (zone in filtered) {
                        append("<tr>")
                        append("<td><a href=\"world/${zone.name.esc()}\">${zone.name.esc()}</a></td>")
                        append("<td>${zone.roomCount}</td>")
                        append("<td>${zone.playersOnline}</td>")
                        append("<td>${zone.mobsAlive}</td>")
                        append("</tr>")
                    }
                    if (filtered.isEmpty()) {
                        append("<tr><td colspan=\"4\" class=\"muted\">No zones matched that filter.</td></tr>")
                    }
                    append("</table>")
                }
            call.respondText(htmlPage("World", body, basePath), ContentType.Text.Html)
        }

        // ── Zone detail ───────────────────────────────────────────────────────
        get("/world/{zone}") {
            val zone = call.parameters["zone"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val zoneRooms = world.rooms.filter { it.key.zone == zone }
            if (zoneRooms.isEmpty()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val body =
                buildString {
                    append("<p><a href=\"world\">← World</a></p>")
                    append("<h1>Zone: ${zone.esc()}</h1>")
                    append("<table>")
                    append("<tr><th>Room</th><th>Title</th><th>Exits</th><th>Players</th><th>Mobs</th></tr>")
                    for ((roomId, room) in zoneRooms.entries.sortedBy { it.key.value }) {
                        val roomPlayers = players.playersInRoom(roomId).map { it.name }
                        val roomMobs = mobs.mobsInRoom(roomId).map { it.name }
                        append("<tr>")
                        append("<td style=\"font-size:0.85em\">${roomId.value.esc()}</td>")
                        append("<td>${room.title.esc()}</td>")
                        append("<td>${room.exits.keys.joinToString(" ") { it.name.lowercase() }.esc()}</td>")
                        append("<td>${roomPlayers.joinToString(", ") { it.esc() }.ifEmpty { "—" }}</td>")
                        append("<td>${roomMobs.joinToString(", ") { it.esc() }.ifEmpty { "—" }}</td>")
                        append("</tr>")
                    }
                    append("</table>")
                }
            call.respondText(htmlPage("Zone: $zone", body, basePath), ContentType.Text.Html)
        }

        // ── JSON API ──────────────────────────────────────────────────────────

        get("/api/overview") {
            val dto =
                OverviewDto(
                    playersOnline = players.allPlayers().size,
                    mobsAlive = mobs.all().size,
                    zonesLoaded =
                        world.rooms.keys
                            .mapTo(mutableSetOf()) { it.zone }
                            .size,
                    roomsTotal = world.rooms.size,
                    grafanaUrl = grafanaUrl,
                    metricsUrl = metricsUrl,
                )
            call.respondText(json.writeValueAsString(dto), ContentType.Application.Json)
        }

        get("/api/players") {
            val items =
                players
                    .allPlayers()
                    .sortedBy { it.name }
                    .map { it.toListItemDto() }
            call.respondText(json.writeValueAsString(items), ContentType.Application.Json)
        }

        get("/api/players/{name}") {
            val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val ps = players.allPlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }
            val dto: PlayerDetailDto? =
                ps?.toDetailDto() ?: playerRepo.findByName(name)?.toDetailDto()
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(json.writeValueAsString(dto), ContentType.Application.Json)
        }

        get("/api/world/zones") {
            val zones = buildZoneInfoDtos(world, players, mobs)
            call.respondText(json.writeValueAsString(zones), ContentType.Application.Json)
        }

        get("/api/world/zones/{zone}") {
            val zone = call.parameters["zone"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val zoneRooms = world.rooms.filter { it.key.zone == zone }
            if (zoneRooms.isEmpty()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val detail =
                ZoneDetailDto(
                    name = zone,
                    rooms =
                        zoneRooms.entries.sortedBy { it.key.value }.map { (roomId, room) ->
                            RoomInfoDto(
                                id = roomId.value,
                                title = room.title,
                                exits =
                                    room.exits.keys
                                        .map { it.name.lowercase() }
                                        .sorted(),
                                players = players.playersInRoom(roomId).map { it.name }.sorted(),
                                mobs = mobs.mobsInRoom(roomId).map { it.name }.sorted(),
                            )
                        },
                )
            call.respondText(json.writeValueAsString(detail), ContentType.Application.Json)
        }

        // ── Hot Reload API ─────────────────────────────────────────────────
        // Reloads world definitions, ability definitions, and/or status-effect definitions
        // from their source YAML/config. The reload is NOT atomic across subsystems: if the
        // abilities reload succeeds but the world reload fails, the engine may be left with
        // a partially updated state. On failure, the previously loaded state for that
        // subsystem remains intact (each subsystem reload is individually atomic). A
        // concurrent-reload guard prevents overlapping reloads.
        post("/api/reload") {
            if (onReload == null) {
                call.respondJsonError(HttpStatusCode.NotImplemented, "Hot reload not configured")
                return@post
            }
            if (isRateLimited("reload", 30_000L)) {
                call.respondJsonError(HttpStatusCode.TooManyRequests, "Reload rate limited (max 1 per 30 seconds)")
                return@post
            }
            if (!reloadInProgress.compareAndSet(false, true)) {
                call.respondJsonError(HttpStatusCode.Conflict, "Reload already in progress")
                return@post
            }
            try {
                val target = call.request.queryParameters["target"] // world, abilities, effects, all
                val validTargets = setOf("world", "abilities", "effects", "all")
                if (target != null && target !in validTargets) {
                    call.respondJsonError(
                        HttpStatusCode.BadRequest,
                        "Invalid target. Use: ${validTargets.joinToString(", ")}",
                    )
                    return@post
                }
                val summary = onReload.invoke(target)
                call.respondText(
                    json.writeValueAsString(mapOf("status" to "ok", "summary" to summary)),
                    ContentType.Application.Json,
                )
            } catch (e: Exception) {
                log.error(e) { "Hot reload API failed" }
                call.respondText(
                    json.writeValueAsString(mapOf("status" to "error", "message" to (e.message ?: "unknown"))),
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError,
                )
            } finally {
                reloadInProgress.set(false)
            }
        }

        // ── Health ──────────────────────────────────────────────────────────
        get("/api/health") {
            val uptimeMs = System.currentTimeMillis() - startTime
            call.respondText(
                json.writeValueAsString(
                    mapOf(
                        "status" to "ok",
                        "uptimeMs" to uptimeMs,
                        "playersOnline" to players.allPlayers().size,
                    ),
                ),
                ContentType.Application.Json,
            )
        }

        // ── Logs (ring buffer) ─────────────────────────────────────────────
        get("/api/logs") {
            val ringBuffer = LogRingBuffer.instance
            if (ringBuffer == null) {
                call.respondJsonError(HttpStatusCode.ServiceUnavailable, "Log ring buffer not available")
                return@get
            }
            val minLevel = call.request.queryParameters["level"]?.let {
                ch.qos.logback.classic.Level.toLevel(it, null)
            }
            val sinceMs = call.request.queryParameters["since"]?.toLongOrNull()
            val loggerPrefix = call.request.queryParameters["logger"]?.takeIf { it.isNotBlank() }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 500
            val entries = ringBuffer.entries(
                minLevel = minLevel,
                sinceEpochMs = sinceMs,
                loggerPrefix = loggerPrefix,
                limit = limit,
            )
            call.respondText(
                json.writeValueAsString(entries),
                ContentType.Application.Json,
            )
        }

        // ── Staff toggle (JSON) ─────────────────────────────────────────────
        post("/api/players/{name}/staff") {
            if (isRateLimited("staff_toggle", 5_000L)) {
                call.respondJsonError(HttpStatusCode.TooManyRequests, "Staff toggle rate limited (max 1 per 5 seconds)")
                return@post
            }
            val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val record = playerRepo.findByName(name)
            if (record == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val newValue = !record.isStaff
            playerRepo.save(record.copy(isStaff = newValue))
            players
                .allPlayers()
                .firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.let { it.isStaff = newValue }
            call.respondText(
                json.writeValueAsString(
                    mapOf("name" to record.name, "isStaff" to newValue),
                ),
                ContentType.Application.Json,
            )
        }

        // ── Player search (online + offline) ────────────────────────────────
        get("/api/players/search") {
            val query = call.request.queryParameters["q"]?.trim()
            if (query.isNullOrBlank()) {
                call.respondJsonError(HttpStatusCode.BadRequest, "Query parameter 'q' is required")
                return@get
            }
            // Check online first
            val onlineMatch = players.allPlayers()
                .firstOrNull { it.name.equals(query, ignoreCase = true) }
            if (onlineMatch != null) {
                call.respondText(
                    json.writeValueAsString(onlineMatch.toDetailDto()),
                    ContentType.Application.Json,
                )
                return@get
            }
            // Fall back to persistence
            val record = playerRepo.findByName(query)
            if (record != null) {
                call.respondText(
                    json.writeValueAsString(record.toDetailDto()),
                    ContentType.Application.Json,
                )
                return@get
            }
            call.respond(HttpStatusCode.NotFound)
        }

        // ── Room detail ─────────────────────────────────────────────────────
        get("/api/world/zones/{zone}/rooms/{room}") {
            val zone = call.parameters["zone"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val roomLocal = call.parameters["room"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val roomId = RoomId("$zone:$roomLocal")
            val room = world.rooms[roomId]
            if (room == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val roomPlayers = players.playersInRoom(roomId).map { it.name }.sorted()
            val roomMobs = mobs.mobsInRoom(roomId).map { mob ->
                mapOf(
                    "id" to mob.id.value,
                    "name" to mob.name,
                    "hp" to mob.hp,
                    "maxHp" to mob.maxHp,
                    "templateKey" to mob.templateKey,
                )
            }
            val dto = mapOf(
                "id" to roomId.value,
                "title" to room.title,
                "description" to room.description,
                "exits" to room.exits.map { (dir, target) ->
                    mapOf("direction" to dir.name.lowercase(), "target" to target.value)
                },
                "players" to roomPlayers,
                "mobs" to roomMobs,
                "features" to room.features.map { it.toString() },
                "station" to room.station,
                "image" to room.image,
                "video" to room.video,
                "music" to room.music,
                "ambient" to room.ambient,
                "mapX" to room.mapX,
                "mapY" to room.mapY,
            )
            call.respondText(json.writeValueAsString(dto), ContentType.Application.Json)
        }

        // ── Mobs ────────────────────────────────────────────────────────────
        get("/api/mobs") {
            val zone = call.request.queryParameters["zone"]
            val allMobs = mobs.all()
            val filtered = if (zone != null) {
                allMobs.filter { it.roomId.zone == zone }
            } else {
                allMobs
            }
            val dtos = filtered.sortedBy { it.name }.map { it.toMobDto() }
            call.respondText(json.writeValueAsString(dtos), ContentType.Application.Json)
        }

        get("/api/mobs/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val mob = mobs.get(MobId(id))
            if (mob == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(json.writeValueAsString(mob.toMobDto()), ContentType.Application.Json)
        }

        // ── Abilities ───────────────────────────────────────────────────────
        registryListRoute(
            path = "/api/abilities",
            registry = abilityRegistry,
            label = "Ability",
            json = json,
            allFn = { abilityRegistry!!.all() },
            sortKey = { it.displayName },
            toDto = { it.toAbilityDto() },
        )
        registryDetailRoute(
            path = "/api/abilities/{id}",
            registry = abilityRegistry,
            label = "Ability",
            json = json,
            lookupFn = { id -> abilityRegistry!!.get(AbilityId(id)) },
            toDto = { it.toAbilityDto() },
        )

        // ── Status Effects ──────────────────────────────────────────────────
        registryListRoute(
            path = "/api/effects",
            registry = statusEffectRegistry,
            label = "Status effect",
            json = json,
            allFn = { statusEffectRegistry!!.all() },
            sortKey = { it.displayName },
            toDto = { it.toEffectDto() },
        )
        registryDetailRoute(
            path = "/api/effects/{id}",
            registry = statusEffectRegistry,
            label = "Status effect",
            json = json,
            lookupFn = { id -> statusEffectRegistry!!.get(StatusEffectId(id)) },
            toDto = { it.toEffectDto() },
        )

        // ── Quests ──────────────────────────────────────────────────────────
        registryListRoute(
            path = "/api/quests",
            registry = questRegistry,
            label = "Quest",
            json = json,
            allFn = { questRegistry!!.all() },
            sortKey = { it.id },
            toDto = { it.toQuestDto() },
        )
        registryDetailRoute(
            path = "/api/quests/{id}",
            registry = questRegistry,
            label = "Quest",
            json = json,
            lookupFn = { id -> questRegistry!!.get(id) },
            toDto = { it.toQuestDto() },
        )

        // ── Achievements ────────────────────────────────────────────────────
        registryListRoute(
            path = "/api/achievements",
            registry = achievementRegistry,
            label = "Achievement",
            json = json,
            allFn = { achievementRegistry!!.all() },
            sortKey = { it.id },
            toDto = { it.toAchievementDto() },
        )
        registryDetailRoute(
            path = "/api/achievements/{id}",
            registry = achievementRegistry,
            label = "Achievement",
            json = json,
            lookupFn = { id -> achievementRegistry!!.get(id) },
            toDto = { it.toAchievementDto() },
        )

        // ── Shops ───────────────────────────────────────────────────────────
        get("/api/shops") {
            val shops = world.shopDefinitions.sortedBy { it.id }.map { shop ->
                val items = shopRegistry?.shopItems(shop) ?: emptyList()
                mapOf(
                    "id" to shop.id,
                    "name" to shop.name,
                    "roomId" to shop.roomId.value,
                    "items" to items.map { (itemId, item) ->
                        mapOf(
                            "id" to itemId.value,
                            "displayName" to item.displayName,
                            "basePrice" to item.basePrice,
                            "slot" to item.slot?.name?.lowercase(),
                        )
                    },
                )
            }
            call.respondText(json.writeValueAsString(shops), ContentType.Application.Json)
        }

        // ── Items (templates) ───────────────────────────────────────────────
        get("/api/items") {
            val items = world.itemSpawns
                .sortedBy { it.instance.item.displayName }
                .map { spawn ->
                    val item = spawn.instance.item
                    mapOf(
                        "id" to spawn.instance.id.value,
                        "displayName" to item.displayName,
                        "description" to item.description,
                        "slot" to item.slot?.name?.lowercase(),
                        "damage" to item.damage,
                        "armor" to item.armor,
                        "stats" to item.stats.values,
                        "consumable" to item.consumable,
                        "basePrice" to item.basePrice,
                        "image" to item.image,
                        "spawnRoom" to spawn.roomId?.value,
                    )
                }
            call.respondText(json.writeValueAsString(items), ContentType.Application.Json)
        }

        // ── Housing ─────────────────────────────────────────────────────────
        get("/api/housing/templates") {
            if (housingConfig == null || !housingConfig.enabled) {
                call.respondJsonError(HttpStatusCode.NotImplemented, "Housing not configured")
                return@get
            }
            val templates = housingConfig.templates.map { (id, tmpl) ->
                mapOf(
                    "id" to id,
                    "title" to tmpl.title,
                    "description" to tmpl.description,
                    "cost" to tmpl.cost,
                    "isEntry" to tmpl.isEntry,
                    "maxDroppedItems" to tmpl.maxDroppedItems,
                    "safe" to tmpl.safe,
                    "station" to tmpl.station,
                    "image" to tmpl.image,
                )
            }
            call.respondText(json.writeValueAsString(templates), ContentType.Application.Json)
        }

        get("/api/housing") {
            if (houseRepo == null) {
                call.respondJsonError(HttpStatusCode.NotImplemented, "Housing not configured")
                return@get
            }
            // List all houses by scanning online players; for a full list, the repo would need a findAll method.
            val houses = players.allPlayers()
                .filter { it.hasHouse }
                .map { ps ->
                    mapOf(
                        "ownerName" to ps.name,
                        "ownerId" to ps.playerId?.value,
                        "online" to true,
                    )
                }
                .sortedBy { it["ownerName"] as String }
            call.respondText(json.writeValueAsString(houses), ContentType.Application.Json)
        }

        get("/api/housing/{playerName}") {
            if (houseRepo == null) {
                call.respondJsonError(HttpStatusCode.NotImplemented, "Housing not configured")
                return@get
            }
            val name = call.parameters["playerName"]
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val house = houseRepo.findByOwnerName(name)
            if (house == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val dto = mapOf(
                "ownerId" to house.ownerId.value,
                "ownerName" to house.ownerName,
                "createdAtEpochMs" to house.createdAtEpochMs,
                "rooms" to house.rooms.map { room ->
                    mapOf(
                        "templateId" to room.templateId,
                        "customTitle" to room.customTitle,
                        "customDescription" to room.customDescription,
                        "exits" to room.exits.mapKeys { it.key.name },
                        "storedItemCount" to room.storedItems.size,
                    )
                },
            )
            call.respondText(json.writeValueAsString(dto), ContentType.Application.Json)
        }

        // ── Broadcast ───────────────────────────────────────────────────────
        post("/api/broadcast") {
            if (isRateLimited("broadcast", 10_000L)) {
                call.respondJsonError(HttpStatusCode.TooManyRequests, "Broadcast rate limited (max 1 per 10 seconds)")
                return@post
            }
            if (onBroadcast == null) {
                call.respondJsonError(HttpStatusCode.NotImplemented, "Broadcast not configured")
                return@post
            }
            val body = call.receiveText().trim()
            val message = try {
                val node = json.readTree(body)
                node["message"]?.asText()
            } catch (_: Exception) {
                null
            }
            if (message.isNullOrBlank()) {
                call.respondJsonError(HttpStatusCode.BadRequest, "Request body must be JSON with a 'message' field")
                return@post
            }
            val count = onBroadcast.invoke(message)
            call.respondText(
                json.writeValueAsString(mapOf("status" to "ok", "recipients" to count)),
                ContentType.Application.Json,
            )
        }

        // ── CORS preflight catch-all ────────────────────────────────────────
        options("{...}") {
            // Handled by the intercept above; this route just ensures Ktor
            // doesn't return 405 Method Not Allowed for OPTIONS requests.
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// --- Auth helper ---

/**
 * Length-independent constant-time string comparison: both inputs are reduced to fixed-length
 * SHA-256 digests before [MessageDigest.isEqual], so neither the value nor the length of [token]
 * leaks through comparison timing.
 */
private fun constantTimeStringEquals(a: String, b: String): Boolean {
    val da = MessageDigest.getInstance("SHA-256").digest(a.toByteArray(Charsets.UTF_8))
    val db = MessageDigest.getInstance("SHA-256").digest(b.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(da, db)
}

private suspend fun ApplicationCall.requireBasicAuth(token: String): Boolean {
    val header = request.headers[HttpHeaders.Authorization]
    if (header != null && header.startsWith("Basic ")) {
        val decoded =
            try {
                Base64.getDecoder().decode(header.removePrefix("Basic ").trim()).decodeToString()
            } catch (_: IllegalArgumentException) {
                ""
            }
        val colonIdx = decoded.indexOf(':')
        val password = if (colonIdx >= 0) decoded.substring(colonIdx + 1) else decoded
        // Compare fixed-length SHA-256 digests so the timing/early-exit of MessageDigest.isEqual on a
        // length mismatch cannot leak the admin token's length.
        if (constantTimeStringEquals(password, token)) return true
    }
    response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"AmbonMUD Admin\", charset=\"UTF-8\"")
    respond(HttpStatusCode.Unauthorized)
    return false
}

// --- HTML helpers ---

private fun htmlPage(
    title: String,
    body: String,
    basePath: String = "/",
): String =
    """
    <!DOCTYPE html>
    <html lang="en">
    <head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <base href="${basePath.esc()}">
    <title>AmbonMUD Admin — ${title.esc()}</title>
    <style>
    :root{--lavender:#D8C5E8;--pale-blue:#B8D8E8;--dusty-rose:#E8C5D8;--moss-green:#C5D8A8;--soft-gold:#E8D8A8;--deep-mist:#6B6B7B;--soft-fog:#A8A8B8;--cloud:#E8E8F0;--bg-primary:#E8E8F0;--bg-secondary:#F8F8FC;--text-primary:#6B6B7B;--text-secondary:#A8A8B8;--text-disabled:#C8C8D0;--error:#C5A8A8}
    *{box-sizing:border-box}
    body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:linear-gradient(135deg,var(--bg-secondary),var(--bg-primary));color:var(--text-primary);margin:0;padding:0}
    nav{background:linear-gradient(135deg,var(--lavender),rgba(184,216,232,0.1));padding:16px 24px;display:flex;gap:20px;align-items:center;border-bottom:1px solid var(--pale-blue);box-shadow:0 2px 8px rgba(0,0,0,0.08)}
    nav .brand{font-weight:bold;color:var(--deep-mist);font-size:1.1em}
    nav a{color:var(--text-primary);text-decoration:none;transition:color 0.2s}
    nav a:hover{color:var(--dusty-rose);text-decoration:underline}
    main{padding:24px;max-width:1400px;margin:0 auto}
    h1,h2{color:var(--text-primary);margin-top:0;margin-bottom:16px}
    h1{font-size:1.8em;font-weight:600}
    h2{font-size:1.1em;margin-top:20px}
    .stats{display:flex;flex-wrap:wrap;gap:16px;margin-bottom:24px}
    .stat{background:var(--bg-primary);border:1px solid var(--pale-blue);border-radius:8px;padding:16px 20px;min-width:140px;box-shadow:0 1px 2px rgba(0,0,0,0.04);transition:all 0.2s}
    .stat:hover{box-shadow:0 0 16px rgba(216,197,232,0.3);border-color:var(--dusty-rose);transform:translateY(-2px)}
    .stat .label{font-size:0.75em;color:var(--text-secondary);text-transform:uppercase;letter-spacing:0.05em}
    .stat .value{font-size:2em;font-weight:bold;color:var(--deep-mist);margin-top:4px}
    table{border-collapse:collapse;width:100%;margin-bottom:16px}
    th{background:var(--color-primary-lavender);color:var(--text-primary);padding:12px;text-align:left;font-weight:600;border-bottom:1px solid var(--pale-blue)}
    td{padding:12px;border-bottom:1px solid rgba(184,216,232,0.2);vertical-align:top}
    tr:hover td{background:rgba(216,197,232,0.1)}
    .badge{border-radius:12px;padding:4px 8px;font-size:0.75em;font-weight:bold;display:inline-block}
    .badge-staff{background:var(--dusty-rose);color:var(--text-primary)}
    .badge-online{background:var(--moss-green);color:var(--text-primary)}
    a{color:var(--dusty-rose);text-decoration:none;transition:color 0.2s}
    a:hover{color:var(--deep-mist);text-decoration:underline}
    form.inline{display:inline}
    button{background:var(--lavender);color:var(--text-primary);border:1px solid var(--pale-blue);padding:6px 16px;cursor:pointer;border-radius:4px;font-family:inherit;font-weight:500;transition:all 0.2s;box-shadow:0 1px 2px rgba(0,0,0,0.04)}
    button:hover{background:var(--dusty-rose);box-shadow:0 2px 8px rgba(216,197,232,0.3);transform:translateY(-1px)}
    button:active{background:var(--pale-blue);transform:scale(0.98)}
    button.danger{border-color:var(--error);color:var(--error)}
    button.danger:hover{background:var(--error);color:white}
    input[type=text],select{background:white;color:var(--text-primary);border:1px solid var(--pale-blue);padding:8px 12px;border-radius:4px;font-family:inherit;transition:all 0.2s}
    input[type=text]:focus,select:focus{outline:none;border-color:var(--dusty-rose);box-shadow:0 0 0 2px rgba(232,197,216,0.2)}
    input[type=text]{width:240px}
    input[type=checkbox]{width:16px;height:16px;cursor:pointer;vertical-align:middle}
    .search-row{margin-bottom:16px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
    .search-row label{font-size:0.9em;color:var(--text-secondary);display:flex;align-items:center;gap:6px}
    .muted{color:var(--text-secondary)}
    .dl{display:grid;grid-template-columns:max-content 1fr;gap:4px 16px;margin-bottom:8px}
    .dl .key{color:var(--text-secondary);font-size:0.85em;align-self:baseline;padding-top:2px;font-weight:500}
    .section{background:var(--bg-primary);border:1px solid var(--pale-blue);border-radius:8px;padding:16px;margin-bottom:16px;box-shadow:0 1px 2px rgba(0,0,0,0.04)}
    .link-btn{background:var(--lavender);color:var(--text-primary);text-decoration:none;padding:8px 16px;border-radius:4px;display:inline-block;margin-right:8px;margin-bottom:8px;border:1px solid var(--pale-blue);transition:all 0.2s;box-shadow:0 1px 2px rgba(0,0,0,0.04)}
    .link-btn:hover{background:var(--dusty-rose);box-shadow:0 2px 8px rgba(216,197,232,0.3);transform:translateY(-1px)}
    </style>
    </head>
    <body>
    <nav>
      <span class="brand">AmbonMUD</span>
      <a href="./">Overview</a>
      <a href="players">Players</a>
      <a href="world">World</a>
    </nav>
    <main>
    $body
    </main>
    </body>
    </html>
    """.trimIndent()

private fun StringBuilder.appendStatCard(
    label: String,
    value: String,
) {
    append("<div class=\"stat\"><div class=\"label\">${label.esc()}</div>")
    append("<div class=\"value\">${value.esc()}</div></div>")
}

private fun StringBuilder.appendDlRow(
    key: String,
    value: String,
) {
    append("<div class=\"key\">${key.esc()}</div><div>${value.esc()}</div>")
}

private fun playerRowsHtml(items: List<PlayerListItemDto>): String =
    buildString {
        append("<table>")
        append("<tr><th>Name</th><th>Level</th><th>Class</th><th>Race</th><th>Room</th><th>HP</th></tr>")
        for (p in items) {
            append("<tr>")
            append("<td><a href=\"players/${p.name.esc()}\">${p.name.esc()}</a>")
            if (p.isOnline) append(" <span class=\"badge badge-online\">online</span>")
            if (p.isStaff) append(" <span class=\"badge badge-staff\">staff</span>")
            append("</td>")
            append("<td>${p.level}</td>")
            append("<td>${p.playerClass.esc()}</td>")
            append("<td>${p.race.esc()}</td>")
            append("<td>${p.room.esc()}</td>")
            if (p.isOnline) append("<td>${p.hp}/${p.maxHp}</td>") else append("<td>—</td>")
            append("</tr>")
        }
        append("</table>")
    }

private fun playerComparator(sort: String): Comparator<PlayerListItemDto> =
    when (sort) {
        "level" -> compareByDescending<PlayerListItemDto> { it.level }.thenBy { it.name.lowercase() }
        "class" -> compareBy<PlayerListItemDto> { it.playerClass.lowercase() }.thenBy { it.name.lowercase() }
        else -> compareBy<PlayerListItemDto> { it.name.lowercase() }
    }

private fun String.esc(): String =
    this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

// --- Registry route helpers ---

/**
 * Registers a GET route that lists all items from a nullable registry,
 * sorted by [sortKey] and mapped to DTOs via [toDto].
 * Returns 501 if the registry is null.
 */
private fun <D, S : Comparable<S>> Route.registryListRoute(
    path: String,
    registry: Any?,
    label: String,
    json: ObjectMapper,
    allFn: () -> Collection<D>,
    sortKey: (D) -> S,
    toDto: (D) -> Any,
) {
    get(path) {
        if (registry == null) {
            call.respondJsonError(HttpStatusCode.NotImplemented, "$label registry not configured")
            return@get
        }
        val dtos = allFn().sortedBy(sortKey).map(toDto)
        call.respondText(json.writeValueAsString(dtos), ContentType.Application.Json)
    }
}

/**
 * Registers a GET route that looks up a single item by the `{id}` path parameter
 * using [lookupFn]. Returns 501 if the registry is null, 400 if `{id}` is missing,
 * 404 if not found.
 */
private fun <D> Route.registryDetailRoute(
    path: String,
    registry: Any?,
    label: String,
    json: ObjectMapper,
    lookupFn: (String) -> D?,
    toDto: (D) -> Any,
) {
    get(path) {
        if (registry == null) {
            call.respondJsonError(HttpStatusCode.NotImplemented, "$label registry not configured")
            return@get
        }
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val item = lookupFn(id)
        if (item == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(json.writeValueAsString(toDto(item)), ContentType.Application.Json)
    }
}

// --- JSON error helper ---

private val errorJson = jacksonObjectMapper()

private suspend fun ApplicationCall.respondJsonError(
    status: HttpStatusCode,
    error: String,
) {
    respondText(errorJson.writeValueAsString(mapOf("error" to error)), ContentType.Application.Json, status)
}

// --- CORS helper ---

private fun ApplicationCall.applyCorsHeaders(origins: List<String>) {
    val requestOrigin = request.headers[HttpHeaders.Origin] ?: return
    val allowed = origins.any { it == "*" || it.equals(requestOrigin, ignoreCase = true) }
    if (!allowed) return
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, requestOrigin)
    response.headers.append(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type")
    response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
    response.headers.append(HttpHeaders.AccessControlMaxAge, "3600")
}

// --- Content DTO helpers ---

private fun dev.ambon.domain.mob.MobState.toMobDto(): Map<String, Any?> =
    mapOf(
        "id" to id.value,
        "name" to name,
        "roomId" to roomId.value,
        "hp" to hp,
        "maxHp" to maxHp,
        "templateKey" to templateKey,
        "aggressive" to aggressive,
        "xpReward" to xpReward,
        "armor" to armor,
        "image" to image,
        "questIds" to questIds,
        "spawnRoomId" to spawnRoomId?.value,
    )

private fun AbilityDefinition.toAbilityDto(): Map<String, Any?> =
    mapOf(
        "id" to id.value,
        "displayName" to displayName,
        "description" to description,
        "manaCostPct" to manaCostPct,
        "cooldownMs" to cooldownMs,
        "levelRequired" to levelRequired,
        "targetType" to targetType,
        "requiredClass" to requiredClass,
        "image" to image,
        "effectType" to effect::class.simpleName,
    )

private fun StatusEffectDefinition.toEffectDto(): Map<String, Any?> =
    mapOf(
        "id" to id.value,
        "displayName" to displayName,
        "effectType" to effectType,
        "durationMs" to durationMs,
        "tickIntervalMs" to tickIntervalMs,
        "tickMinValue" to tickMinValue,
        "tickMaxValue" to tickMaxValue,
        "shieldAmount" to shieldAmount,
        "statMods" to statMods.values,
        "stackBehavior" to stackBehavior,
        "maxStacks" to maxStacks,
    )

private fun QuestDef.toQuestDto(): Map<String, Any?> =
    mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "giverMobId" to giverMobId,
        "completionType" to completionType,
        "objectives" to objectives.map { obj ->
            mapOf(
                "type" to obj.type,
                "targetId" to obj.targetId,
                "count" to obj.count,
                "description" to obj.description,
            )
        },
        "rewards" to mapOf(
            "xp" to rewards.xp,
            "gold" to rewards.gold,
        ),
    )

private fun AchievementDef.toAchievementDto(): Map<String, Any?> =
    mapOf(
        "id" to id,
        "displayName" to displayName,
        "description" to description,
        "category" to category,
        "hidden" to hidden,
        "criteria" to criteria.map { c ->
            mapOf(
                "type" to c.type,
                "targetId" to c.targetId,
                "count" to c.count,
                "description" to c.description,
            )
        },
        "rewards" to mapOf(
            "xp" to rewards.xp,
            "gold" to rewards.gold,
            "title" to rewards.title,
        ),
    )
