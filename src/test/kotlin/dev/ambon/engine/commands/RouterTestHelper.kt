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
import dev.ambon.engine.commands.handlers.EngineContext
import dev.ambon.engine.commands.handlers.GroupHandler
import dev.ambon.engine.commands.handlers.ItemHandler
import dev.ambon.engine.commands.handlers.MailHandler
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
    val router = CommandRouter(outbound = outbound, players = players)
    val ctx = EngineContext(
        players = players,
        mobs = mobs,
        world = world,
        items = items,
        outbound = outbound,
        combat = combat,
        gmcpEmitter = null,
        worldState = worldState,
    )
    listOf(
        UiHandler(ctx = ctx, onPhase = onPhase),
        CommunicationHandler(
            ctx = ctx,
            groupSystem = groupSystem,
            interEngineBus = interEngineBus,
            playerLocationIndex = playerLocationIndex,
            engineId = engineId,
            onRemoteWho = onRemoteWho,
        ),
        NavigationHandler(ctx = ctx, onCrossZoneMove = onCrossZoneMove),
        CombatHandler(ctx = ctx),
        ProgressionHandler(ctx = ctx, progression = progression, groupSystem = groupSystem),
        ItemHandler(ctx = ctx),
        ShopHandler(ctx = ctx, shopRegistry = shopRegistry, economyConfig = economyConfig),
        DialogueQuestHandler(ctx = ctx),
        GroupHandler(ctx = ctx, groupSystem = groupSystem),
        WorldFeaturesHandler(ctx = ctx),
        MailHandler(ctx = ctx),
        AdminHandler(
            ctx = ctx,
            onShutdown = onShutdown,
            onMobSmited = onMobSmited,
            interEngineBus = interEngineBus,
            engineId = engineId,
        ),
    ).forEach { it.register(router) }
    return router
}
