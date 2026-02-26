package dev.ambon.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.ambon.config.AdminConfig
import dev.ambon.domain.world.World
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.persistence.PlayerRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.Base64

private val log = KotlinLogging.logger {}

class AdminHttpServer(
    private val config: AdminConfig,
    private val players: PlayerRegistry,
    private val playerRepo: PlayerRepository,
    private val mobs: MobRegistry,
    private val world: World,
    private val metricsUrl: String = "",
) {
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val json: ObjectMapper = jacksonObjectMapper()

    fun start() {
        engine =
            embeddedServer(Netty, port = config.port) {
                adminModule(
                    token = config.token,
                    players = players,
                    playerRepo = playerRepo,
                    mobs = mobs,
                    world = world,
                    grafanaUrl = config.grafanaUrl,
                    metricsUrl = metricsUrl,
                    json = json,
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
    val strength: Int,
    val dexterity: Int,
    val constitution: Int,
    val intelligence: Int,
    val wisdom: Int,
    val charisma: Int,
    val activeTitle: String?,
    val activeQuestIds: List<String>,
    val completedQuestIds: List<String>,
    val achievementIds: List<String>,
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
) {
    routing {
        // ── Overview ─────────────────────────────────────────────────────────
        get("/") {
            if (!call.requireBasicAuth(token)) return@get
            val online = players.allPlayers()
            val zones = world.rooms.keys.mapTo(mutableSetOf()) { it.zone }
            val mobCount = mobs.all().size
            val zoneSummaries =
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
                            append("<td><a href=\"/players/${p.name.esc()}\">${p.name.esc()}</a>")
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
                        append("<td><a href=\"/world/${zone.name.esc()}\">${zone.name.esc()}</a></td>")
                        append("<td>${zone.playersOnline}</td>")
                        append("<td>${zone.mobsAlive}</td>")
                        append("<td>${zone.roomCount}</td>")
                        append("</tr>")
                    }
                    append("</table>")
                    append("</div>")
                }
            call.respondText(htmlPage("Overview", body), ContentType.Text.Html)
        }

        // ── Players list ──────────────────────────────────────────────────────
        get("/players") {
            if (!call.requireBasicAuth(token)) return@get
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
                        PlayerListItemDto(
                            name = ps.name,
                            level = ps.level,
                            playerClass = ps.playerClass,
                            race = ps.race,
                            room = ps.roomId.value,
                            isOnline = true,
                            isStaff = ps.isStaff,
                            hp = ps.hp,
                            maxHp = ps.maxHp,
                        )
                    } else {
                        val record = playerRepo.findByName(query)
                        record?.let {
                            PlayerListItemDto(
                                name = it.name,
                                level = it.level,
                                playerClass = it.playerClass,
                                race = it.race,
                                room = it.roomId.value,
                                isOnline = false,
                                isStaff = it.isStaff,
                                hp = 0,
                                maxHp = 0,
                            )
                        }
                    }
                } else {
                    null
                }
            val body =
                buildString {
                    append("<h1>Players</h1>")
                    append("<form method=\"get\" action=\"/players\" class=\"search-row\">")
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
                                .map { p ->
                                    PlayerListItemDto(
                                        name = p.name,
                                        level = p.level,
                                        playerClass = p.playerClass,
                                        race = p.race,
                                        room = p.roomId.value,
                                        isOnline = true,
                                        isStaff = p.isStaff,
                                        hp = p.hp,
                                        maxHp = p.maxHp,
                                    )
                                }
                                .sortedWith(playerComparator(sort))
                        append(playerRowsHtml(items))
                    }
                }
            call.respondText(htmlPage("Players", body), ContentType.Text.Html)
        }

        // ── Player detail ─────────────────────────────────────────────────────
        get("/players/{name}") {
            if (!call.requireBasicAuth(token)) return@get
            val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val ps = players.allPlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }
            val dto: PlayerDetailDto? =
                if (ps != null) {
                    PlayerDetailDto(
                        name = ps.name,
                        level = ps.level,
                        playerClass = ps.playerClass,
                        race = ps.race,
                        room = ps.roomId.value,
                        isOnline = true,
                        isStaff = ps.isStaff,
                        hp = ps.hp,
                        maxHp = ps.maxHp,
                        mana = ps.mana,
                        maxMana = ps.maxMana,
                        xpTotal = ps.xpTotal,
                        gold = ps.gold,
                        strength = ps.strength,
                        dexterity = ps.dexterity,
                        constitution = ps.constitution,
                        intelligence = ps.intelligence,
                        wisdom = ps.wisdom,
                        charisma = ps.charisma,
                        activeTitle = ps.activeTitle,
                        activeQuestIds = ps.activeQuests.keys.sorted(),
                        completedQuestIds = ps.completedQuestIds.sorted(),
                        achievementIds = ps.unlockedAchievementIds.sorted(),
                    )
                } else {
                    val record = playerRepo.findByName(name)
                    record?.let {
                        PlayerDetailDto(
                            name = it.name,
                            level = it.level,
                            playerClass = it.playerClass,
                            race = it.race,
                            room = it.roomId.value,
                            isOnline = false,
                            isStaff = it.isStaff,
                            hp = 0,
                            maxHp = 0,
                            mana = it.mana,
                            maxMana = it.maxMana,
                            xpTotal = it.xpTotal,
                            gold = it.gold,
                            strength = it.strength,
                            dexterity = it.dexterity,
                            constitution = it.constitution,
                            intelligence = it.intelligence,
                            wisdom = it.wisdom,
                            charisma = it.charisma,
                            activeTitle = it.activeTitle,
                            activeQuestIds = it.activeQuests.keys.sorted(),
                            completedQuestIds = it.completedQuestIds.sorted(),
                            achievementIds = it.unlockedAchievementIds.sorted(),
                        )
                    }
                }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val body =
                buildString {
                    append("<p><a href=\"/players\">← Players</a></p>")
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
                    appendDlRow("Strength", dto.strength.toString())
                    appendDlRow("Dexterity", dto.dexterity.toString())
                    appendDlRow("Constitution", dto.constitution.toString())
                    appendDlRow("Intelligence", dto.intelligence.toString())
                    appendDlRow("Wisdom", dto.wisdom.toString())
                    appendDlRow("Charisma", dto.charisma.toString())
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
                    append("<form method=\"post\" action=\"/players/${dto.name.esc()}/staff\" class=\"inline\">")
                    append("<button class=\"$staffClass\" type=\"submit\">$staffLabel</button>")
                    append("</form>")
                    if (!dto.isOnline) {
                        append(" <small style=\"color:#888\">(takes effect on next login)</small>")
                    }
                    append("</div>")
                }
            call.respondText(htmlPage(dto.name, body), ContentType.Text.Html)
        }

        // ── Toggle staff ───────────────────────────────────────────────────────
        post("/players/{name}/staff") {
            if (!call.requireBasicAuth(token)) return@post
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
            call.respondRedirect("/players/${record.name}")
        }

        // ── World inspector ───────────────────────────────────────────────────
        get("/world") {
            if (!call.requireBasicAuth(token)) return@get
            val query = call.request.queryParameters["q"]?.trim()?.lowercase() ?: ""
            val roomsByZone = world.rooms.keys.groupBy { it.zone }
            val body =
                buildString {
                    append("<h1>World</h1>")
                    append("<form method=\"get\" action=\"/world\" class=\"search-row\">")
                    append("<input type=\"text\" name=\"q\" placeholder=\"Filter zones\" value=\"${query.esc()}\">")
                    append("<button type=\"submit\">Filter</button>")
                    append("</form>")
                    append("<table>")
                    append("<tr><th>Zone</th><th>Rooms</th><th>Players Online</th><th>Mobs Alive</th></tr>")
                    val filtered =
                        roomsByZone.entries
                            .sortedBy { it.key }
                            .filter { query.isBlank() || it.key.lowercase().contains(query) }
                    for ((zone, rooms) in filtered) {
                        val zonePlayers = players.playersInZone(zone).size
                        val zoneMobs = rooms.sumOf { roomId -> mobs.mobsInRoom(roomId).size }
                        append("<tr>")
                        append("<td><a href=\"/world/${zone.esc()}\">${zone.esc()}</a></td>")
                        append("<td>${rooms.size}</td>")
                        append("<td>$zonePlayers</td>")
                        append("<td>$zoneMobs</td>")
                        append("</tr>")
                    }
                    if (filtered.isEmpty()) {
                        append("<tr><td colspan=\"4\" class=\"muted\">No zones matched that filter.</td></tr>")
                    }
                    append("</table>")
                }
            call.respondText(htmlPage("World", body), ContentType.Text.Html)
        }

        // ── Zone detail ───────────────────────────────────────────────────────
        get("/world/{zone}") {
            if (!call.requireBasicAuth(token)) return@get
            val zone = call.parameters["zone"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val zoneRooms = world.rooms.filter { it.key.zone == zone }
            if (zoneRooms.isEmpty()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val body =
                buildString {
                    append("<p><a href=\"/world\">← World</a></p>")
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
            call.respondText(htmlPage("Zone: $zone", body), ContentType.Text.Html)
        }

        // ── JSON API ──────────────────────────────────────────────────────────

        get("/api/overview") {
            if (!call.requireBasicAuth(token)) return@get
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
            if (!call.requireBasicAuth(token)) return@get
            val items =
                players
                    .allPlayers()
                    .sortedBy { it.name }
                    .map { p ->
                        PlayerListItemDto(
                            name = p.name,
                            level = p.level,
                            playerClass = p.playerClass,
                            race = p.race,
                            room = p.roomId.value,
                            isOnline = true,
                            isStaff = p.isStaff,
                            hp = p.hp,
                            maxHp = p.maxHp,
                        )
                    }
            call.respondText(json.writeValueAsString(items), ContentType.Application.Json)
        }

        get("/api/players/{name}") {
            if (!call.requireBasicAuth(token)) return@get
            val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val ps = players.allPlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }
            val dto: PlayerDetailDto? =
                if (ps != null) {
                    PlayerDetailDto(
                        name = ps.name,
                        level = ps.level,
                        playerClass = ps.playerClass,
                        race = ps.race,
                        room = ps.roomId.value,
                        isOnline = true,
                        isStaff = ps.isStaff,
                        hp = ps.hp,
                        maxHp = ps.maxHp,
                        mana = ps.mana,
                        maxMana = ps.maxMana,
                        xpTotal = ps.xpTotal,
                        gold = ps.gold,
                        strength = ps.strength,
                        dexterity = ps.dexterity,
                        constitution = ps.constitution,
                        intelligence = ps.intelligence,
                        wisdom = ps.wisdom,
                        charisma = ps.charisma,
                        activeTitle = ps.activeTitle,
                        activeQuestIds = ps.activeQuests.keys.sorted(),
                        completedQuestIds = ps.completedQuestIds.sorted(),
                        achievementIds = ps.unlockedAchievementIds.sorted(),
                    )
                } else {
                    val record = playerRepo.findByName(name)
                    record?.let {
                        PlayerDetailDto(
                            name = it.name,
                            level = it.level,
                            playerClass = it.playerClass,
                            race = it.race,
                            room = it.roomId.value,
                            isOnline = false,
                            isStaff = it.isStaff,
                            hp = 0,
                            maxHp = 0,
                            mana = it.mana,
                            maxMana = it.maxMana,
                            xpTotal = it.xpTotal,
                            gold = it.gold,
                            strength = it.strength,
                            dexterity = it.dexterity,
                            constitution = it.constitution,
                            intelligence = it.intelligence,
                            wisdom = it.wisdom,
                            charisma = it.charisma,
                            activeTitle = it.activeTitle,
                            activeQuestIds = it.activeQuests.keys.sorted(),
                            completedQuestIds = it.completedQuestIds.sorted(),
                            achievementIds = it.unlockedAchievementIds.sorted(),
                        )
                    }
                }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(json.writeValueAsString(dto), ContentType.Application.Json)
        }

        get("/api/world/zones") {
            if (!call.requireBasicAuth(token)) return@get
            val roomsByZone = world.rooms.keys.groupBy { it.zone }
            val zones =
                roomsByZone.entries.sortedBy { it.key }.map { (zone, rooms) ->
                    ZoneInfoDto(
                        name = zone,
                        roomCount = rooms.size,
                        playersOnline = players.playersInZone(zone).size,
                        mobsAlive = rooms.sumOf { roomId -> mobs.mobsInRoom(roomId).size },
                    )
                }
            call.respondText(json.writeValueAsString(zones), ContentType.Application.Json)
        }

        get("/api/world/zones/{zone}") {
            if (!call.requireBasicAuth(token)) return@get
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
    }
}

// --- Auth helper ---

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
        if (password == token) return true
    }
    response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"AmbonMUD Admin\", charset=\"UTF-8\"")
    respond(HttpStatusCode.Unauthorized)
    return false
}

// --- HTML helpers ---

private fun htmlPage(
    title: String,
    body: String,
): String =
    """
    <!DOCTYPE html>
    <html lang="en">
    <head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
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
      <a href="/">Overview</a>
      <a href="/players">Players</a>
      <a href="/world">World</a>
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
            append("<td><a href=\"/players/${p.name.esc()}\">${p.name.esc()}</a>")
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
