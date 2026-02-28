package dev.ambon.engine

import dev.ambon.config.EngineConfig
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.ids.RoomId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PlayerRepository
import java.time.Clock
import kotlin.coroutines.CoroutineContext

fun resolveClassStartRooms(engineConfig: EngineConfig): Map<PlayerClass, RoomId> =
    engineConfig.classStartRooms
        .mapNotNull { (key, value) -> PlayerClass.fromString(key)?.to(RoomId(value)) }
        .toMap()

fun createPlayerRegistry(
    startRoom: RoomId,
    engineConfig: EngineConfig,
    repo: PlayerRepository,
    items: ItemRegistry,
    clock: Clock,
    progression: PlayerProgression,
    hashingContext: CoroutineContext,
): PlayerRegistry =
    PlayerRegistry(
        startRoom = startRoom,
        classStartRooms = resolveClassStartRooms(engineConfig),
        repo = repo,
        items = items,
        clock = clock,
        progression = progression,
        hashingContext = hashingContext,
    )
