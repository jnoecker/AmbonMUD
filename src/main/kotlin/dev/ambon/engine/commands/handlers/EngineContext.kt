package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.config.BankConfig
import dev.ambon.config.EconomyConfig
import dev.ambon.config.FlightConfig
import dev.ambon.config.StylistConfig
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.EquipmentSlotRegistry
import dev.ambon.engine.FlightSystem
import dev.ambon.engine.GenderRegistry
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.JukeboxSystem
import dev.ambon.engine.LeaderboardSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.MusicBoxSystem
import dev.ambon.engine.PlayerClassRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PuzzleSystem
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.RaceRegistry
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.StatRegistry
import dev.ambon.engine.TrainerRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.crafting.GatheringRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.metrics.GameMetrics

/**
 * Shared registries and services passed to every command handler.
 *
 * Grouping these into one parameter avoids threading 6–8 identical
 * constructor parameters through every handler class and through the
 * wiring code that instantiates them. Adding a new commonly-needed
 * dependency requires only updating this class and the one place that
 * constructs it — not every handler constructor individually.
 */
data class EngineContext(
    val players: PlayerRegistry,
    val mobs: MobRegistry,
    val world: World,
    val items: ItemRegistry,
    val outbound: OutboundBus,
    val combat: CombatSystem,
    val gmcpEmitter: GmcpEmitter?,
    val worldState: WorldStateRegistry?,
    val gatheringRegistry: GatheringRegistry? = null,
    val shopRegistry: ShopRegistry? = null,
    val economyConfig: EconomyConfig = EconomyConfig(),
    val questSystem: QuestSystem? = null,
    val classRegistry: PlayerClassRegistry? = null,
    val raceRegistry: RaceRegistry? = null,
    val statRegistry: StatRegistry? = null,
    val equipmentSlotRegistry: EquipmentSlotRegistry? = null,
    val genderRegistry: GenderRegistry? = null,
    val leaderboardSystem: LeaderboardSystem? = null,
    val trainerRegistry: TrainerRegistry? = null,
    val puzzleSystem: PuzzleSystem? = null,
    /** Per-room jukebox state. Null disables jukebox GMCP/commands (e.g. in tests). */
    val jukeboxSystem: JukeboxSystem? = null,
    /** Per-player music-box state. Null disables music-box GMCP/commands (e.g. in tests). */
    val musicBoxSystem: MusicBoxSystem? = null,
    val bankConfig: BankConfig = BankConfig(),
    val stylistConfig: StylistConfig = StylistConfig(),
    /** Flight-master fast-travel logic. Null disables flight GMCP/commands (e.g. in tests). */
    val flightSystem: FlightSystem? = null,
    val flightConfig: FlightConfig = FlightConfig(),
    /** Records shop/craft/gather item discoveries into Akathavae journals when present. */
    val akathavaeSystem: dev.ambon.engine.AkathavaeSystem? = null,
    /** Gameplay metrics. Defaults to a no-op registry so tests need no wiring. */
    val metrics: GameMetrics = GameMetrics.noop(),
)
