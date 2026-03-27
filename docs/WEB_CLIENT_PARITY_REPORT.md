# Web Client Feature Parity Report (Consolidated)

*Generated 2026-03-27 — merged from three independent reviews*

## Executive Summary

The web client (web-v3) is **remarkably comprehensive**. It handles all 51 GMCP packages, provides rich UI panels for nearly every game system, and includes canvas-rendered combat animations, minimap, and dialogue overlays. The remaining gaps are primarily **interaction model gaps** (text-only workflows in an otherwise visual client) and **manifest-driven UX gaps** (static help that can drift from the server's canonical command list).

**Overall parity: ~90%** — all major systems are present; the gaps below represent polish and productization work.

---

## 1. Command Coverage

### Fully Supported (Text + Rich UI)

These commands work via text input AND have dedicated UI interactions:

| Category | Commands | Web UI |
|----------|----------|--------|
| Navigation | move, look, exits, recall | Minimap click-to-move, exit list, Recall button on canvas |
| Combat | kill, flee, cast | Target picker, Flee button, quickbar/spellbook ability grid |
| Items | get, drop, wear, remove, use, give, inventory, equipment | InventoryPanel with wear/drop/give/use buttons, EquipmentPanel paperdoll |
| Shops | shop/list, buy, sell | ShopPopout with buy/sell tabs |
| Dialogue | talk, dialogue choices, accept | DialogueOverlay with clickable choices + quest accept cards |
| Quests | quest log/list/info/abandon, accept | QuestPanel with active/available tabs, accept/abandon buttons |
| Achievements | achievements | CharacterPanel achievements section |
| Groups | group invite/accept/leave/kick/list, gtell | ChatPanel Group tab |
| Guilds | guild create/disband/invite/accept/leave/kick/promote/demote/motd/roster/info, gchat | ChatPanel Guild tab |
| Friends | friend list/add/remove | ChatPanel Friends tab |
| Mail | mail list/read/delete | MailPanel with inbox, read, delete |
| Crafting | gather, craft, recipes | CraftingPanel with professions, recipes, nodes |
| Sprites | sprite list/set/default | CharacterPanel sprite selector |
| Communication | say, tell, gossip, shout, ooc, emote, who | ChatPanel with channel tabs + Who tab |
| World features | open, close, unlock, pull, read, search | Room features panel with context-sensitive buttons |
| Admin | goto, transfer, spawn, kick, shutdown | AdminPanel with zone browser, player tools |
| Character | score, gender, title | CharacterPanel with stats, gender/title selectors |
| Progression | spells/abilities, effects, balance | SpellbookPanel, status effects display, gold in vitals |
| Sharding | phase | Zone instances UI with phase-switch callback |

### Text-Only or Hybrid (No Full UI Shortcut)

These commands work via text input but lack a clickable UI element, or have only partial UI support:

| Command | Notes | Suggestion |
|---------|-------|------------|
| `look <target>` | EntityPopout has "Look" for mobs/players on canvas, but **inventory items and ground items** have no examine button | Add "Examine" button to inventory items and ground items |
| `look <direction>` | No UI to peek in a direction without moving | Minimap hover tooltips or peek action on exit labels |
| `whisper <player> <msg>` | Handled as Comm.Channel, but no dedicated whisper UI — must type command | Add whisper option to player context menus |
| `pose <msg>` | Documented in help, no quick-action button | Low priority — niche RP command |
| `mail send` / compose | **Hybrid workflow**: UI collects recipient via panel, but message body is written in the terminal with dot-termination. No in-panel body editor. | Add full in-panel compose with subject/body editor + send/abort buttons |
| `mail abort` | No explicit UI affordance — user can click "Back" in compose panel but this doesn't issue `mail abort` to cancel server-side compose state | Add explicit abort button that sends `mail abort` |
| `lock <door/container>` | Room features panel has open/close/unlock but **no lock button** | Add Lock button when feature supports locking |
| `put <item> in <container>` | Help documents it, InventoryPanel shows containers, but no "put" button | Add "Put in..." button on inventory items when containers are open |
| `craftskills` | CraftingPanel shows "Type `craftskills` to load" instead of having a button | Add a "Load Skills" / "Refresh" button |
| `dispel <target>` | Staff command, no admin UI | Add to AdminPanel actions |
| `setlevel <player> <level>` | Staff command, no admin UI | Add to AdminPanel player actions with level input |
| `reload [scope]` | Staff command, no admin UI | Add "Reload" button with scope selector |
| `smite <target>` | Staff command, has text but no UI button | Add to AdminPanel mob/player context actions |

---

## 2. GMCP Coverage

### Reception Coverage

All 51 server GMCP packages are handled by the client's `applyGmcpPackage.ts`. No server-emitted packages are ignored. **100% GMCP reception coverage.**

### GMCP Opportunities (New or Enhanced Packages)

| Proposed Package / Enhancement | Purpose | Benefit |
|-------------------------------|---------|---------|
| `Room.LookTarget` | Structured result of `look <target>` | Rich inspect popovers for items, mobs, players with images/stats instead of plain text |
| `Char.Items.Update` | Delta update for a single inventory item (e.g., charges) | More efficient than re-sending full list |
| `Char.Recall` | Recall room info (name, zone) | Let UI show where Recall leads |
| `Char.Titles` | Dedicated list of all earned titles | Cleaner than deriving from achievement data |
| `Group.Invite` / `Guild.Invite` | Structured pending invite events | Power accept/decline UI cards instead of relying on text output + command loops |
| `Mail.ComposeState` | Structured compose envelope (recipient, draft body, state) | Enable full in-panel compose/send/abort without terminal fallback |
| `UI.Feedback` enhancement | Add stable machine-readable `code`, `scope`, and `command` fields | Reduce string-parsing dependence; enable contextual error UX |
| `Server.Commands` enrichment | Add `uiActionType`, `requiresTarget`, `requiresContext` fields | Reduce hardcoded web logic; enable manifest-driven action discovery |
| `Server.Who` periodic push | Optional auto-refresh or diff events for who list | Keep social panels fresh without manual refresh |

---

## 3. Help & Discoverability Gaps

### Static Help Drift Risk

`HelpContent.tsx` hardcodes all command categories and descriptions. The server already sends `Server.Commands` GMCP with `name`, `usage`, `category`, and `staff` fields, but this metadata is only used for autocomplete — not for rendering help.

**Recommendation:** Make the web help panel **data-driven from `Server.Commands`**, with a minimal local fallback for pre-login. This eliminates drift by construction.

### Specific Help Content Issues

| Issue | Details |
|-------|---------|
| **Missing sprite commands** | `sprite list`, `sprite set`, `sprite default` not documented in HelpContent.tsx |
| **`search` description is misleading** | Help says "Search the area for hidden items" but the parser command is `search <container>` — should be "Search a container for its contents" |
| **`look <target>` not called out** | Help documents `look` as "Look around the room (or look <direction>)" but doesn't mention examining specific targets |

### Command Palette Opportunity

Consider adding a searchable **"Advanced Commands" quick palette** (Ctrl+K or similar) populated from `Server.Commands`. This would make all commands discoverable for visual-first players without cluttering the UI with buttons for rarely-used commands.

---

## 4. UI Polish Opportunities

### 4.1 Inventory & Ground Item Inspection

**Gap:** No way to examine individual items from the UI. Players must type `look <item>`.

**Suggestion:** Add info/inspect button on inventory items and ground items. Ideally backed by a new `Room.LookTarget` GMCP for structured data.

### 4.2 Player Context Actions

**Gap:** Other players in the room are listed via `Room.Players` but interaction options are limited. No quick buttons for whisper, give, tell, look, or group invite.

**Suggestion:** Add a player context menu with actions: Look, Tell, Whisper, Give, Group Invite, Friend Add.

### 4.3 Mob Context Enrichment

**Gap:** EntityPopout shows Look/Kill/Talk for mobs but doesn't leverage `Room.MobInfo` metadata.

**Suggestion:** Use questGiver, shopKeeper, dialogue flags to show context-appropriate actions: "Shop" for shopkeepers, "Talk" with quest icon for quest givers.

### 4.4 Mail Compose Modernization

**Gap:** Mail compose is a hybrid workflow — UI collects recipient, but body is authored in the terminal with dot-termination. This is the most visible parity gap in an otherwise polished panel system.

**Suggestion:** Add a full in-panel body editor with Send and Abort buttons. Back with a new `Mail.ComposeState` GMCP, or at minimum send `mail abort` when the user cancels compose.

### 4.5 Lock Button for Doors/Containers

**Gap:** Room features panel has Open, Close, Unlock, Pull, Read, Search but no **Lock** button.

**Suggestion:** Add Lock button when door/container supports locking.

### 4.6 Container "Put" Action

**Gap:** No UI to put items into an open container from inventory.

**Suggestion:** Show "Put in [container]" action on inventory items when a container is open.

### 4.7 Crafting Skills Refresh

**Gap:** CraftingPanel displays "Type `craftskills` to load" rather than providing a button.

**Suggestion:** Add a "Refresh" button that issues `craftskills`.

### 4.8 Group/Guild Invite Cards

**Gap:** Group and guild invite flows rely on text output. No structured pending-invite UI.

**Suggestion:** Add GMCP `Group.Invite` / `Guild.Invite` events to power accept/decline cards in the respective panels.

### 4.9 Direction Peeking

**Gap:** `look <direction>` has no UI equivalent.

**Suggestion:** Minimap hover tooltips or exit-label peek actions showing adjacent room info.

---

## 5. Admin Panel Gaps

The AdminPanel supports goto, transfer, spawn, kick, and shutdown. Missing staff commands:

| Command | Suggestion |
|---------|------------|
| `smite <target>` | Add to mob/player context actions in admin mode |
| `setlevel <player> <level>` | Add to AdminPanel player actions with level input |
| `dispel <target>` | Add to AdminPanel player/mob actions |
| `reload [scope]` | Add "Reload" button with scope selector (world/abilities/effects/all) |

---

## 6. Priority Recommendations

### Phase 1 — High Impact, Low Risk

1. **Make help panel data-driven from `Server.Commands`** — eliminates drift by construction; keep local fallback for pre-login only.
2. **Fix `search` help description** — change to "Search a container for its contents" to match parser contract.
3. **Add sprite commands to help** — `sprite list`, `sprite set`, `sprite default`.
4. **Item inspection UI** — examine/inspect button for inventory and ground items.
5. **Player context menu** — Tell, Whisper, Give, Group Invite, Look on player names.
6. **Crafting "Refresh" button** — replace "type `craftskills` to load" hint with a button.
7. **Staff panel: add setlevel, dispel, reload, smite** controls.

### Phase 2 — Interaction Parity Hardening

8. **Mail compose modernization** — full in-panel body editor with Send/Abort buttons.
9. **Container "Put" action** from inventory panel.
10. **Lock button** on room features panel.
11. **Mob context enrichment** — leverage MobInfo for shop/quest/dialogue actions.
12. **Mail abort button** — explicit cancel that sends `mail abort` to server.

### Phase 3 — GMCP Contract Maturity

13. **Group/Guild invite GMCP events** — structured pending invite cards.
14. **`Room.LookTarget` GMCP** — structured examine results for rich popovers.
15. **`UI.Feedback` standardization** — add machine-readable codes and context fields.
16. **`Server.Commands` enrichment** — add actionability metadata for manifest-driven UI generation.
17. **Command palette** — searchable launcher populated from `Server.Commands`.
18. **Parity CI checks** — automated tests diffing parser command list vs web command manifest.

---

## 7. Acceptance Criteria for "Full Parity"

Full web client feature parity can be declared when:

1. Every non-staff parser command has at least one discoverable web affordance (direct UI control or command palette entry with usage hints).
2. Every staff parser command has a staff-gated web affordance.
3. Help content is generated from server command metadata (no static drift possible).
4. No gameplay workflow requires terminal text entry when a panel-based alternative exists (mail compose is the current exception).
5. GMCP package matrix shows 100% coverage for all gameplay loops.

---

## 8. Summary Table

| Area | Server Commands | Web UI Coverage | Gap Count |
|------|----------------|-----------------|-----------|
| Navigation | 6 | 6/6 (100%) | 0 |
| Communication | 8 | 7/8 (88%) | Whisper UI |
| Items | 8 | 6/8 (75%) | Examine, Put |
| Combat | 3 | 3/3 (100%) | 0 |
| Progression | 5 | 5/5 (100%) | 0 |
| Quests & Dialogue | 6 | 6/6 (100%) | 0 |
| Achievements & Titles | 4 | 3/4 (75%) | Pose UI |
| Shops | 3 | 3/3 (100%) | 0 |
| World Features | 7 | 5/7 (71%) | Lock, search drift |
| Groups | 5 | 5/5 (100%) | 0 (invite UX improvable) |
| Guilds | 11 | 11/11 (100%) | 0 (invite UX improvable) |
| Crafting | 4 | 3/4 (75%) | craftskills button |
| Friends | 3 | 3/3 (100%) | 0 |
| Mail | 5 | 3/5 (60%) | Compose body, abort |
| Sprites | 3 | 3/3 (100%) | Help docs only |
| Sharding | 1 | 1/1 (100%) | 0 |
| Staff | 9 | 5/9 (56%) | 4 admin cmds |
| Utility | 7 | 7/7 (100%) | 0 |
| **GMCP Packages** | **51 emitted** | **51/51 handled** | **0** |

**All commands remain accessible via text input.** The gaps above concern missing *clickable UI shortcuts* and *hybrid workflows* that force users back to the terminal for part of a flow.
