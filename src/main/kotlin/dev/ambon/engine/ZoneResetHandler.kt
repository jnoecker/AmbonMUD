package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.MobVariantDefinition
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.idZone
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.RoomFeature
import dev.ambon.domain.world.ScalingMode
import dev.ambon.domain.world.World
import dev.ambon.domain.world.resolveMobStats
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import java.time.Clock

/** BFS from [start], staying within the same zone. Returns room → hop distance. */
internal fun computeDistanceMap(start: RoomId, world: World): Map<RoomId, Int> {
    val zone = start.zone
    val distances = mutableMapOf(start to 0)
    val queue = ArrayDeque<RoomId>()
    queue.add(start)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val dist = distances[current]!!
        val room = world.rooms[current] ?: continue
        for (exit in room.exits.values) {
            if (exit.zone != zone) continue
            if (exit in distances) continue
            distances[exit] = dist + 1
            queue.add(exit)
        }
    }
    return distances
}

/**
 * Converts a [MobSpawn] placement into a live [MobState] instance, reading
 * stats from the matching [dev.ambon.domain.world.MobTemplateDef].
 *
 * When [referencePlayerLevel] is supplied and the mob's zone uses a non-STATIC
 * scaling mode, the template level and tier-derived stats are recomputed for
 * that player level. Authored overrides still win. Pass null (or nothing) to
 * use the authored level — the normal path for zones without scaling.
 *
 * Throws [IllegalStateException] if [spawn]'s template id is unknown to [world];
 * the loader's invariant is that every spawn has a corresponding template.
 */
internal fun spawnToMobState(
    spawn: MobSpawn,
    world: World,
    referencePlayerLevel: Int? = null,
    variant: RolledMobVariant? = null,
): MobState {
    val template = world.mobTemplate(spawn.templateId)
        ?: error("No template '${spawn.templateId.value}' for spawn '${spawn.id.value}'")
    val zone = idZone(spawn.id.value)
    val scaling = world.zoneScaling(zone)

    val shouldRescale = scaling.mode != ScalingMode.STATIC && template.tier != null
    val effectiveLevel =
        if (shouldRescale) scaling.resolveLevel(referencePlayerLevel, template.level) else template.level
    val resolved =
        if (shouldRescale && effectiveLevel != template.level && template.tier != null) {
            resolveMobStats(template.tier, effectiveLevel, template.overrides)
        } else {
            null
        }

    val baseHp = resolved?.hp ?: template.maxHp
    val damage = resolved?.damage ?: template.damage
    val armor = resolved?.armor ?: template.armor
    val baseXp = resolved?.xpReward ?: template.xpReward
    val baseGoldMin = resolved?.goldMin ?: template.goldMin
    val baseGoldMax = resolved?.goldMax ?: template.goldMax

    // Apply the rare-variant overlay: a name prefix, cosmetic tint/overlay, and
    // a modest HP/XP/loot bump. Ordinary mobs (variant == null) are unchanged.
    val def = variant?.def
    val name = (def?.namePrefix ?: "") + template.name
    val maxHp = scaleStat(baseHp, def?.hpMultiplier)
    val xpReward = scaleStat(baseXp, def?.xpMultiplier)
    val goldMin = scaleStat(baseGoldMin, def?.lootMultiplier)
    val goldMax = scaleStat(baseGoldMax, def?.lootMultiplier)
    val drops =
        if (def == null || def.lootMultiplier == 1.0) {
            template.drops
        } else {
            template.drops.map { it.copy(chance = (it.chance * def.lootMultiplier).coerceAtMost(1.0)) }
        }

    return MobState(
        id = spawn.id,
        name = name,
        description = template.description,
        roomId = spawn.roomId,
        hp = maxHp,
        maxHp = maxHp,
        damage = damage,
        armor = armor,
        xpReward = xpReward,
        drops = drops,
        goldMin = goldMin,
        goldMax = goldMax,
        dialogue = template.dialogue,
        behaviorTree = template.behaviorTree,
        aggressive = template.aggressive,
        templateKey = spawn.templateId.value,
        spawnRoomId = spawn.roomId,
        spawnDistanceMap = computeDistanceMap(spawn.roomId, world),
        questIds = template.questIds,
        image = template.image,
        video = template.video,
        category = template.category,
        spells = template.spells,
        defaultAttack = template.defaultAttack,
        level = effectiveLevel,
        role = template.role,
        variantId = variant?.id,
        variantName = def?.displayName?.takeIf { it.isNotEmpty() },
        tint = def?.tint?.takeIf { it.isNotEmpty() },
        overlay = def?.overlay?.takeIf { it.isNotEmpty() },
    )
}

private fun scaleStat(base: Int, multiplier: Double?): Int =
    if (multiplier == null || multiplier == 1.0) base else Math.round(base * multiplier).toInt()

private fun scaleStat(base: Long, multiplier: Double?): Long =
    if (multiplier == null || multiplier == 1.0) base else Math.round(base * multiplier)

/**
 * Broadcasts the arrival of a rare variant [mob], with reach scaled by the
 * variant's configured [MobVariantDefinition.announce] loudness (ROOM/ZONE/SERVER).
 */
internal suspend fun announceMobVariant(
    outbound: OutboundBus,
    players: PlayerRegistry,
    mob: MobState,
    def: MobVariantDefinition,
) {
    val zone = mob.roomId.zone
    when (def.announce) {
        "SERVER" -> {
            val msg = "[Legendary] A ${mob.name} has appeared somewhere in $zone — a sighting for the ages!"
            for (p in players.allPlayers()) outbound.send(OutboundEvent.SendInfo(p.sessionId, msg))
        }
        "ZONE" -> {
            val msg = "A rare creature stirs nearby — ${mob.name} has appeared!"
            for (p in players.playersInZone(zone)) outbound.send(OutboundEvent.SendInfo(p.sessionId, msg))
        }
        else -> {
            val msg = "${mob.name} shimmers into view, unmistakably rare."
            for (p in players.playersInRoom(mob.roomId)) outbound.send(OutboundEvent.SendInfo(p.sessionId, msg))
        }
    }
}

/**
 * Returns the highest-level player currently in any room of [zone], or null
 * if nobody's there. Used as the reference-player input to spawn-time scaling.
 */
internal fun highestPlayerLevelInZone(players: PlayerRegistry, zone: String): Int? =
    players
        .allPlayers()
        .filter { it.roomId.zone == zone }
        .maxOfOrNull { it.level }

/**
 * Handles periodic zone resets: tracks per-zone lifespan timers, removes stale mobs/items,
 * re-spawns them from world definitions, and resets stateful room features.
 */
internal class ZoneResetHandler(
    private val world: World,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val worldState: WorldStateRegistry,
    private val mobRemovalCoordinator: MobRemovalCoordinator,
    private val mobSystem: MobSystem,
    private val behaviorTreeSystem: BehaviorTreeSystem,
    private val gmcpEmitter: GmcpEmitter,
    private val clock: Clock,
    private val variantRoller: MobVariantRoller,
    /**
     * Whether a mob is currently fighting a player. Mobs in combat survive
     * zone resets (#1222); wired to [CombatSystem.isMobInCombat] by GameEngine.
     */
    private val isMobInCombat: (MobId) -> Boolean = { false },
) {
    private val zoneResetDueAtMillis: MutableMap<String, Long> =
        world.zoneLifespansMinutes
            .filterValues { it > 0L }
            .mapValuesTo(mutableMapOf()) { (_, minutes) -> clock.millis() + minutesToMillis(minutes) }

    /** Tracks the lifespan values that produced the current timers, so [refreshSchedule] can detect changes. */
    private val previousLifespanMinutes: MutableMap<String, Long> =
        world.zoneLifespansMinutes
            .filterValues { it > 0L }
            .toMutableMap()

    /** Returns the set of zones that currently have active reset timers. Visible for testing. */
    internal fun scheduledZones(): Set<String> = zoneResetDueAtMillis.keys.toSet()

    /** Returns the due-at millis for [zone], or null if not scheduled. Visible for testing. */
    internal fun dueAtMillis(zone: String): Long? = zoneResetDueAtMillis[zone]

    /**
     * Syncs [zoneResetDueAtMillis] with the current [world.zoneLifespansMinutes].
     * - New zones get a timer starting at now + lifespan.
     * - Removed/disabled zones (lifespan absent or ≤ 0) have their timer dropped.
     * - Changed lifespans reset the timer to now + new lifespan.
     * - Unchanged lifespans keep the existing timer undisturbed.
     *
     * Call after a hot reload replaces world data.
     */
    fun refreshSchedule() {
        val now = clock.millis()
        val fresh = world.zoneLifespansMinutes.filterValues { it > 0L }

        // Remove zones that no longer have a lifespan.
        val staleZones = zoneResetDueAtMillis.keys - fresh.keys
        for (zone in staleZones) {
            zoneResetDueAtMillis.remove(zone)
        }

        // Add or update zones.
        for ((zone, minutes) in fresh) {
            val oldMinutes = previousLifespanMinutes[zone]
            if (zone !in zoneResetDueAtMillis || oldMinutes != minutes) {
                zoneResetDueAtMillis[zone] = now + minutesToMillis(minutes)
            }
        }

        previousLifespanMinutes.clear()
        previousLifespanMinutes.putAll(fresh)
    }

    /** Called once per tick; resets any zones whose lifespan has elapsed. */
    suspend fun tick() {
        if (zoneResetDueAtMillis.isEmpty()) return

        val now = clock.millis()
        for ((zone, dueAtMillis) in zoneResetDueAtMillis) {
            if (now < dueAtMillis) continue

            resetZone(zone)

            val lifespanMinutes = world.zoneLifespansMinutes[zone] ?: continue
            val lifespanMillis = minutesToMillis(lifespanMinutes)
            val elapsedCycles = ((now - dueAtMillis) / lifespanMillis) + 1
            zoneResetDueAtMillis[zone] = dueAtMillis + (elapsedCycles * lifespanMillis)
        }
    }

    private suspend fun resetZone(zone: String) {
        val playersInZone = players.allPlayers().filter { player -> player.roomId.zone == zone }
        for (player in playersInZone) {
            outbound.send(OutboundEvent.SendText(player.sessionId, "The air shimmers as the area resets around you."))
        }

        val zoneRoomIds =
            world.rooms.keys
                .filterTo(linkedSetOf()) { roomId -> roomId.zone == zone }

        val zoneMobSpawns =
            world.mobSpawns
                .filter { spawn -> idZone(spawn.id.value) == zone }
        val activeZoneMobIds =
            mobs
                .all()
                .map { mob -> mob.id }
                .filter { mobId -> idZone(mobId.value) == zone }

        val zoneMobIds =
            (zoneMobSpawns.map { spawn -> spawn.id } + activeZoneMobIds)
                .toSet()

        // Mobs actively fighting a player survive the reset (#1222): yanking an
        // opponent mid-swing forfeits kill credit and feels broken. Their spawn
        // slots are skipped too — when the fight ends, the normal post-death
        // respawn timer (or the next reset, once out of combat) refills the slot.
        val survivingMobIds = zoneMobIds.filterTo(hashSetOf()) { mobId -> isMobInCombat(mobId) }

        for (mobId in zoneMobIds) {
            if (mobId in survivingMobIds) continue
            mobRemovalCoordinator.removeMobExternally(mobId)
        }

        val referenceLevel = highestPlayerLevelInZone(players, zone)
        val freshVariants = mutableListOf<Pair<MobState, MobVariantDefinition>>()
        for (spawn in zoneMobSpawns) {
            if (spawn.id in survivingMobIds) continue
            val template = world.mobTemplate(spawn.templateId)
            val variant = template?.let { variantRoller.roll(it) }
            val mob = spawnToMobState(spawn, world, referenceLevel, variant)
            mobs.upsert(mob)
            mobSystem.onMobSpawned(spawn.id)
            behaviorTreeSystem.onMobSpawned(spawn.id)
            if (variant != null) freshVariants.add(mob to variant.def)
        }

        val zoneItemSpawns =
            world.itemSpawns
                .filter { spawn -> idZone(spawn.instance.id.value) == zone }

        items.resetZone(
            zone = zone,
            roomIds = zoneRoomIds,
            // Surviving mobs keep their carried loot for the imminent kill.
            mobIds = zoneMobIds - survivingMobIds,
            spawns = zoneItemSpawns,
        )

        // Reset stateful room features (doors, containers, levers) for this zone.
        worldState.resetZone(zone)
        for (room in world.rooms.values.filter { it.id.zone == zone }) {
            for (feature in room.features.filterIsInstance<RoomFeature.Container>()) {
                if (!feature.resetWithZone) continue
                val instances = feature.initialItems.mapNotNull { items.createFromTemplate(it) }
                worldState.resetContainer(feature.id, instances)
            }
        }

        // Refresh mob GMCP for all players in the reset zone.
        for (player in playersInZone) {
            gmcpEmitter.sendRoomMobs(player.sessionId, mobs.mobsInRoom(player.roomId))
            gmcpEmitter.sendRoomItems(player.sessionId, items.itemsInRoom(player.roomId))
        }

        // Announce any rare variants the reset rolled into existence.
        for ((mob, def) in freshVariants) {
            announceMobVariant(outbound, players, mob, def)
        }
    }
}

private fun minutesToMillis(minutes: Long): Long = minutes * 60_000L
