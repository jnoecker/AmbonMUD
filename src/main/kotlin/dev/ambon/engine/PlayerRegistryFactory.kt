package dev.ambon.engine

import dev.ambon.config.EngineConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PlayerRepository
import java.time.Clock
import kotlin.coroutines.CoroutineContext

fun resolveClassStartRooms(engineConfig: EngineConfig): Map<String, RoomId> =
    engineConfig.classStartRooms
        .map { (key, value) -> key.uppercase() to RoomId(value) }
        .toMap()

fun createPlayerRegistry(
    startRoom: RoomId,
    engineConfig: EngineConfig,
    repo: PlayerRepository,
    items: ItemRegistry,
    clock: Clock,
    progression: PlayerProgression,
    hashingContext: CoroutineContext,
    classRegistry: PlayerClassRegistry? = null,
    raceRegistry: RaceRegistry? = null,
    statRegistry: StatRegistry? = null,
): PlayerRegistry =
    PlayerRegistry(
        startRoom = startRoom,
        classStartRooms = resolveClassStartRooms(engineConfig),
        repo = repo,
        items = items,
        clock = clock,
        progression = progression,
        hashingContext = hashingContext,
        classRegistry = classRegistry,
        raceRegistry = raceRegistry,
        statRegistry = statRegistry,
        startingGold = engineConfig.characterCreation.startingGold,
    )
