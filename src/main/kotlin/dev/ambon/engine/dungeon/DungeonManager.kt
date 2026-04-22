package dev.ambon.engine.dungeon

import dev.ambon.domain.DamageRange
import dev.ambon.domain.dungeon.DungeonDifficulty
import dev.ambon.domain.dungeon.DungeonTemplateDef
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.MobRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlin.math.roundToInt

private val log = KotlinLogging.logger {}

/**
 * Manages the lifecycle of procedural dungeon instances.
 * Creates rooms in the World, spawns mobs, and cleans up on completion.
 */
class DungeonManager(
    private val world: World,
    private val mobs: MobRegistry,
    private val generator: DungeonGenerator = DungeonGenerator(),
    private val dungeonRegistry: DungeonRegistry,
) {
    /** Active dungeon instances by instance ID. */
    private val instances = mutableMapOf<String, DungeonInstance>()

    /** Maps session ID → active dungeon instance ID. */
    private val playerInstances = mutableMapOf<SessionId, String>()

    fun getInstanceForPlayer(sessionId: SessionId): DungeonInstance? =
        playerInstances[sessionId]?.let { instances[it] }

    fun getInstanceById(id: String): DungeonInstance? = instances[id]

    fun isInDungeon(sessionId: SessionId): Boolean = sessionId in playerInstances

    fun isInDungeon(roomId: RoomId): Boolean =
        instances.values.any { inst -> inst.roomIds.values.any { it == roomId } }

    /**
     * Creates a new dungeon instance from a template.
     * Generates the layout, creates rooms in the World, spawns mobs,
     * and returns the instance (but does NOT teleport players).
     */
    fun createInstance(
        template: DungeonTemplateDef,
        difficulty: DungeonDifficulty,
        leader: SessionId,
        members: Set<SessionId>,
        partyLevel: Int,
        returnRoom: RoomId,
    ): DungeonInstance {
        val instanceId = UUID.randomUUID().toString().take(8)
        val layout = generator.generate(template)
        // Zone prefix must be unique per instance so removeZone doesn't nuke other instances.
        val zonePrefix = "dg_$instanceId"

        // Create rooms in the World
        val roomIdMap = mutableMapOf<Int, RoomId>()
        val worldRooms = mutableMapOf<RoomId, Room>()

        for (room in layout.rooms) {
            val roomId = RoomId("$zonePrefix:r_${room.index}")
            roomIdMap[room.index] = roomId
        }

        for (room in layout.rooms) {
            val roomId = roomIdMap[room.index]!!
            val exits = room.exits.mapValues { (_, targetIdx) -> roomIdMap[targetIdx]!! }
            worldRooms[roomId] = Room(
                id = roomId,
                title = room.title,
                description = room.description,
                exits = exits,
                image = room.image,
            )
        }
        world.putRooms(worldRooms)

        val instance = DungeonInstance(
            id = instanceId,
            template = template,
            difficulty = difficulty,
            layout = layout,
            partyLeader = leader,
            members = members.toMutableSet(),
            partyLevel = partyLevel,
            roomIds = roomIdMap,
            returnRoom = returnRoom,
        )
        instances[instanceId] = instance

        // Register all members
        for (sid in members) {
            playerInstances[sid] = instanceId
        }

        // Spawn mobs — if this fails, clean up the partially-created instance
        // (rooms already added to the World, player mappings already registered).
        try {
            spawnDungeonMobs(instance)
        } catch (e: Exception) {
            log.error(e) { "Failed to spawn mobs for dungeon instance $instanceId, cleaning up" }
            destroyInstance(instanceId)
            throw e
        }

        log.info { "Dungeon instance created: id=$instanceId template=${template.name} difficulty=$difficulty rooms=${layout.rooms.size}" }
        return instance
    }

    /** Returns the entrance RoomId for a dungeon instance. */
    fun entranceRoom(instance: DungeonInstance): RoomId =
        instance.roomIds[instance.layout.entranceIndex]!!

    /** Marks a dungeon as complete (boss killed). */
    fun markComplete(instance: DungeonInstance) {
        instance.completed = true
        log.info { "Dungeon completed: id=${instance.id} template=${instance.template.name}" }
    }

    /** Checks if a mob ID is the boss of any active dungeon. */
    fun findInstanceByBossMob(mobId: MobId): DungeonInstance? {
        for (inst in instances.values) {
            if (inst.completed) continue
            val bossRoomId = inst.roomIds[inst.layout.bossIndex] ?: continue
            val mobsInBoss = mobs.mobsInRoom(bossRoomId)
            if (mobsInBoss.any { it.id == mobId }) return inst
        }
        return null
    }

    /**
     * Removes a player from their active dungeon instance.
     * If all members leave, destroys the instance.
     * Returns the return room for teleporting.
     */
    fun removePlayer(sessionId: SessionId): RoomId? {
        val instanceId = playerInstances.remove(sessionId) ?: return null
        val instance = instances[instanceId] ?: return null
        instance.members.remove(sessionId)

        if (instance.members.isEmpty()) {
            destroyInstance(instanceId)
        }

        return instance.returnRoom
    }

    /** Destroys a dungeon instance: removes all mobs and rooms from the World. */
    fun destroyInstance(instanceId: String) {
        val instance = instances.remove(instanceId) ?: return

        // Remove players from tracking
        for (sid in instance.members) {
            playerInstances.remove(sid)
        }

        // Remove spawned mobs
        for (mobId in instance.spawnedMobs) {
            mobs.remove(mobId)
        }

        val zonePrefix = instance.roomIds.values.firstOrNull()?.zone
        if (zonePrefix != null) {
            world.removeZone(zonePrefix)
        }

        log.info { "Dungeon instance destroyed: id=$instanceId template=${instance.template.name}" }
    }

    /** Spawns mobs for a dungeon instance with difficulty + level scaling. */
    private fun spawnDungeonMobs(instance: DungeonInstance) {
        val template = instance.template
        val diff = instance.difficulty
        val levelScale = instance.partyLevel

        for (room in instance.layout.rooms) {
            val roomId = instance.roomIds[room.index] ?: continue
            for (mobTemplateId in room.mobIds) {
                // Find the mob spawn from the world's mob spawns (same zone as the template)
                val templateZone = template.id.substringBefore(':')
                val fullMobId = if (':' in mobTemplateId) mobTemplateId else "$templateZone:$mobTemplateId"
                val mobSpawn = world.mobSpawns.firstOrNull { it.id.value == fullMobId }
                if (mobSpawn == null) {
                    log.warn { "Dungeon mob template not found: $fullMobId" }
                    continue
                }

                // Create a unique mob ID for this instance
                val uniqueId = MobId("${instance.roomIds.values.first().zone}:${mobTemplateId}_${UUID.randomUUID().toString().take(6)}")

                // Scale stats based on difficulty and party level
                val scaledHp = (mobSpawn.maxHp * diff.hpMultiplier * levelScaleFactor(levelScale, mobSpawn)).roundToInt()
                val scaledMinDmg = (mobSpawn.damage.min * diff.damageMultiplier * levelScaleFactor(levelScale, mobSpawn)).roundToInt()
                val scaledMaxDmg = (mobSpawn.damage.max * diff.damageMultiplier * levelScaleFactor(levelScale, mobSpawn)).roundToInt()
                val scaledXp = (mobSpawn.xpReward * diff.xpMultiplier).toLong()

                val mob = MobState(
                    id = uniqueId,
                    name = mobSpawn.name,
                    description = mobSpawn.description,
                    roomId = roomId,
                    hp = scaledHp,
                    maxHp = scaledHp,
                    damage = DamageRange(scaledMinDmg.coerceAtLeast(1), scaledMaxDmg.coerceAtLeast(1)),
                    armor = mobSpawn.armor,
                    xpReward = scaledXp,
                    drops = mobSpawn.drops.map { drop ->
                        drop.copy(chance = (drop.chance * diff.dropRateMultiplier).coerceAtMost(1.0))
                    },
                    goldMin = mobSpawn.goldMin,
                    goldMax = mobSpawn.goldMax,
                    dialogue = mobSpawn.dialogue,
                    behaviorTree = mobSpawn.behaviorTree,
                    aggressive = true,
                    templateKey = fullMobId,
                    spawnRoomId = roomId,
                    questIds = mobSpawn.questIds,
                    image = mobSpawn.image,
                    video = mobSpawn.video,
                    category = mobSpawn.category,
                    level = mobSpawn.level,
                )

                mobs.upsert(mob)
                instance.spawnedMobs.add(uniqueId)
            }
        }
    }

    /** Level scale factor: scales mob stats relative to party level vs mob's base. */
    private fun levelScaleFactor(partyLevel: Int, spawn: MobSpawn): Double {
        // Simple linear scaling: each party level adds ~10% to base stats
        return (1.0 + (partyLevel - 1) * 0.1).coerceAtLeast(0.5)
    }

    fun activeInstances(): Collection<DungeonInstance> = instances.values

    fun clear() {
        instances.clear()
        playerInstances.clear()
    }
}
