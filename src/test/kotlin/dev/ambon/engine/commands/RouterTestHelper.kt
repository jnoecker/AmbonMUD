package dev.ambon.engine.commands

import dev.ambon.bus.OutboundBus
import dev.ambon.config.EconomyConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.commands.handlers.AdminHandler
import dev.ambon.engine.commands.handlers.CombatHandler
import dev.ambon.engine.commands.handlers.CommunicationHandler
import dev.ambon.engine.commands.handlers.DialogueQuestHandler
import dev.ambon.engine.commands.handlers.GroupHandler
import dev.ambon.engine.commands.handlers.ItemHandler
import dev.ambon.engine.commands.handlers.NavigationHandler
import dev.ambon.engine.commands.handlers.ProgressionHandler
import dev.ambon.engine.commands.handlers.ShopHandler
import dev.ambon.engine.commands.handlers.UiHandler
import dev.ambon.engine.commands.handlers.WorldFeaturesHandler
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.PlayerLocationIndex

/**
 * Builds a fully-wired [CommandRouter] suitable for use in unit tests.
 *
 * Optional parameters mirror the optional constructor parameters of the individual handler classes.
 * Handlers that don't need certain deps simply receive [null] by default.
 */
internal fun buildTestRouter(
    world: World,
    players: PlayerRegistry,
    mobs: MobRegistry,
    items: ItemRegistry,
    combat: CombatSystem,
    outbound: OutboundBus,
    progression: PlayerProgression = PlayerProgression(),
    groupSystem: GroupSystem? = null,
    onShutdown: suspend () -> Unit = {},
    onMobSmited: (MobId) -> Unit = {},
    interEngineBus: InterEngineBus? = null,
    playerLocationIndex: PlayerLocationIndex? = null,
    engineId: String = "",
    onRemoteWho: (suspend (SessionId) -> Unit)? = null,
    onPhase: (suspend (SessionId, String?) -> PhaseResult)? = null,
    onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
    worldState: WorldStateRegistry? = null,
    shopRegistry: ShopRegistry? = null,
    economyConfig: EconomyConfig = EconomyConfig(),
): CommandRouter {
    val router = CommandRouter()
    UiHandler(router = router, players = players, outbound = outbound, combat = combat, onPhase = onPhase)
    CommunicationHandler(
        router = router,
        players = players,
        outbound = outbound,
        groupSystem = groupSystem,
        interEngineBus = interEngineBus,
        playerLocationIndex = playerLocationIndex,
        engineId = engineId,
        onRemoteWho = onRemoteWho,
    )
    NavigationHandler(
        router = router,
        world = world,
        players = players,
        mobs = mobs,
        items = items,
        combat = combat,
        outbound = outbound,
        worldState = worldState,
        onCrossZoneMove = onCrossZoneMove,
    )
    CombatHandler(router = router, players = players, mobs = mobs, combat = combat, outbound = outbound)
    ProgressionHandler(
        router = router,
        players = players,
        items = items,
        combat = combat,
        outbound = outbound,
        progression = progression,
        groupSystem = groupSystem,
    )
    ItemHandler(router = router, players = players, items = items, combat = combat, outbound = outbound)
    ShopHandler(
        router = router,
        players = players,
        items = items,
        outbound = outbound,
        shopRegistry = shopRegistry,
        economyConfig = economyConfig,
    )
    DialogueQuestHandler(router = router, players = players, mobs = mobs, outbound = outbound)
    GroupHandler(router = router, outbound = outbound, groupSystem = groupSystem)
    WorldFeaturesHandler(router = router, world = world, players = players, items = items, outbound = outbound, worldState = worldState)
    AdminHandler(
        router = router,
        world = world,
        players = players,
        mobs = mobs,
        items = items,
        combat = combat,
        outbound = outbound,
        onShutdown = onShutdown,
        onMobSmited = onMobSmited,
        interEngineBus = interEngineBus,
        engineId = engineId,
    )
    return router
}
