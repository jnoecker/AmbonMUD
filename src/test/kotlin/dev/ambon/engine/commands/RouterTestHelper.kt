package dev.ambon.engine.commands

import dev.ambon.bus.OutboundBus
import dev.ambon.config.BankConfig
import dev.ambon.config.EconomyConfig
import dev.ambon.config.PrestigeConfig
import dev.ambon.config.StylistConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.DuelSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.GuildHallSystem
import dev.ambon.engine.GuildSystem
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.MobRemovalCoordinator
import dev.ambon.engine.PetSystem
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PrestigeSystem
import dev.ambon.engine.PuzzleSystem
import dev.ambon.engine.RaceRegistry
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.TradeSystem
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.commands.handlers.AdminHandler
import dev.ambon.engine.commands.handlers.BankHandler
import dev.ambon.engine.commands.handlers.CombatHandler
import dev.ambon.engine.commands.handlers.CommunicationHandler
import dev.ambon.engine.commands.handlers.DialogueQuestHandler
import dev.ambon.engine.commands.handlers.DuelHandler
import dev.ambon.engine.commands.handlers.DungeonHandler
import dev.ambon.engine.commands.handlers.EnchantHandler
import dev.ambon.engine.commands.handlers.EngineContext
import dev.ambon.engine.commands.handlers.GroupHandler
import dev.ambon.engine.commands.handlers.GuildHandler
import dev.ambon.engine.commands.handlers.HousingHandler
import dev.ambon.engine.commands.handlers.ItemHandler
import dev.ambon.engine.commands.handlers.MailHandler
import dev.ambon.engine.commands.handlers.NavigationHandler
import dev.ambon.engine.commands.handlers.PetHandler
import dev.ambon.engine.commands.handlers.PrestigeHandler
import dev.ambon.engine.commands.handlers.ProgressionHandler
import dev.ambon.engine.commands.handlers.PuzzleHandler
import dev.ambon.engine.commands.handlers.ShopHandler
import dev.ambon.engine.commands.handlers.StylistHandler
import dev.ambon.engine.commands.handlers.TradeHandler
import dev.ambon.engine.commands.handlers.UiHandler
import dev.ambon.engine.commands.handlers.WorldFeaturesHandler
import dev.ambon.engine.crafting.EnchantSystem
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.dungeon.DungeonManager
import dev.ambon.engine.dungeon.DungeonRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.PlayerLocationIndex
import java.time.Clock

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
    guildSystem: GuildSystem? = null,
    onShutdown: suspend () -> Unit = {},
    mobRemovalCoordinator: MobRemovalCoordinator? = null,
    interEngineBus: InterEngineBus? = null,
    playerLocationIndex: PlayerLocationIndex? = null,
    engineId: String = "",
    onRemoteWho: (suspend (SessionId) -> Unit)? = null,
    onPhase: (suspend (SessionId, String?) -> PhaseResult)? = null,
    onCrossZoneMove: (suspend (SessionId, RoomId) -> Unit)? = null,
    worldState: WorldStateRegistry? = null,
    clock: Clock = Clock.systemUTC(),
    shopRegistry: ShopRegistry? = null,
    economyConfig: EconomyConfig = EconomyConfig(),
    gatheringRegistry: GatheringRegistry? = null,
    petSystem: PetSystem? = null,
    enchantSystem: EnchantSystem? = null,
    bankConfig: BankConfig? = null,
    stylistConfig: StylistConfig? = null,
    raceRegistry: RaceRegistry? = null,
    duelSystem: DuelSystem? = null,
    tradeSystem: TradeSystem? = null,
    dungeonManager: DungeonManager? = null,
    dungeonRegistry: DungeonRegistry? = null,
    housingSystem: HousingSystem? = null,
    puzzleSystem: PuzzleSystem? = null,
    guildHallSystem: GuildHallSystem? = null,
    gmcpEmitter: GmcpEmitter? = null,
): CommandRouter {
    val router = CommandRouter(outbound = outbound, players = players)
    val ctx = EngineContext(
        players = players,
        mobs = mobs,
        world = world,
        items = items,
        outbound = outbound,
        combat = combat,
        gmcpEmitter = gmcpEmitter,
        worldState = worldState,
        gatheringRegistry = gatheringRegistry,
        shopRegistry = shopRegistry,
        economyConfig = economyConfig,
        raceRegistry = raceRegistry,
        bankConfig = bankConfig ?: BankConfig(),
        stylistConfig = stylistConfig ?: StylistConfig(),
    )
    val puzzleHandler = puzzleSystem?.let { PuzzleHandler(ctx = ctx, puzzleSystem = it) }
    listOfNotNull(
        UiHandler(ctx = ctx, onPhase = onPhase),
        CommunicationHandler(
            ctx = ctx,
            groupSystem = groupSystem,
            interEngineBus = interEngineBus,
            playerLocationIndex = playerLocationIndex,
            engineId = engineId,
            onRemoteWho = onRemoteWho,
        ),
        NavigationHandler(ctx = ctx, onCrossZoneMove = onCrossZoneMove, clock = clock, puzzleSystem = puzzleSystem),
        CombatHandler(ctx = ctx),
        ProgressionHandler(ctx = ctx, progression = progression, groupSystem = groupSystem),
        ItemHandler(ctx = ctx),
        ShopHandler(ctx = ctx, shopRegistry = shopRegistry, economyConfig = economyConfig),
        DialogueQuestHandler(ctx = ctx),
        GroupHandler(ctx = ctx, groupSystem = groupSystem),
        GuildHandler(ctx = ctx, guildSystem = guildSystem, guildHallSystem = guildHallSystem),
        puzzleHandler,
        WorldFeaturesHandler(ctx = ctx, puzzleHandler = puzzleHandler),
        MailHandler(ctx = ctx),
        petSystem?.let { PetHandler(ctx = ctx, petSystem = it) },
        enchantSystem?.let { EnchantHandler(ctx = ctx, enchantSystem = it) },
        bankConfig?.let { BankHandler(ctx = ctx, bankConfig = it) },
        stylistConfig?.let {
            StylistHandler(
                ctx = ctx,
                stylistConfig = it,
                progression = progression,
            )
        },
        DuelHandler(ctx = ctx, duelSystem = duelSystem, combatSystem = combat),
        tradeSystem?.let { TradeHandler(ctx = ctx, tradeSystem = it) },
        DungeonHandler(ctx = ctx, dungeonManager = dungeonManager, dungeonRegistry = dungeonRegistry, groupSystem = groupSystem),
        housingSystem?.let { HousingHandler(ctx = ctx, housingSystem = it) },
        PrestigeHandler(
            ctx = ctx,
            prestigeSystem = PrestigeSystem(PrestigeConfig(), progression),
            progression = progression,
        ),
        AdminHandler(
            ctx = ctx,
            onShutdown = onShutdown,
            mobRemovalCoordinator = mobRemovalCoordinator,
            interEngineBus = interEngineBus,
            engineId = engineId,
        ),
    ).forEach { it.register(router) }
    return router
}
