# Web Client Feature Parity Report (Consolidated)

**Date:** 2026-03-27
**Source:** Three independent parity studies merged and deduplicated.
**Scope:** All MUD text commands/features vs web-v3 client UI and GMCP coverage.

---

## Executive Summary

The web client covers the core gameplay loop well — navigation, combat, inventory/equipment, spells, quests, dialogue, shops, and chat. However, several full feature systems have **no web UI or GMCP support** (mail, crafting, world features), many others have **partial UI requiring text-command fallback** for management actions (guilds, groups, friends, titles), and a handful of **GMCP protocol issues** prevent data from reaching the client correctly.

---

## 1. GMCP Protocol Fixes

These are server/client protocol bugs or mismatches — high impact, low risk.

### 1.1 Zone.Map support-check handshake broken for WebSocket

`GmcpEmitter.sendZoneMap()` uses `supportCheck = "Room"`, but WebSocket auto-support advertises `"Room.Info 1"` (not root `"Room 1"`). With prefix-match semantics, `"Room"` doesn't match `"Room.Info"` — so `Zone.Map` is silently dropped for web sessions.

**Fix:** Either advertise `"Room 1"` in WebSocket `Core.Supports.Set`, or change the support check to `"Room.Info"`.

### 1.2 Room.MobInfo missing fields

`MobInfoEntry` has `questAvailable`, `questComplete`, `aggressive`, but `RoomMobInfoPayload` omits them — only sends `id`, `level`, `tier`, `questGiver`, `shopKeeper`, `dialogue`. Web client defaults these when missing.

**Fix:** Include the three missing fields in the emitted payload.

### 1.3 Char.Combat.Event field-name mismatch

Server payload uses `amount` and `remaining`; web client reads `healing` and `shieldRemaining`. Healing/shield visuals and combat-log details can be incomplete.

**Fix:** Standardize on one naming convention across server and web client.

### 1.4 Core.Ping not handled by web client

`GmcpEmitter` emits `Core.Ping` but `applyGmcpPackage.ts` has no case for it.

**Fix:** Add `Core.Ping` handler; opportunity for connection-health/latency indicator.

### 1.5 Char.Gain fields underutilized

Server emits optional `newLevel`, `hpGained`, `manaGained` fields. Web client only reads `type`, `amount`, `source`.

**Fix:** Consume the additional fields for richer level-up and resource-gain feedback.

---

## 2. Discoverability Gaps

### 2.1 Command autocomplete incomplete

`web-v3/src/constants.ts` command completion list is narrower than the full parser surface. Missing families: mail, crafting, world features, guild/group management, friends, phase, title, gender.

### 2.2 In-app Help underrepresents command families

`HelpContent` doesn't cover mail, crafting, world-feature/container verbs, and several other supported families.

---

## 3. Full Feature Gaps (No GMCP + No Web UI)

These systems work via text commands but are invisible to the web client.

### 3.1 Mail System

**Commands:** `mail list/read/delete/send/abort`
**GMCP needed:** `Mail.List`, `Mail.Message`, `Mail.Notification` (and compose-state feedback).
**Web UI needed:** Mail panel with inbox, message viewer, compose form, delete action.
**Note:** Multi-step compose (`mail send`, line capture, `.` to finish) is especially poor UX via generic command entry.

### 3.2 Crafting & Gathering System

**Commands:** `gather`, `craft`, `recipes`, `craftskills`/`professions`
**GMCP needed:** `Crafting.Skills`, `Crafting.Recipes`, `Crafting.Nodes`, `Crafting.Result`.
**Web UI needed:** Crafting panel with recipe browser, gather button on room nodes, skill progression display.

### 3.3 World Features (Doors, Containers, Levers, Signs)

**Commands:** `open`, `close`, `unlock`, `lock`, `pull`, `read`, `search`, `get <item> from <container>`, `put <item> in <container>`
**GMCP needed:** `Room.Features` (interactive feature state, lock/key requirements, container contents, sign text).
**Web UI needed:** Contextual action buttons on room features — click door to open/close, click sign to read, click container to search/open.

### 3.4 Structured Who List

**Commands:** `who`
**GMCP needed:** `Server.Who` with structured player data (name, level, race, class, title, guild, idle).
**Web UI needed:** Currently parses raw terminal text in Who tab — a GMCP package enables proper sorting, filtering, and click-to-tell.

---

## 4. UI-Only Gaps (GMCP Exists, Management UI Missing)

### 4.1 Guild Management

**GMCP available:** `Guild.Info`, `Guild.Members`, `Guild.Chat` — all handled.
**Displayed:** Guild name/tag/rank/MOTD, member list with online status.
**Missing actions:** Create, invite, accept invite, kick, promote, demote, set MOTD, leave, disband.

### 4.2 Group Management

**GMCP available:** `Group.Info` — shows member HP/Mana bars.
**Displayed:** Group tab with leader + members + vitals.
**Missing actions:** Invite, accept/decline invite, leave, kick, list.

### 4.3 Friends Management

**GMCP available:** `Friends.List`, `Friends.Online`, `Friends.Offline` — all handled.
**Displayed:** Friends tab with online/offline status, level, zone.
**Missing actions:** Add friend, remove friend, click-to-tell.

### 4.4 Character Profile Controls (Title & Gender)

**Title:** Achievement titles are sent via `Char.Achievements`, but no UI to set/clear active title.
**Gender:** `gender <option>` command only — no selector in Character panel.

### 4.5 Phase/Instance Selector

**Commands:** `phase`/`layer` supported.
**Missing:** No instance selector UI, no current-instance display, no occupancy information.
**GMCP opportunity:** Instance list + current + occupancy + switch ack/error.

### 4.6 Character Stats Tab

**GMCP available:** `Char.Stats` sends full stat breakdown (STR/DEX/CON/INT/WIS/CHA base+effective, damage range, armor, dodge%).
**State:** Stored in client (`charStats`) but no panel renders it.
**Missing:** Stats tab in CharacterPanel showing attribute table with base vs effective values, derived combat stats.

### 4.7 Score View

**Commands:** `score`/`sc` shows comprehensive character summary.
**Note:** All constituent data is already received via `Char.Vitals`, `Char.Name`, `Char.Stats` — can be composed from existing GMCP without new server changes.

---

## 5. UX Enhancement Gaps

### 5.1 Flee Button

Must type `flee` — needs an action bar button visible when `inCombat` is true.

### 5.2 Recall Button

Must type `recall` — needs a navigation button with cooldown indicator.

### 5.3 Inventory Context Actions

**Missing:** `Use` button for consumable items, `Put` for containers, `Search` for containers.

### 5.4 Emote/Pose Picker

Must type `emote <action>` or `pose <text>` — could have quick-access emote buttons in chat panel.

### 5.5 Combat Target Selector

When multiple mobs present, no tab-targeting or mob-list picker — only click-on-canvas targeting.

### 5.6 Mob Status Effects

`Char.StatusEffects` only shows player's own effects. No way to see enemy buffs/debuffs in battle scene.
**GMCP opportunity:** `Room.MobEffects` or extend `Room.UpdateMob` with active effects.

### 5.7 Contextual Entity Action Enrichment

EntityPopout exists but could be richer based on `Room.MobInfo` flags — e.g., quest-giver shows "View Quests", shop-keeper shows "Browse Shop", hostile mob shows "Attack".

---

## 6. Infrastructure / Regression Prevention

### 6.1 Command-Parity CI Check

Script/test that diffs parser command families vs web command palette/autocomplete/action maps. Prevents future drift.

### 6.2 GMCP Contract Tests

Test that compares `GmcpEmitter` package strings and field schemas against `applyGmcpPackage.ts` cases. Catches field-name mismatches and missing handlers.

### 6.3 Server.Commands Metadata Package

A `Server.Commands` GMCP package providing command manifest (syntax, help, category, privilege) to power dynamic in-client command palette and context-aware UI.

---

## 7. Features with Full Coverage (No Gaps)

| Feature | GMCP Packages | Web UI |
|---------|--------------|--------|
| Navigation & Movement | `Room.Info`, `Zone.Map` | Canvas world scene, minimap, exit buttons |
| Room contents | `Room.Mobs`, `Room.Items`, `Room.Players`, `Room.MobInfo` | Canvas entities with labels, HP bars, role badges |
| Combat | `Char.Combat`, `Char.Combat.Event`, `Char.Vitals` | Battle scene, combat log, HP/Mana bars, target card |
| Spells & Abilities | `Char.Skills`, `Char.Cooldown` | Spellbook panel, quickbar with cooldown sweeps |
| Status Effects | `Char.StatusEffects` | Character panel effects tab, battle scene indicators |
| Inventory | `Char.Items.List/Add/Remove` | Inventory panel with wear/give/drop actions |
| Equipment | `Char.Equipment.Slots` | Paperdoll equipment panel with remove action |
| Quests | `Quest.List/Update/Complete/Available` | Quest panel with accept/abandon, progress tracking |
| Dialogue | `Dialogue.Node/End` | Canvas dialogue overlay with clickable choices |
| Shops | `Shop.List/Close` | Shop popout with buy/sell tabs |
| Chat (all channels) | `Comm.Channel` | Chat panel with say/tell/gossip/shout/ooc/gtell/gchat |
| Achievements | `Char.Achievements` | Character panel achievements tab with progress bars |
| XP/Gold gains | `Char.Gain` | Floating gain notifications |
| Login flow | `Login.Prompt/Error` | Login modal with race/class/gender selection |
| Admin/Staff tools | `Staff.WorldInfo` | Admin panel with zone/room browser, teleport |

---

## 8. Summary Matrix

| # | Feature | Has Text Cmd | Has GMCP | Has Web UI | Gap Type | Issue |
|---|---------|:---:|:---:|:---:|----------|-------|
| 1 | Zone.Map handshake | — | Broken | Ready | Protocol fix | #667 |
| 2 | Room.MobInfo fields | — | Partial | Ready | Protocol fix | #668 |
| 3 | Combat.Event names | — | Mismatched | Mismatched | Protocol fix | #669 |
| 4 | Core.Ping | — | Emitted | Missing | Protocol fix | #670 |
| 5 | Char.Gain fields | — | Emitted | Partial | Protocol fix | #671 |
| 6 | Command autocomplete | Y | N/A | Partial | Discoverability | #672 |
| 7 | Help coverage | Y | N/A | Partial | Discoverability | #673 |
| 8 | Mail | Y | **N** | **N** | Full gap | #674 |
| 9 | Crafting/Gathering | Y | **N** | **N** | Full gap | #675 |
| 10 | World features | Y | **N** | **N** | Full gap | #676 |
| 11 | Who (structured) | Y | **N** | Partial | Full gap | #677 |
| 12 | Guild management | Y | Partial | **N** | UI gap | #678 |
| 13 | Group management | Y | Y | **N** | UI gap | #679 |
| 14 | Friends management | Y | Y | **N** | UI gap | #680 |
| 15 | Title/gender controls | Y | Partial | **N** | UI gap | #681 |
| 16 | Phase/instance selector | Y | **N** | **N** | UI gap | #682 |
| 17 | Character stats tab | Y | Y | **N** | UI gap | #683 |
| 18 | Score view | Y | Composable | **N** | UI gap | #684 |
| 19 | Flee button | Y | N/A | **N** | UX gap | #685 |
| 20 | Recall button | Y | N/A | **N** | UX gap | #686 |
| 21 | Inventory use/put/search | Y | N/A | **N** | UX gap | #687 |
| 22 | Emote picker | Y | N/A | **N** | UX gap | #688 |
| 23 | Combat target selector | Y | N/A | **N** | UX gap | #689 |
| 24 | Mob status effects | — | **N** | **N** | UX gap | #690 |
| 25 | Entity action enrichment | — | Partial | Partial | UX gap | #691 |
| 26 | Command-parity CI | — | — | — | Infra | #692 |
| 27 | GMCP contract tests | — | — | — | Infra | #693 |
| 28 | Server.Commands package | — | **N** | **N** | Infra | #694 |
