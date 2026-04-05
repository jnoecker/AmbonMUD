# Phase 1: State Extraction - Research

**Researched:** 2026-04-05
**Domain:** React Context + useReducer state management refactoring
**Confidence:** HIGH

## Summary

Phase 1 is a pure refactoring of `web-v3/src/App.tsx` (1,655 lines, 88 `useState` calls) into a domain-split React Context architecture. The current monolith passes 80+ setter functions into `applyGmcpPackage` and prop-drills state into 14+ panel components. The target architecture replaces this with 5-8 focused React Contexts using `useReducer`, a single `GameStateBridgeSync` component that writes to the mutable ref for PixiJS, and a refactored `applyGmcpPackage` that receives a dispatch map instead of individual setters.

The technology is stable and well-understood: React 19.2.4 (installed), `useContext`, `useReducer`, and the split-context pattern. No new dependencies are needed. The primary risks are (1) regression from the big-bang refactor of 88 state variables, (2) breaking the canvas bridge sync that enables 60fps reads, and (3) getting the GMCP dispatch routing right across 95 switch cases.

**Primary recommendation:** Split into 7 domain contexts (Core, Combat, Inventory, Social, World, Quest, UI), implement a single GameStateBridgeSync component, and refactor applyGmcpPackage to accept a dispatch map -- all in one pass with manual regression testing via `./gradlew demo`.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Domain-split contexts -- 5-8 focused contexts (e.g., CoreContext, CombatContext, InventoryContext, SocialContext, WorldContext, UIContext) rather than one monolithic context
- **D-02:** Each context uses `useReducer` with a typed discriminated action union (e.g., `{ type: 'SET_VITALS', vitals } | { type: 'SET_ROOM', room }`)
- **D-03:** A dedicated `GameStateBridgeSync` component subscribes to all contexts and writes to `gameStateRef` in one place -- single sync layer, not per-context
- **D-04:** Pure React Context + `useReducer` -- no external state libraries (Zustand, Jotai, etc.). Zero new dependencies. React 19's improved context performance is sufficient with domain-split architecture.
- **D-05:** `applyGmcpPackage` receives a dispatch map (`{ core: coreDispatch, combat: combatDispatch, ... }`) and fires typed actions directly to the appropriate domain context
- **D-06:** Refactor `applyGmcpPackage` signature directly -- no adapter layer, no temporary setter compatibility. Clean break in one pass.
- **D-07:** Big-bang refactor -- extract all 88 `useState` calls at once into the new context system in a single PR. No hybrid state, no temporary adapters.
- **D-08:** New context files live in `web-v3/src/state/` directory (e.g., `state/CoreContext.tsx`, `state/CombatContext.tsx`, `state/GameStateProvider.tsx` as composition root)

### Claude's Discretion
- Exact number and naming of domain contexts (user said "you decide" on granularity -- optimize for minimal re-renders and manageable file sizes)
- Internal reducer action naming conventions
- How to handle the `canvasCallbacks` bridge (keep as-is or fold into a context)
- Test strategy for verifying no regressions

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| STATE-01 | Extract App.tsx monolith (90+ useState) into GameStateProvider context + reducer | Domain-split context architecture with 7 contexts, useReducer pattern, GameStateProvider composition root -- see Architecture Patterns section |
| STATE-02 | Preserve GameStateBridge ref pattern for canvas performance (60fps reads) | GameStateBridgeSync component pattern that subscribes to all contexts and writes to mutable ref -- see Architecture Patterns: GameStateBridgeSync |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| react | 19.2.4 | Context + useReducer state management | Already installed; no external deps needed per D-04 [VERIFIED: node_modules] |
| react-dom | 19.2.4 | DOM rendering | Already installed [VERIFIED: node_modules] |
| typescript | 5.9.3 | Type safety for discriminated unions | Already installed [VERIFIED: npx tsc --version] |

### Supporting
No new libraries needed. This phase uses only React built-in APIs.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| React Context | Zustand | Simpler API, but adds dependency -- ruled out by D-04 |
| React Context | Jotai | Atom-based, great for fine-grained updates -- ruled out by D-04 |
| useReducer | useState in context | useState simpler but doesn't centralize transition logic; useReducer better for complex state per D-02 |
| Split contexts | Single monolithic context | Single context re-renders ALL consumers on ANY change; split contexts are essential for performance |

**Installation:**
```bash
# No new packages needed -- zero dependency change
```

## Architecture Patterns

### Recommended Project Structure
```
web-v3/src/
├── state/                      # NEW: All state management
│   ├── GameStateProvider.tsx    # Composition root: nests all providers
│   ├── GameStateBridgeSync.tsx  # Single component syncing contexts -> gameStateRef
│   ├── CoreContext.tsx          # Vitals, character, login, connection state
│   ├── CombatContext.tsx        # Combat target, effects, skills, combat log
│   ├── InventoryContext.tsx     # Items, equipment, bank, crafting, trade, auction
│   ├── SocialContext.tsx        # Chat, guild, group, friends, mail, who list
│   ├── WorldContext.tsx         # Room, mobs, players, room items, features, world time/weather
│   ├── QuestContext.tsx         # Active quests, available quests, daily/weekly/auto/global quests
│   └── UIContext.tsx            # Popouts, terminal visibility, toast, notifications, look target
├── gmcp/
│   └── applyGmcpPackage.ts     # MODIFIED: accepts dispatch map instead of setter bag
├── canvas/
│   └── GameStateBridge.ts      # UNCHANGED: mutable ref + canvasCallbacks
├── hooks/                      # UNCHANGED: existing hooks continue working
│   ├── useCommandHistory.ts
│   ├── useMiniMap.ts
│   ├── useMudSocket.ts         # MODIFIED: handleGmcp builds dispatch map
│   ├── useAudioEngine.ts
│   └── useQuickbar.ts
├── App.tsx                     # SLIMMED: ~80-100 lines, renders GameStateProvider + layout shell
└── components/                 # MODIFIED: panels switch from props to useContext
```

### Pattern 1: Domain Context with useReducer

**What:** Each domain context provides both state and dispatch, with a typed discriminated union for actions.
**When to use:** Every domain context in this phase.

```typescript
// Source: React official docs pattern [CITED: https://react.dev/learn/scaling-up-with-reducer-and-context]
import { createContext, useContext, useReducer } from "react";
import type { Dispatch, ReactNode } from "react";
import type { Vitals, CharacterInfo, StatusVarLabels } from "../types";
import { EMPTY_VITALS, EMPTY_CHAR, DEFAULT_STATUS_VAR_LABELS } from "../constants";

// State shape
interface CoreState {
  vitals: Vitals;
  statusVarLabels: StatusVarLabels;
  character: CharacterInfo;
  connected: boolean;
  loginPrompt: LoginPromptState | null;
  loginError: LoginErrorState | null;
  reconnecting: boolean;
  savedCharacters: string[];
  serverAssets: Record<string, string>;
  serverCommands: CommandEntry[];
  emotePresets: EmotePreset[];
}

// Discriminated action union (per D-02)
type CoreAction =
  | { type: "SET_VITALS"; vitals: Vitals }
  | { type: "SET_CHARACTER"; character: CharacterInfo }
  | { type: "SET_STATUS_VAR_LABELS"; labels: StatusVarLabels }
  | { type: "SET_CONNECTED"; connected: boolean }
  | { type: "SET_LOGIN_PROMPT"; prompt: LoginPromptState | null }
  | { type: "SET_LOGIN_ERROR"; error: LoginErrorState | null }
  | { type: "SET_RECONNECTING"; reconnecting: boolean }
  | { type: "SET_SAVED_CHARACTERS"; characters: string[] }
  | { type: "SET_SERVER_ASSETS"; assets: Record<string, string> }
  | { type: "SET_SERVER_COMMANDS"; commands: CommandEntry[] }
  | { type: "SET_EMOTE_PRESETS"; presets: EmotePreset[] }
  | { type: "RESET" };

function coreReducer(state: CoreState, action: CoreAction): CoreState {
  switch (action.type) {
    case "SET_VITALS":
      return { ...state, vitals: action.vitals };
    case "SET_CHARACTER":
      return { ...state, character: action.character };
    // ... etc
    case "RESET":
      return INITIAL_CORE_STATE;
    default:
      return state;
  }
}

// Split state and dispatch contexts to prevent dispatch-only consumers from re-rendering
const CoreStateContext = createContext<CoreState>(INITIAL_CORE_STATE);
const CoreDispatchContext = createContext<Dispatch<CoreAction>>(() => {});

export function CoreProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(coreReducer, INITIAL_CORE_STATE);
  return (
    <CoreStateContext value={state}>
      <CoreDispatchContext value={dispatch}>
        {children}
      </CoreDispatchContext>
    </CoreStateContext>
  );
}

// Custom hooks for clean consumption
export function useCoreState() { return useContext(CoreStateContext); }
export function useCoreDispatch() { return useContext(CoreDispatchContext); }
```

**Key detail:** React 19 uses `<Context value={...}>` directly -- the `Context.Provider` wrapper is no longer needed. [CITED: https://react.dev/blog/2024/12/05/react-19]

### Pattern 2: GameStateProvider Composition Root

**What:** A single component that nests all domain providers, creating the full context tree.
**When to use:** Once, wrapping the entire App component tree.

```typescript
// state/GameStateProvider.tsx
export function GameStateProvider({ children }: { children: ReactNode }) {
  return (
    <CoreProvider>
      <CombatProvider>
        <InventoryProvider>
          <SocialProvider>
            <WorldProvider>
              <QuestProvider>
                <UIProvider>
                  <GameStateBridgeSync />
                  {children}
                </UIProvider>
              </QuestProvider>
            </WorldProvider>
          </SocialProvider>
        </InventoryProvider>
      </CombatProvider>
    </CoreProvider>
  );
}
```

**Nesting order matters for cross-context reads:** If `GameStateBridgeSync` reads from all contexts, it must be inside all providers. The order of the providers themselves does not matter since there are no cross-context dependencies at the provider level.

### Pattern 3: GameStateBridgeSync Component (STATE-02)

**What:** A single component that subscribes to all context state and writes to the mutable `gameStateRef` for PixiJS canvas consumption at 60fps.
**When to use:** Once, rendered inside GameStateProvider.

```typescript
// state/GameStateBridgeSync.tsx
import { gameStateRef } from "../canvas/GameStateBridge";
import { useCoreState } from "./CoreContext";
import { useCombatState } from "./CombatContext";
import { useWorldState } from "./WorldContext";
import { useQuestState } from "./QuestContext";
import { useInventoryState } from "./InventoryContext";

export function GameStateBridgeSync() {
  const core = useCoreState();
  const combat = useCombatState();
  const world = useWorldState();
  const quest = useQuestState();
  const inventory = useInventoryState();

  // Write to mutable ref on every render -- no useEffect needed.
  // This runs synchronously during render, so the ref is always fresh
  // when the canvas reads it on the next animation frame.
  gameStateRef.current = {
    room: world.room,
    vitals: core.vitals,
    mobs: world.mobs,
    players: world.players,
    roomItems: world.roomItems,
    combatTarget: combat.combatTarget,
    inCombat: core.vitals.inCombat,
    effects: combat.effects,
    character: core.character,
    mobInfo: world.mobInfo,
    groupInfo: /* from social context */,
    dialogue: world.dialogue,
    questsAvailable: quest.questsAvailable,
    shop: inventory.shop,
    craftingNodes: inventory.craftingNodes,
    questTargetRoomIds: /* derived from quest state */,
    serverAssets: core.serverAssets,
  };

  return null; // Renders nothing -- pure sync component
}
```

**Critical:** This component will re-render whenever ANY subscribed context changes. This is intentional -- it replaces the current `useEffect` at line 767 of App.tsx that runs on every render anyway. The mutable ref write is a cheap synchronous operation. [VERIFIED: App.tsx line 767-793 current implementation]

### Pattern 4: GMCP Dispatch Map (D-05, D-06)

**What:** Refactored `applyGmcpPackage` receives dispatch functions instead of 80+ setters.
**When to use:** In `useMudSocket`'s `onGmcpMessage` callback.

```typescript
// New GmcpContext interface (replaces the old 80+ setter interface)
export interface GmcpDispatchMap {
  core: Dispatch<CoreAction>;
  combat: Dispatch<CombatAction>;
  inventory: Dispatch<InventoryAction>;
  social: Dispatch<SocialAction>;
  world: Dispatch<WorldAction>;
  quest: Dispatch<QuestAction>;
  ui: Dispatch<UIAction>;
  // Non-dispatch utilities that remain
  updateMap: (roomId: string, exits: Record<string, string>, title: string, image: string | null, mapX: number, mapY: number, housing?: boolean) => void;
  loadZoneMap: (zone: string, rooms: Array<{ id: string; x: number; y: number; exits: Record<string, string> }>) => void;
  sendGmcp: (pkg: string, payload: unknown) => boolean;
  resumeTokenRef: { current: string | null };
  pendingAuthCharRef: { current: string | null };
  failedAuthCharRef: { current: string | null };
}

// Inside applyGmcpPackage:
case "Char.Vitals": {
  const packet = data as Partial<Record<string, unknown>>;
  ctx.core({ type: "SET_VITALS", vitals: { /* parse fields */ } });
  break;
}

case "Room.Info": {
  const packet = data as Partial<Record<string, unknown>>;
  ctx.world({ type: "SET_ROOM", room: { /* parse fields */ } });
  ctx.world({ type: "SET_ROOM_FEATURES", features: [] }); // clear features on room change
  ctx.ui({ type: "SET_CONTAINER_CONTENTS", contents: null }); // clear container on room change
  break;
}
```

**Note:** Some GMCP handlers dispatch to multiple contexts (e.g., `Room.Info` clears world state AND UI state). This is fine -- dispatches are synchronous and batched by React 19 automatically. [CITED: https://react.dev/blog/2024/12/05/react-19]

### Pattern 5: canvasCallbacks Bridge

**Recommendation (Claude's discretion):** Keep `canvasCallbacks` as-is in `GameStateBridge.ts`. The callbacks are imperative function references set by `useEffect` hooks. They bridge canvas click events into React actions (sendCommand, openShop, etc.). Folding them into a context would add complexity without benefit -- canvas code needs direct function references, not reactive state. The `useEffect` hooks that wire callbacks can live in `App.tsx` or in a small `useCanvasCallbackWiring` hook.

### Anti-Patterns to Avoid
- **Single monolithic context:** Putting all 88 state variables in one context defeats the purpose -- every consumer re-renders on every GMCP packet.
- **useEffect for bridge sync:** Don't use `useEffect` to write to `gameStateRef` -- it runs after paint, introducing a frame of stale data. Write during render (current App.tsx approach at line 767 is correct; preserve this pattern).
- **Splitting state and dispatch into separate files:** Keep each domain's state type, action type, reducer, provider, and hooks in one file. A 200-line context file is manageable; splitting across 4 files creates navigation overhead.
- **Memoizing dispatch:** `useReducer` dispatch is stable across renders -- never wrap it in `useCallback` or `useMemo`.

## Recommended Domain Context Split

Based on analysis of 88 useState calls and 95 GMCP switch cases, here is the recommended 7-context split:

### 1. CoreContext (~14 state fields)
**GMCP families:** `Char.StatusVars`, `Char.Vitals`, `Char.Name`, `Char.Sprites`, `Char.Stats`, `Char.Classes`, `Server.Assets`, `Server.Commands`, `Server.EmotePresets`, `Login.*`, `Session.*`

| State Field | Type | Initial Value |
|-------------|------|---------------|
| vitals | Vitals | EMPTY_VITALS |
| statusVarLabels | StatusVarLabels | DEFAULT_STATUS_VAR_LABELS |
| character | CharacterInfo | EMPTY_CHAR |
| charStats | CharStats \| null | null |
| spriteList | SpriteList | { active: null, sprites: [] } |
| unlockedClasses | string[] | [] |
| loginPrompt | LoginPromptState \| null | null |
| loginError | LoginErrorState \| null | null |
| reconnecting | boolean | false |
| savedCharacters | string[] | [] |
| serverAssets | Record<string, string> | {} |
| serverCommands | CommandEntry[] | [] |
| emotePresets | EmotePreset[] | [] |
| prestigeInfo | PrestigeInfo \| null | null |

### 2. CombatContext (~7 state fields)
**GMCP families:** `Char.Combat`, `Char.StatusEffects`, `Char.Skills`, `Char.Cooldown`, `Char.Combat.Event`, `Duel.*`

| State Field | Type | Initial Value |
|-------------|------|---------------|
| combatTarget | CombatTarget \| null | null |
| effects | StatusEffect[] | [] |
| skills | SkillSummary[] | [] |
| combatLogMessages | CombatLogMessage[] | [] |
| duelState | DuelState \| null | null |
| duelChallenge | DuelChallenge \| null | null |
| petState | PetState \| null | null |

**Special handling:** `combatEventsRef` and `gainEventsRef` (currently `useRef`) feed the canvas event bus. These can stay as refs managed inside the CombatProvider or in a lightweight callback passed to the dispatch map.

### 3. InventoryContext (~11 state fields)
**GMCP families:** `Char.Items.*`, `Char.Equipment.*`, `Shop.*`, `Crafting.*`, `Auction.*`, `Trade.*`, `Char.Bank`, `Char.Currencies`, `Char.Gain`

| State Field | Type | Initial Value |
|-------------|------|---------------|
| inventory | ItemSummary[] | [] |
| equipment | Record<string, ItemSummary> | {} |
| equipmentSlotDefs | EquipmentSlotDef[] | [] |
| shop | ShopState \| null | null |
| craftingSkills | CraftingSkill[] | [] |
| craftingRecipes | CraftingRecipe[] | [] |
| craftingNodes | CraftingNode[] | [] |
| tradeState | TradeState \| null | null |
| auctionListings | AuctionListing[] | [] |
| bankState | BankState \| null | null |
| currencies | CurrencyBalance[] | [] |

### 4. SocialContext (~11 state fields)
**GMCP families:** `Comm.Channel`, `Group.*`, `Guild.*`, `Friends.*`, `Server.Who`, `Mail.*`, `Lottery.Info`

| State Field | Type | Initial Value |
|-------------|------|---------------|
| chatByChannel | Record<ChatChannel, ChatMessage[]> | createEmptyChatByChannel() |
| activeChatChannel | ChatChannel | "say" |
| groupInfo | GroupInfo | { leader: null, members: [] } |
| pendingGroupInvite | PendingGroupInvite \| null | null |
| guildInfo | GuildInfo | { name: null, tag: null, rank: null, motd: null, memberCount: 0, maxSize: 50 } |
| pendingGuildInvite | PendingGuildInvite \| null | null |
| guildMembers | GuildMemberEntry[] | [] |
| guildHall | GuildHallInfo \| null | null |
| friends | FriendEntry[] | [] |
| whoPlayers | WhoPlayer[] | [] |
| lotteryInfo | LotteryInfo \| null | null |

### 5. WorldContext (~12 state fields)
**GMCP families:** `Room.*`, `Zone.*`, `Dialogue.*`, `World.*`, `Dungeon.*`

| State Field | Type | Initial Value |
|-------------|------|---------------|
| room | RoomState | EMPTY_ROOM |
| players | RoomPlayer[] | [] |
| mobs | RoomMob[] | [] |
| roomItems | RoomItem[] | [] |
| mobInfo | MobInfo[] | [] |
| roomFeatures | RoomFeature[] | [] |
| dialogue | DialogueState \| null | null |
| zoneInstances | ZoneInstances | { zone: null, currentEngineId: null, instances: [] } |
| worldTime | WorldTime \| null | null |
| worldWeather | WorldWeather \| null | null |
| worldEvents | WorldEvent[] | [] |
| dungeonInfo | DungeonInfo \| null | null |

### 6. QuestContext (~8 state fields)
**GMCP families:** `Quest.*`, `Char.Achievements`

| State Field | Type | Initial Value |
|-------------|------|---------------|
| quests | QuestEntry[] | [] |
| questsAvailable | QuestAvailable[] | [] |
| achievements | AchievementData | { completed: [], inProgress: [] } |
| dailyQuests | DailyQuestBoard \| null | null |
| weeklyQuests | DailyQuestEntry[] | [] |
| autoQuest | AutoQuest \| null | null |
| globalQuest | GlobalQuest \| null | null |
| questNotifications | QuestNotification[] | [] |

### 7. UIContext (~14 state fields)
**GMCP families:** `UI.Feedback`, `Staff.*`, `Core.Ping`

| State Field | Type | Initial Value |
|-------------|------|---------------|
| activePopout | PopoutPanel | null |
| showAdminPanel | boolean | false |
| showCommandPalette | boolean | false |
| terminalVisible | boolean | false |
| terminalOpaque | boolean | false |
| layoutMode | LayoutMode | from localStorage |
| composerValue | string | "" |
| videoUrl | string \| null | null |
| videoClosing | boolean | false |
| toast | string \| null | null |
| lookTarget | LookTargetInfo \| null | null |
| broadcast | { sender, message } \| null | null |
| staffInvisible | boolean | false |
| possessing | string \| null | null |
| containerContents | ContainerContents \| null | null |
| staffWorldInfo | StaffWorldZone[] | [] |
| staffMobTemplates | StaffMobZone[] | [] |
| mailInbox | MailEntry[] \| null | null |
| mailMessage | MailMessage \| null | null |
| friendNotifications | FriendNotification[] | [] |
| trainer | TrainerData \| null | null |
| housing | HousingInfo \| null | null |
| leaderboard | Record<string, LeaderboardData> | {} |
| factions | FactionStanding[] | [] |

**Note on UIContext size:** At ~23 fields this is the largest context. Many of these are panel-specific data (trainer, housing, leaderboard, factions, mail) that only update when the user opens that panel. An alternative would be to split "panel data" into a separate context, but since these fields rarely change simultaneously, re-render cost is minimal. The planner may choose to keep or split -- either works. [ASSUMED]

### Total: 88 useState calls mapped to 7 contexts

The count is approximate -- some fields like `combatLogIdCounter` (module-level), `combatEventsRef`/`gainEventsRef` (useRef), and `resumeTokenRef`/`pendingAuthCharRef`/`failedAuthCharRef`/`connectedRef` (useRef) are not useState and don't need to move into contexts.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| State management library | Custom pub/sub system | React Context + useReducer | Built-in, zero deps, well-tested (D-04) |
| Context selector | Manual shouldComponentUpdate wrappers | Split contexts (D-01) | React 19 doesn't ship useContextSelector in stable; split contexts achieve the same goal |
| State bridge for canvas | Custom event emitter | Mutable ref pattern (GameStateBridge) | Already working; synchronous writes during render are frame-perfect |
| Action type checking | Runtime validation | TypeScript discriminated unions | Compile-time safety, zero runtime cost |

**Key insight:** The `useContextSelector` hook does NOT exist in React 19.2 stable. It is only available as a third-party library (`use-context-selector`). The split-context approach (D-01) achieves the same re-render optimization without any dependency. [VERIFIED: React 19.2 release notes do not include useContextSelector]

## Common Pitfalls

### Pitfall 1: Context Provider Value Identity
**What goes wrong:** If the provider value is a new object on every render (e.g., `value={{ state, dispatch }}`), all consumers re-render even when state hasn't changed.
**Why it happens:** Object literal creates new reference each render.
**How to avoid:** Split into two contexts -- one for state (changes), one for dispatch (stable). `useReducer` dispatch is referentially stable.
**Warning signs:** Components that only call dispatch still re-render on state changes.

### Pitfall 2: useEffect vs Render-time Ref Writes
**What goes wrong:** Using `useEffect` to sync state to `gameStateRef` introduces a 1-frame delay -- canvas reads stale data for one paint.
**Why it happens:** `useEffect` fires after the browser paints.
**How to avoid:** Write to `gameStateRef.current` during render (inside the component body), not in an effect. The current App.tsx already does this correctly at line 767 -- preserve this pattern in `GameStateBridgeSync`.
**Warning signs:** Canvas shows previous room/vitals for one frame after GMCP update.

### Pitfall 3: Forgetting to Reset State on Disconnect
**What goes wrong:** After logout/disconnect, stale state persists in contexts.
**Why it happens:** Each context manages its own state; the monolithic `resetHud` callback (App.tsx line 394) resets everything in one place.
**How to avoid:** Each reducer must handle a `RESET` action. The disconnect handler dispatches `RESET` to all 7 contexts.
**Warning signs:** After reconnecting, UI shows previous session's inventory/quests.

### Pitfall 4: GMCP Handlers That Dispatch to Multiple Contexts
**What goes wrong:** Some GMCP packages need to update state across multiple domains. For example, `Room.Info` sets room data (WorldContext) AND clears container contents (UIContext). Missing a secondary dispatch causes stale UI.
**Why it happens:** The original code uses individual setters so cross-domain updates are implicit. With dispatch maps, each cross-domain write must be explicit.
**How to avoid:** Audit all 95 GMCP switch cases for multi-setter patterns. Document cross-context dispatches in comments.
**Warning signs:** Opening a container in one room still shows the previous room's container contents.

### Pitfall 5: Derived State Computation
**What goes wrong:** Some current values in App.tsx are derived (e.g., `textMode`, `xpText`, `hasCharacterProfile`, `preLogin`, `exits`). If these are recomputed in every consumer, it wastes cycles. If they're in the wrong context, consumers don't update.
**Why it happens:** Derived state was previously computed once in App.tsx.
**How to avoid:** Keep simple derivations in consumers via `useMemo`. For frequently-used derivations (like `textMode` or `hasCharacterProfile`), compute them in the context provider itself or export a custom hook that derives and memoizes.
**Warning signs:** Stale derived values or unnecessary re-renders from recalculating derived state.

### Pitfall 6: Callback Functions That Need Multiple Contexts
**What goes wrong:** Functions like `sendChatMessage`, `handleCastSkill`, `completeCast`, and `submitComposer` currently read from and write to multiple state variables. They need access to multiple contexts.
**Why it happens:** Complex interactions cross domain boundaries (e.g., casting a spell reads combat target + skills, writes to skills + sends command).
**How to avoid:** These callback functions can live in App.tsx or in a dedicated `useGameActions` hook that consumes multiple contexts. They do NOT need to be in reducers.
**Warning signs:** Circular dependencies between context files.

## Code Examples

### Example 1: Consuming Context in a Panel Component

```typescript
// Before: prop drilling
function EquipmentPanel({ connected, hasCharacterProfile, character, equipment, slotDefs, ... }) { ... }

// After: context consumption
import { useCoreState } from "../state/CoreContext";
import { useInventoryState } from "../state/InventoryContext";

function EquipmentPanel() {
  const { character } = useCoreState();
  const { equipment, equipmentSlotDefs } = useInventoryState();
  const hasCharacterProfile = character.name !== "-";
  // ... render
}
```

### Example 2: Slim App.tsx Structure

```typescript
// Target App.tsx: ~80-100 lines
import { GameStateProvider } from "./state/GameStateProvider";
import { AppShell } from "./components/AppShell";

function App() {
  return (
    <GameStateProvider>
      <AppShell />
    </GameStateProvider>
  );
}
```

Where `AppShell` contains the layout, terminal setup, keyboard shortcuts, and canvas callback wiring that currently live in App.tsx. The goal is NOT to move everything into contexts -- terminal refs, the xterm instance, audio, minimap canvas, and keyboard handlers stay in App.tsx or AppShell.

### Example 3: Reset on Disconnect

```typescript
// In the disconnect handler (wherever useMudSocket is wired):
function handleDisconnect() {
  coreDispatch({ type: "RESET" });
  combatDispatch({ type: "RESET" });
  inventoryDispatch({ type: "RESET" });
  socialDispatch({ type: "RESET" });
  worldDispatch({ type: "RESET" });
  questDispatch({ type: "RESET" });
  uiDispatch({ type: "RESET" });
}
```

## GMCP Package to Context Mapping

This table maps all 95 GMCP switch cases to their target context, including cross-context dispatches:

| GMCP Package | Primary Context | Secondary Context(s) | Notes |
|-------------|----------------|---------------------|-------|
| Char.StatusVars | Core | -- | |
| Char.Vitals | Core | -- | |
| Char.Name | Core | Core (clear loginPrompt/Error) | |
| Char.Sprites | Core | -- | |
| Char.Stats | Core | -- | |
| Char.Classes | Core | -- | |
| Char.Combat | Combat | -- | |
| Char.StatusEffects | Combat | -- | |
| Char.Skills | Combat | -- | |
| Char.Cooldown | Combat | -- | |
| Char.Combat.Event | Combat | -- | Pushes to canvasEvents bus |
| Char.Gain | Combat | -- | Pushes to canvasEvents bus |
| Char.Items.List | Inventory | -- | |
| Char.Items.Add | Inventory | -- | |
| Char.Items.Remove | Inventory | -- | |
| Char.Equipment.Slots | Inventory | -- | |
| Char.Bank | Inventory | -- | |
| Char.Currencies | Inventory | -- | |
| Char.Factions | UI | -- | |
| Char.Pet | Combat | -- | |
| Shop.List | Inventory | -- | |
| Shop.Close | Inventory | UI (close popout) | Cross-context |
| Crafting.* | Inventory | -- | |
| Auction.List | Inventory | -- | |
| Trade.State | Inventory | -- | |
| Room.Info | World | UI (clear container) | Cross-context |
| Room.Items | World | -- | |
| Room.Players | World | -- | |
| Room.AddPlayer / RemovePlayer | World | -- | |
| Room.Mobs | World | -- | |
| Room.AddMob / UpdateMob / RemoveMob | World | -- | |
| Room.MobInfo | World | -- | |
| Room.Features | World | -- | |
| Room.ContainerContents | UI | -- | |
| Room.LookTarget | UI | -- | |
| Zone.Map | World | -- | Via updateMap callback |
| Zone.Instances | World | -- | |
| Dialogue.Node | World | -- | |
| Dialogue.End | World | -- | |
| World.Time / Weather / Events | World | -- | |
| Dungeon.Info | World | -- | |
| Quest.* | Quest | -- | |
| Comm.Channel | Social | -- | |
| Group.* | Social | -- | |
| Guild.* | Social | -- | |
| Friends.* | Social | UI (friendNotifications) | Cross-context |
| Mail.* | UI | -- | |
| Server.Who | Social | -- | |
| Server.Assets | Core | -- | |
| Server.Commands | Core | -- | |
| Server.EmotePresets | Core | -- | |
| Server.Broadcast | UI | -- | |
| Login.Prompt | Core | -- | |
| Login.Error | Core | -- | |
| Session.* | Core | -- | Uses refs, not state |
| UI.Feedback | UI | -- | Via pushCombatLogMessage (Combat) | Cross-context |
| Staff.* | UI | -- | |
| Trainer.List | UI | -- | |
| Lottery.Info | Social | -- | |
| Prestige.Info | Core | -- | |
| Duel.* | Combat | -- | |
| Leaderboard.Data | UI | -- | |
| Housing.Info | UI | -- | |
| Core.Ping | -- | -- | No state change |

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `<Context.Provider>` wrapper | `<Context value={...}>` direct | React 19 (Dec 2024) | Cleaner JSX, one fewer wrapper component |
| Manual batching via `unstable_batchedUpdates` | Automatic batching in all contexts | React 18 (Mar 2022) | Multiple dispatches in GMCP handler are batched automatically |
| Prop drilling for deep trees | Context + custom hooks | Stable pattern since React 16.3 | Standard approach; no innovation needed |

**Deprecated/outdated:**
- `Context.Provider` is deprecated in React 19 -- use `<Context value={...}>` directly. [CITED: https://react.dev/blog/2024/12/05/react-19]
- `useContextSelector` from core React is NOT available -- it was proposed but never shipped in stable. Only available via `use-context-selector` npm package. [VERIFIED: React 19.2 release blog]

## Assumptions Log

> List all claims tagged `[ASSUMED]` in this research.

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | UIContext at ~23 fields won't cause performance issues because panel-specific data rarely changes simultaneously | Domain Context Split: UIContext | If panels update frequently, a further split into PanelDataContext may be needed -- low risk, easy to split later |

**All other claims in this research were verified or cited.**

## Open Questions

1. **Where do `combatEventsRef` and `gainEventsRef` live?**
   - What we know: They are `useRef` (not `useState`), used to push events to `canvasEvents` bus. They don't trigger re-renders.
   - What's unclear: Should they live inside CombatProvider (as a side-effect of the reducer), or remain in App.tsx/AppShell?
   - Recommendation: Keep them as refs in AppShell. The `pushCombatEvent` and `pushGainEvent` callbacks push to both the ref and the canvas event bus -- this is imperative logic, not state management.

2. **Should panel components migrate to context consumption in this phase?**
   - What we know: Panels currently receive all data as props. Moving to `useContext` in panels is a natural consequence of the extraction but not explicitly in requirements.
   - What's unclear: Should panels keep their current prop interfaces for backward compatibility?
   - Recommendation: YES, migrate panels to context consumption in this phase. It's required to satisfy the success criterion "App.tsx is under 100 lines" -- if App.tsx still passes 80+ props, it can't be under 100 lines.

3. **How should `useMudSocket` integrate?**
   - What we know: `useMudSocket` is a hook that returns `{ connected, sendLine, sendGmcp, ... }`. The `handleGmcp` callback needs the dispatch map.
   - What's unclear: Should `useMudSocket` be called inside AppShell (which is inside GameStateProvider and thus has access to all dispatches)?
   - Recommendation: Call `useMudSocket` in AppShell. Build the dispatch map there from the context dispatch hooks.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| React | Context API | Yes | 19.2.4 | -- |
| TypeScript | Type-safe discriminated unions | Yes | 5.9.3 | -- |
| Bun | Build tool | Yes | 1.3.10 | -- |
| Vite | Dev server + build | Yes | 7.3.1 | -- |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | None installed (no vitest/jest in package.json) |
| Config file | None |
| Quick run command | `cd web-v3 && bun run build` (type-check via tsc + bundle) |
| Full suite command | `cd web-v3 && bun run lint && bun run build` |

### Phase Requirements -> Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| STATE-01 | All useState calls extracted into contexts; App.tsx < 100 lines | build + lint + manual | `cd web-v3 && bun run build && wc -l src/App.tsx` | N/A |
| STATE-02 | Canvas reads state via gameStateRef at 60fps | manual | `./gradlew demo` (visual regression check) | N/A |

### Sampling Rate
- **Per task commit:** `cd web-v3 && bun run build` (type errors catch most regressions)
- **Per wave merge:** `cd web-v3 && bun run lint && bun run build` + manual `./gradlew demo`
- **Phase gate:** Full manual regression via `./gradlew demo` -- verify login, movement, combat, inventory, chat, shops, quests

### Wave 0 Gaps
- No test framework installed (vitest, @testing-library/react not present)
- No automated component tests exist
- **Recommendation:** Do NOT add a test framework in this phase. The TypeScript compiler is the primary safety net (catches missing props, wrong action types, etc.). Manual verification via `./gradlew demo` covers behavioral regression. Adding vitest would expand scope beyond state extraction.

## Security Domain

Security is not applicable to this phase. This is a client-side refactoring that:
- Does not change authentication flows
- Does not modify data transmission (WebSocket messages unchanged)
- Does not introduce new input surfaces
- Does not change credential handling (localStorage tokens untouched)

All ASVS categories are N/A for a pure state management refactoring.

## Sources

### Primary (HIGH confidence)
- React 19 release blog: https://react.dev/blog/2024/12/05/react-19 -- `<Context value>` syntax, automatic batching [CITED]
- React official docs: https://react.dev/learn/scaling-up-with-reducer-and-context -- useReducer + Context pattern [CITED]
- `web-v3/src/App.tsx` -- 1,655 lines, 88 useState calls, 95 GMCP switch cases [VERIFIED: direct read]
- `web-v3/src/gmcp/applyGmcpPackage.ts` -- GmcpContext interface with 80+ setter fields [VERIFIED: direct read]
- `web-v3/src/canvas/GameStateBridge.ts` -- GameStateSnapshot interface and mutable ref pattern [VERIFIED: direct read]
- `web-v3/package.json` -- React 19.2.4 installed, no test framework [VERIFIED: direct read]
- React 19.2 release: https://react.dev/blog/2025/10/01/react-19-2 -- confirmed useContextSelector NOT in stable [CITED]

### Secondary (MEDIUM confidence)
- React Context performance optimization article: https://www.tenxdeveloper.com/blog/optimizing-react-context-performance -- split context pattern [CITED]
- React 19 useContextSelector deep dive: https://dev.to/a1guy/react-19-usecontextselector-deep-dive -- confirmed it's library-only, not in core [CITED]

### Tertiary (LOW confidence)
None -- all findings verified against primary sources.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- React Context + useReducer is the official recommended pattern for this exact use case
- Architecture: HIGH -- domain split is well-understood; the GMCP-to-context mapping was derived from direct code analysis of all 95 switch cases
- Pitfalls: HIGH -- all pitfalls are well-documented in React ecosystem and verified against the actual codebase patterns

**Research date:** 2026-04-05
**Valid until:** 2026-05-05 (stable patterns, no fast-moving dependencies)
