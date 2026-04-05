---
phase: 01-state-extraction
plan: 02
subsystem: web-client-state
tags: [react-context, state-management, refactoring, gmcp]
dependency_graph:
  requires: [01-01]
  provides: [context-wired-panels, dispatch-map-gmcp, slim-app-tsx]
  affects: [web-v3/src/App.tsx, web-v3/src/gmcp/applyGmcpPackage.ts, web-v3/src/components/AppShell.tsx]
tech_stack:
  added: []
  patterns: [GmcpDispatchMap, context-consuming-panels, incremental-reducer-actions]
key_files:
  created:
    - web-v3/src/components/AppShell.tsx
  modified:
    - web-v3/src/App.tsx
    - web-v3/src/gmcp/applyGmcpPackage.ts
    - web-v3/src/state/CombatContext.tsx
    - web-v3/src/state/InventoryContext.tsx
    - web-v3/src/state/QuestContext.tsx
    - web-v3/src/state/SocialContext.tsx
    - web-v3/src/state/UIContext.tsx
    - web-v3/src/state/WorldContext.tsx
    - web-v3/src/components/panels/CharacterPanel.tsx
    - web-v3/src/components/panels/InventoryPanel.tsx
    - web-v3/src/components/panels/EquipmentPanel.tsx
    - web-v3/src/components/panels/ChatPanel.tsx
    - web-v3/src/components/panels/QuestPanel.tsx
    - web-v3/src/components/panels/CraftingPanel.tsx
    - web-v3/src/components/panels/BankPanel.tsx
    - web-v3/src/components/panels/AuctionPanel.tsx
    - web-v3/src/components/panels/LeaderboardPanel.tsx
    - web-v3/src/components/panels/HousingPanel.tsx
    - web-v3/src/components/panels/MailPanel.tsx
    - web-v3/src/components/panels/AdminPanel.tsx
    - web-v3/src/components/panels/PlayPanel.tsx
    - web-v3/src/components/CombatLog.tsx
    - web-v3/src/components/ActionBar.tsx
    - web-v3/src/components/ShopPopout.tsx
decisions:
  - "GmcpDispatchMap uses 7 typed dispatch functions instead of 80+ individual setters"
  - "Incremental reducer actions added for functional update patterns (ADD/REMOVE inventory, UPDATE_MOB merge, PUSH_CHAT_MESSAGE trim)"
  - "Cross-context dispatches handled inline in GMCP handler (Room.Info clears dialogue+quests, Shop.Close closes popout)"
  - "Panels derive connected/hasCharacterProfile locally from character.name instead of receiving as props"
  - "combatEventToLogMessage helper moved to AppShell (imperative callback, not context state)"
metrics:
  duration: 40m
  completed: 2026-04-05
  tasks: 2/3 (Task 3 is checkpoint:human-verify)
  files: 25
---

# Phase 01 Plan 02: Big-Bang Migration Summary

GmcpDispatchMap replaces 80+ individual setters with 7 typed dispatch functions; App.tsx slimmed from 1655 to 14 lines; 17 panel components migrated from prop drilling to context consumption.

## What Was Done

### Task 1: Refactor applyGmcpPackage + Create AppShell + Slim App.tsx
**Commit:** 0d438564

**applyGmcpPackage.ts refactored:**
- Replaced `GmcpContext` interface (80+ `Dispatch<SetStateAction<T>>` setters) with `GmcpDispatchMap` (7 typed dispatch functions: `core`, `combat`, `inventory`, `social`, `world`, `quest`, `ui`)
- All 95 GMCP switch cases converted from `ctx.setFoo(value)` to `ctx.domain({ type: "SET_FOO", payload: value })`
- Cross-context dispatches handled explicitly: `Room.Info` clears dialogue+quests, `Shop.Close` closes popout, `Room.UpdateMob` syncs combat target HP, `Room.RemoveMob` clears combat target, `Friends.Online/Offline` updates social+UI
- Imperative callbacks preserved on dispatch map: `pushCombatEvent`, `pushGainEvent`, `pushCraftingResult`, `pushMailNotification`, `updateMap`, `loadZoneMap`, `sendGmcp`

**AppShell.tsx created (1042 lines):**
- Absorbs all layout, callbacks, effects, and GMCP wiring from App.tsx
- Uses `useCoreState()`, `useCombatState()`, etc. instead of 87 useState calls
- Builds `GmcpDispatchMap` from dispatch hooks + utility callbacks via `useMemo`
- `resetHud` dispatches `{ type: "RESET" }` to all 7 contexts + resets imperative refs

**App.tsx slimmed to 14 lines:**
- Only contains `GameStateProvider` wrapping `AppShell`
- Zero useState calls, zero useCallback, zero useEffect

**Context incremental actions added (Deviation Rule 2):**
- `InventoryContext`: ADD_INVENTORY_ITEM, REMOVE_INVENTORY_ITEM
- `CombatContext`: UPDATE_SKILL_COOLDOWN, UPDATE_COMBAT_TARGET_HP, CLEAR_COMBAT_TARGET_IF
- `QuestContext`: SET_QUESTS_AND_FILTER_AVAILABLE, UPDATE_QUEST_OBJECTIVE, REMOVE_QUEST, DISMISS_QUEST_NOTIFICATION
- `SocialContext`: UPDATE_FRIEND_ONLINE, UPDATE_FRIEND_OFFLINE
- `UIContext`: MERGE_LEADERBOARD, CLOSE_POPOUT_IF
- `WorldContext`: UPDATE_MOB changed from full replace to merge (`{ ...m, ...action.payload }`), ADD_PLAYER deduplication

### Task 2: Migrate All Panel Components to Context Consumption
**Commit:** 8aebf78e

17 components migrated from prop drilling to context hooks:
- **CharacterPanel**: reads from all 7 contexts, derives xpText/visibleEffects/displayRace locally
- **InventoryPanel**: useInventoryState, useWorldState, useUIState, useCoreState
- **EquipmentPanel**: useInventoryState, useCoreState
- **ChatPanel**: useSocialState, useCoreState, useUIState
- **QuestPanel**: useQuestState, useCoreState
- **CraftingPanel**: useInventoryState, useCoreState
- **BankPanel**: useInventoryState
- **AuctionPanel**: useInventoryState
- **LeaderboardPanel**: useUIState
- **HousingPanel**: useUIState, useWorldState, useCoreState
- **MailPanel**: useUIState, useCoreState
- **AdminPanel**: useUIState, useSocialState
- **CombatLog**: useCombatState (props interface removed entirely)
- **PlayPanel**: combatLogMessages prop removed (CombatLog reads from context)
- **ActionBar**: uses 6 contexts for state; keeps callback/ref props
- **ShopPopout**: useInventoryState, useCoreState
- **AppShell JSX**: no longer passes state props like vitals, inventory, combatTarget to children

### Task 3: Visual Regression Verification (PENDING - checkpoint:human-verify)
Human verification needed to confirm zero visual/functional regressions.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing functionality] Added incremental reducer actions**
- **Found during:** Task 1
- **Issue:** applyGmcpPackage uses functional updates (e.g., `setInventory(prev => [...prev, item])`, `setMobs(prev => prev.map(...))`) that cannot be expressed with simple SET actions
- **Fix:** Added ADD_INVENTORY_ITEM, REMOVE_INVENTORY_ITEM, UPDATE_SKILL_COOLDOWN, UPDATE_COMBAT_TARGET_HP, CLEAR_COMBAT_TARGET_IF, SET_QUESTS_AND_FILTER_AVAILABLE, UPDATE_QUEST_OBJECTIVE, REMOVE_QUEST, DISMISS_QUEST_NOTIFICATION, UPDATE_FRIEND_ONLINE, UPDATE_FRIEND_OFFLINE, MERGE_LEADERBOARD, CLOSE_POPOUT_IF
- **Files modified:** CombatContext.tsx, InventoryContext.tsx, QuestContext.tsx, SocialContext.tsx, UIContext.tsx, WorldContext.tsx
- **Commit:** 0d438564

**2. [Rule 2 - Missing functionality] WorldContext UPDATE_MOB merge semantics**
- **Found during:** Task 1
- **Issue:** UPDATE_MOB did full replacement but Room.UpdateMob only provides partial data; old code used `{ ...mob, ...updates }` merge pattern
- **Fix:** Changed reducer to `{ ...m, ...action.payload }` merge; also added ADD_PLAYER deduplication
- **Files modified:** WorldContext.tsx
- **Commit:** 0d438564

## Verification Results

- TypeScript compilation: PASS (zero errors via `bunx tsc --noEmit`)
- App.tsx line count: 14 (under 100 limit)
- App.tsx useState count: 0
- GmcpDispatchMap present in applyGmcpPackage: YES
- GmcpContext removed from applyGmcpPackage: YES
- SetStateAction removed from applyGmcpPackage: YES
- All 7 key panels have useContext imports: CONFIRMED
- AppShell has no vitals={vitals} or inventory={inventory}: CONFIRMED
- RESET dispatched to all 7 contexts in resetHud: CONFIRMED
- Vite build: NOT TESTED (Node.js v24 / Vite 7.3.1 rollup resolution issue in worktree environment -- unrelated to code changes)
- ESLint: NOT TESTED (missing transitive dependencies in worktree -- unrelated to code changes)

## Known Stubs

None. All GMCP cases are wired to dispatch actions. All panels consume state from context.

## Self-Check: PASSED

All created files exist. Both task commits verified in git log.
