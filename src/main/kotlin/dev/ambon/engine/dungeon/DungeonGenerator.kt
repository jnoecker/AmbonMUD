package dev.ambon.engine.dungeon

import dev.ambon.domain.dungeon.DungeonRoomTemplateDef
import dev.ambon.domain.dungeon.DungeonRoomType
import dev.ambon.domain.dungeon.DungeonTemplateDef
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.opposite
import kotlin.random.Random

/**
 * Generates a [DungeonLayout] from a [DungeonTemplateDef].
 *
 * Layout structure: a linear backbone (entrance → corridors/chambers → boss)
 * with ~30% chance of a side branch (1-2 rooms) at each chamber.
 */
class DungeonGenerator(
    private val random: Random = Random.Default,
) {
    fun generate(template: DungeonTemplateDef): DungeonLayout {
        val targetRoomCount = random.nextInt(template.roomCountMin, template.roomCountMax + 1)

        // Build backbone: entrance → mixed corridors/chambers → boss
        val rooms = mutableListOf<MutableDungeonRoom>()
        val backboneDirections = listOf(Direction.NORTH, Direction.EAST, Direction.NORTH, Direction.WEST)

        // Entrance
        rooms.add(makeRoom(0, DungeonRoomType.ENTRANCE, template))
        var backboneCount = 1

        // Fill backbone until we're close to target, leaving room for boss
        while (backboneCount < targetRoomCount - 1) {
            val type = if (backboneCount % 3 == 0) DungeonRoomType.CHAMBER else DungeonRoomType.CORRIDOR
            val idx = rooms.size
            rooms.add(makeRoom(idx, type, template))

            // Link from previous backbone room
            val prevIdx = findLastBackboneIndex(rooms, idx)
            val dir = backboneDirections[(backboneCount - 1) % backboneDirections.size]
            linkRooms(rooms, prevIdx, idx, dir)
            backboneCount++

            // ~30% chance of a side branch at chambers (if we have room budget)
            if (type == DungeonRoomType.CHAMBER && backboneCount < targetRoomCount - 2 && random.nextDouble() < 0.30) {
                val branchType = if (random.nextDouble() < 0.4) DungeonRoomType.TREASURE else DungeonRoomType.CORRIDOR
                val branchIdx = rooms.size
                val branchRoom = makeRoom(branchIdx, branchType, template)
                branchRoom.isBackbone = false
                rooms.add(branchRoom)
                val branchDir = pickBranchDirection(rooms[idx], dir)
                linkRooms(rooms, idx, branchIdx, branchDir)
                backboneCount++ // counts toward total

                // 50% chance of extending the branch one more room
                if (backboneCount < targetRoomCount - 1 && random.nextDouble() < 0.5) {
                    val ext = rooms.size
                    val extType = if (branchType == DungeonRoomType.TREASURE) {
                        DungeonRoomType.CORRIDOR
                    } else {
                        DungeonRoomType.TREASURE
                    }
                    val extRoom = makeRoom(ext, extType, template)
                    extRoom.isBackbone = false
                    rooms.add(extRoom)
                    linkRooms(rooms, branchIdx, ext, backboneDirections[random.nextInt(backboneDirections.size)])
                    backboneCount++
                }
            }
        }

        // Boss room at the end
        val bossIdx = rooms.size
        rooms.add(makeRoom(bossIdx, DungeonRoomType.BOSS, template))
        val lastBackbone = findLastBackboneIndex(rooms, bossIdx)
        val bossDir = backboneDirections[(backboneCount - 1) % backboneDirections.size]
        linkRooms(rooms, lastBackbone, bossIdx, bossDir)

        // Assign mobs
        val layout = rooms.map { room ->
            DungeonLayoutRoom(
                index = room.index,
                type = room.type,
                title = room.title,
                description = room.description,
                image = room.image,
                exits = room.exits.toMap(),
                mobIds = assignMobs(room.type, template),
            )
        }

        return DungeonLayout(
            rooms = layout,
            entranceIndex = 0,
            bossIndex = bossIdx,
        )
    }

    private data class MutableDungeonRoom(
        val index: Int,
        val type: DungeonRoomType,
        val title: String,
        val description: String,
        val image: String?,
        val exits: MutableMap<Direction, Int> = mutableMapOf(),
        var isBackbone: Boolean = true,
    )

    private fun makeRoom(
        index: Int,
        type: DungeonRoomType,
        template: DungeonTemplateDef,
    ): MutableDungeonRoom {
        val pool = template.roomTemplates[type]
            ?: template.roomTemplates[DungeonRoomType.CORRIDOR]
            ?: listOf(DungeonRoomTemplateDef("Room $index", "A dungeon room."))
        val chosen = pool[random.nextInt(pool.size)]
        return MutableDungeonRoom(
            index = index,
            type = type,
            title = chosen.title,
            description = chosen.description,
            image = chosen.image,
        )
    }

    private fun linkRooms(
        rooms: MutableList<MutableDungeonRoom>,
        fromIdx: Int,
        toIdx: Int,
        direction: Direction,
    ) {
        // If direction is taken, try alternatives
        val dir = if (rooms[fromIdx].exits.containsKey(direction)) {
            Direction.entries.firstOrNull { !rooms[fromIdx].exits.containsKey(it) } ?: direction
        } else {
            direction
        }
        rooms[fromIdx].exits[dir] = toIdx
        rooms[toIdx].exits[dir.opposite()] = fromIdx
    }

    private fun findLastBackboneIndex(rooms: List<MutableDungeonRoom>, beforeIdx: Int): Int {
        for (i in (beforeIdx - 1) downTo 0) {
            if (rooms[i].isBackbone) return i
        }
        return 0
    }

    private fun pickBranchDirection(room: MutableDungeonRoom, backboneDir: Direction): Direction {
        // Pick a direction that isn't the backbone direction or its opposite
        val candidates = Direction.entries.filter { it != backboneDir && it != backboneDir.opposite() && !room.exits.containsKey(it) }
        return if (candidates.isNotEmpty()) candidates[random.nextInt(candidates.size)] else Direction.SOUTH
    }

    private fun assignMobs(type: DungeonRoomType, template: DungeonTemplateDef): List<String> {
        val pools = template.mobPools
        return when (type) {
            DungeonRoomType.ENTRANCE -> emptyList()
            DungeonRoomType.CORRIDOR -> {
                if (pools.common.isEmpty()) {
                    emptyList()
                } else {
                    val count = random.nextInt(1, 3)
                    (0 until count).map { pools.common[random.nextInt(pools.common.size)] }
                }
            }
            DungeonRoomType.CHAMBER -> {
                val commonCount = random.nextInt(1, 4)
                val mobs = mutableListOf<String>()
                if (pools.common.isNotEmpty()) {
                    repeat(commonCount) { mobs.add(pools.common[random.nextInt(pools.common.size)]) }
                }
                if (pools.elite.isNotEmpty() && random.nextDouble() < 0.4) {
                    mobs.add(pools.elite[random.nextInt(pools.elite.size)])
                }
                mobs
            }
            DungeonRoomType.TREASURE -> {
                if (pools.elite.isNotEmpty()) {
                    listOf(pools.elite[random.nextInt(pools.elite.size)])
                } else {
                    emptyList()
                }
            }
            DungeonRoomType.BOSS -> {
                if (pools.boss.isEmpty()) {
                    emptyList()
                } else {
                    listOf(pools.boss[random.nextInt(pools.boss.size)])
                }
            }
        }
    }
}
