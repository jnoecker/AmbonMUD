# GameEngine Refactor — Phase 2 Plan

**Date:** 2026-02-26
**Context:** Phase 1 (event handler extraction, CommandRouter decomposition) is complete.
`GameEngine.kt` is currently 1594 lines. This plan targets reducing it to ~700–800 lines by
extracting the three largest remaining concerns.

---

## Current State Summary

Phase 1 already extracted:
- `SessionEventHandler`, `InputEventHandler`, `LoginEventHandler` (FSM dispatch only),
  `PhaseEventHandler`, `GmcpEventHandler`, `GmcpFlushHandler`, `InterEngineEventHandler`
- `CommandRouter` + 12 command handler classes under `engine/commands/handlers/`

What remains in `GameEngine.kt`:

| Concern | Approx. Lines | Notes |
|---------|--------------|-------|
| Login flow implementation | ~300 | 8 login methods + auth result drain + finalization |
| System initialization callbacks | ~160 | combatSystem (64 lines), abilitySystem (20), behaviorTreeSystem (15) |
| Zone reset logic | ~80 | `resetZonesIfDue()` + `resetZone()` |
| Who/cross-engine sharding utilities | ~40 | `handleRemoteWho()`, `flushDueWhoResponses()` |
| Utility helpers | ~30 | `spawnToMobState()`, `resolveRoomId()`, `idZone()`, etc. |
| Tick loop + orchestration | ~130 | Phase 1–4 loop — should stay in GameEngine |
| Lazy handler wiring + constructor | ~200 | Will shrink as state moves out |

---

## Phase 2A — Extract Login Flow into `LoginFlowHandler`

**Estimated savings: ~280 lines from GameEngine**

### What to extract

Move these methods and their private state from `GameEngine` into a new class
`dev.ambon.engine.events.LoginFlowHandler`:

**Private state to move:**
```
pendingLogins: MutableMap<SessionId, LoginState>
failedLoginAttempts: MutableMap<SessionId, Int>
sessionAnsiDefaults: MutableMap<SessionId, Boolean>
pendingAuthResults: Channel<PendingAuthResult>
```

**Methods to move:**
- `handleLoginName()`
- `handleLoginCreateConfirmation()`
- `handleLoginExistingPassword()`
- `handleLoginNewPassword()`
- `handleLoginRaceSelection()`
- `handleLoginClassSelection()`
- `drainPendingAuthResults()` — called from tick loop; expose as public method
- `handlePendingAuthResult()` — large 130-line triple-dispatch; becomes private
- `finalizeSuccessfulLogin()`
- `ensureLoginRoomAvailable()`
- `recordFailedLoginAttemptAndCloseIfNeeded()`
- `promptForName()`, `promptForExistingPassword()`, `promptForNewPassword()`
- `promptForCreateConfirmation()`, `promptForRaceSelection()`, `promptForClassSelection()`
- `extractLoginName()`

**Public interface of the new class:**
```kotlin
class LoginFlowHandler(
    private val outbound: OutboundBus,
    private val players: PlayerRegistry,
    private val world: World,
    private val loginConfig: LoginConfig,
    private val engineConfig: EngineConfig,
    private val progression: PlayerProgression,
    private val metrics: GameMetrics,
    private val questSystem: QuestSystem,
    private val achievementSystem: AchievementSystem,
    private val gmcpEmitter: GmcpEmitter,
    private val clock: Clock,
    private val engineScope: () -> CoroutineScope,           // lazy ref
    private val onLoginComplete: suspend (SessionId, PlayerState) -> Unit,
    private val onLoginFailed: suspend (SessionId) -> Unit,
) {
    // State formerly in GameEngine
    val pendingLogins = mutableMapOf<SessionId, LoginState>()
    val failedLoginAttempts = mutableMapOf<SessionId, Int>()
    val sessionAnsiDefaults = mutableMapOf<SessionId, Boolean>()

    suspend fun drainPendingAuthResults() { ... }
    suspend fun onLoginLine(sessionId: SessionId, line: String, state: LoginState) { ... }
    fun cleanupSession(sessionId: SessionId) { ... }  // called by SessionEventHandler
}
```

**GameEngine after extraction:**
- `loginEventHandler` lambda wires to `loginFlowHandler.onLoginLine()`
- Tick loop calls `loginFlowHandler.drainPendingAuthResults()`
- `SessionEventHandler` calls `loginFlowHandler.cleanupSession()` on disconnect

### Implementation notes
- `finalizeSuccessfulLogin()` touches many systems (GMCP, room display, broadcast). Pass those
  as constructor dependencies or callbacks — do NOT pass `this` (GameEngine).
- `ensureLoginRoomAvailable()` needs `handoffManager` and `interEngineBus`; pass as nullable
  constructor parameters matching the existing optionality.
- Keep `pendingAuthResults` channel inside `LoginFlowHandler`; expose `drainPendingAuthResults()`
  as the only entry point from the tick loop.

---

## Phase 2B — Extract Zone Reset into `ZoneResetHandler`

**Estimated savings: ~80 lines from GameEngine**

### What to extract

New class: `dev.ambon.engine.ZoneResetHandler`

**Private state to move:**
```
zoneResetDueAtMillis: MutableMap<String, Long>
```

**Methods to move:**
- `resetZonesIfDue()`
- `resetZone()`
- `spawnToMobState()` — helper used only by zone reset

**Public interface:**
```kotlin
class ZoneResetHandler(
    private val world: World,
    private val mobs: MobRegistry,
    private val items: ItemRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val worldState: WorldStateRegistry,
    private val worldStateRepository: WorldStateRepository?,
    private val clock: Clock,
    private val metrics: GameMetrics,
) {
    fun initialize(world: World) { /* set initial zoneResetDueAtMillis */ }
    suspend fun tick() { /* replaces resetZonesIfDue() */ }
}
```

**GameEngine after extraction:**
```kotlin
private val zoneResetHandler by lazy { ZoneResetHandler(...) }

// In tick loop Phase 4:
zoneResetHandler.tick()
```

### Implementation notes
- `resetZone()` broadcasts to players in the zone; it needs `outbound` and `players` but NOT
  the full engine.
- `spawnToMobState()` is a pure conversion function; can also live as a top-level function in
  a `MobSpawnUtil.kt` file if other code ever needs it.

---

## Phase 2C — Move Who/Sharding Utilities into `InterEngineEventHandler`

**Estimated savings: ~40 lines from GameEngine**

`handleRemoteWho()` and `flushDueWhoResponses()` are currently in GameEngine but logically
belong with inter-engine messaging.

### Changes
- Move `pendingWhoRequests: MutableMap<String, PendingWhoRequest>` into `InterEngineEventHandler`
- Move `handleRemoteWho()` and `flushDueWhoResponses()` into `InterEngineEventHandler`
- Expose a `flushDueWhoResponses()` method called from the tick loop

**GameEngine tick loop after:**
```kotlin
interEngineEventHandler.flushDueWhoResponses()
```

---

## Phase 2D — System Initialization Cleanup (Lower Priority)

The `combatSystem`, `abilitySystem`, and `behaviorTreeSystem` init blocks are long because they
configure callbacks that reference many parts of GameEngine. Two options:

### Option A: Callback objects (recommended)
Extract a `CombatCallbacks` data class passed to `CombatSystem`:
```kotlin
data class CombatCallbacks(
    val onMobRemoved: suspend (MobId, RoomId) -> Unit,
    val onLevelUp: suspend (SessionId, Int) -> Unit,
    val onMobKilledByPlayer: suspend (SessionId, MobId) -> Unit,
    val scheduleRespawn: (String, MobId, Long) -> Unit,
)
```
Move the lambda bodies to private methods in GameEngine or to a `GameEngineCallbackFactory`
helper. This doesn't reduce line count much but makes the dependency graph explicit and
makes the init blocks readable at a glance.

### Option B: GameEngineSystemsFactory
Extract a `GameEngineSystemsFactory` that wires all system callbacks and returns the
configured system instances. `GameEngine` constructor calls the factory and receives
pre-wired systems.

**Option A is recommended** — it's a smaller, lower-risk change that improves readability
without introducing a new abstraction layer.

**Estimated savings:** 30–40 lines (callback lambdas become one-liner delegations).

---

## Suggested Implementation Order

| Phase | Change | Risk | Savings |
|-------|--------|------|---------|
| 2A | Extract `LoginFlowHandler` | Medium (login is complex) | ~280 lines |
| 2B | Extract `ZoneResetHandler` | Low | ~80 lines |
| 2C | Move Who utilities to `InterEngineEventHandler` | Low | ~40 lines |
| 2D | Callback objects for system init | Low | ~30 lines |

**Total projected savings: ~430 lines**
**Projected GameEngine.kt size: ~1160 lines → ~730 lines after all phases**

Start with 2B (lowest risk, standalone) to validate the pattern, then 2A (largest impact),
then 2C and 2D.

---

## Verification Checklist (each phase)

- [ ] `./gradlew ktlintCheck` passes
- [ ] `./gradlew test` passes (no behavioral change)
- [ ] `GameEngine.kt` line count decreases by expected amount
- [ ] New handler class has <300 lines
- [ ] No new circular dependencies introduced
- [ ] Extracted state is not accessible from outside the new handler
  (use `internal` or `private` as appropriate)
