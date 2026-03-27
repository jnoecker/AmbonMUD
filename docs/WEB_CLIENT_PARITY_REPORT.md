# Web Client Feature Parity Report

*Generated 2026-03-27*

## Executive Summary

The web client (web-v3) is **remarkably comprehensive**. It handles 40+ GMCP packages, provides rich UI panels for nearly every game system, and includes canvas-rendered combat animations, minimap, and dialogue overlays. The remaining gaps are relatively minor — mostly small UI conveniences and a few help-text omissions rather than missing systems.

**Overall parity: ~92%** — all major systems are present; the gaps below represent polish opportunities.

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
| Mail | mail list/read/send/delete/abort | MailPanel with inbox, read, compose |
| Crafting | gather, craft, recipes, craftskills | CraftingPanel with professions, recipes, nodes |
| Sprites | sprite list/set/default | CharacterPanel sprite selector |
| Communication | say, tell, gossip, shout, ooc, emote, who | ChatPanel with channel tabs + Who tab |
| World features | open, close, lock, unlock, pull, read, search | Room features panel with context-sensitive buttons |
| Admin | goto, transfer, spawn, smite, kick, shutdown | AdminPanel with zone browser, player tools |
| Character | score, gender, title | CharacterPanel with stats, gender/title selectors |
| Progression | spells/abilities, effects, balance | SpellbookPanel, status effects display, gold in vitals |
| Sharding | phase | Zone instances UI with phase-switch callback |

### Text-Only (No UI Shortcut)

These commands work via the text input but lack a clickable UI element:

| Command | Notes | Suggestion |
|---------|-------|------------|
| `look <target>` | EntityPopout has a "Look" action for mobs/players, but examining **items on the ground** or **items in inventory** has no look/examine button | Add "Examine" button to inventory items and ground items |
| `look <direction>` | No UI to peek in a direction without moving | Could add direction-peek on minimap hover or long-press exit arrows |
| `whisper <player> <msg>` | Handled as a Comm.Channel message, but no dedicated whisper UI in ChatPanel — user must type the command | Add whisper option to player context menus or room player list |
| `pose <msg>` | Documented in help, but no quick-action button | Low priority — niche RP command |
| `dispel <target>` | Staff command, no admin UI button | Add to AdminPanel actions |
| `setlevel <player> <level>` | Staff command, no admin UI | Add to AdminPanel player actions |
| `reload [world|abilities|effects|all]` | Staff command, no admin UI button | Add to AdminPanel server section |
| `lock <door/container>` | Room features panel has open/close/unlock but **no lock button** | Add Lock button when state is "open" or "unlocked" and key is available |
| `put <item> in <container>` | Help documents it, InventoryPanel shows containers, but no "put" button for items | Add "Put in..." button on inventory items when containers are open |

---

## 2. GMCP Coverage

### Fully Utilized Packages

All 51 server GMCP packages are handled by the client's `applyGmcpPackage.ts`. No server-emitted packages are ignored. This is **100% GMCP reception coverage**.

### Potential GMCP Enhancements (Server-Side Additions)

These are opportunities where new GMCP packages could improve the web client experience:

| Proposed Package | Purpose | Benefit |
|------------------|---------|---------|
| `Char.Score` | Full character sheet data (all stats, bonuses, resistances) as structured GMCP | Currently `score` output is text-only; a GMCP version would let the CharacterPanel render a richer, always-up-to-date character sheet without parsing text |
| `Room.LookTarget` | Structured result of `look <target>` | Would enable rich inspect popovers for items, mobs, players with images/stats instead of plain text |
| `Char.Items.Update` | Update a single inventory item's state (e.g., charges remaining) | Currently the full list must be re-sent; a delta update would be more efficient |
| `Server.Help` | Structured help text for individual commands | Would allow the web client to show contextual help tooltips rather than a static help page |
| `Char.Titles` | List of all earned titles | CharacterPanel title selector currently relies on achievement data; a dedicated package would be cleaner |
| `Char.Recall` | Recall room info (name, zone) | Would let the UI show where Recall will take you |

---

## 3. Help Content Gaps

The `HelpContent.tsx` static help is missing these documented commands:

| Missing from Help | Category |
|-------------------|----------|
| `sprite / sprite list / sprites` | Should be in Character or a new "Cosmetics" category |
| `sprite set <imageId>` | Cosmetics |
| `sprite default / clear / auto` | Cosmetics |
| `look <target>` (examine items/mobs/players) | Already partially documented as "look around" but could call out target examination |

---

## 4. UI Polish Opportunities

### 4.1 Inventory Item Inspection

**Gap:** No way to examine/inspect an individual inventory item from the UI. Players must type `look <itemname>`.

**Suggestion:** Add an info/inspect button (or click handler) on each inventory item row that sends `look <keyword>` and displays the result. Ideally backed by a new `Room.LookTarget` or `Char.Items.Inspect` GMCP package for structured data.

### 4.2 Ground Item Interaction

**Gap:** Room items are displayed via `Room.Items` GMCP but the only interaction is `get`. No way to examine ground items from the UI.

**Suggestion:** Add an "Examine" action alongside the existing "Get" action for ground items in the room popout or a dedicated items panel.

### 4.3 Player Context Actions

**Gap:** Other players in the room are listed (via `Room.Players`) but interaction options are limited. No quick buttons for `whisper`, `give`, `tell`, `look`, or `group invite`.

**Suggestion:** Add a player context menu (click on player name) with actions: Look, Tell, Whisper, Give, Group Invite, Friend Add.

### 4.4 Mob Context Actions on Canvas

**Gap:** The EntityPopout on the canvas shows Look/Kill/Talk for mobs, which is good. But it doesn't show quest indicators or shop access in the popout actions.

**Suggestion:** Use `Room.MobInfo` metadata (questGiver, shopKeeper, dialogue flags) to add context-appropriate actions: "Shop" for shopkeepers, "Talk" with a quest icon for quest givers.

### 4.5 Lock Button for Doors/Containers

**Gap:** The room features panel has Open, Close, Unlock, Pull, Read, and Search buttons but is missing **Lock**. Players who have a key and want to lock a door behind them must type the command.

**Suggestion:** Add a Lock button when the feature state is "open" or "closed" (unlocked) and the door/container supports locking.

### 4.6 Container "Put" Action

**Gap:** When a container is open, players can take items from it (via the container contents UI) but there's no UI to put items *into* a container from inventory.

**Suggestion:** When a container is open, show a "Put in [container]" action on inventory items, or enable drag-drop from inventory to the container contents list.

### 4.7 Direction Peeking

**Gap:** `look <direction>` lets text players peek into adjacent rooms. The web client has no equivalent.

**Suggestion:** Minimap hover tooltips showing the room name/description for adjacent rooms, or a peek action on exit direction labels.

---

## 5. Admin Panel Gaps

The AdminPanel supports goto, transfer, spawn, kick, and shutdown. Missing staff commands:

| Command | Status | Suggestion |
|---------|--------|------------|
| `smite <target>` | Has text command but no UI button | Add to mob/player context actions in admin mode |
| `setlevel <player> <level>` | No UI | Add to AdminPanel player actions with level input |
| `dispel <target>` | No UI | Add to AdminPanel player/mob actions |
| `reload [scope]` | No UI | Add "Reload" button to AdminPanel with scope selector (world/abilities/effects/all) |

---

## 6. Priority Recommendations

### High Priority (Improves core gameplay)

1. **Item inspection UI** — Add examine/inspect action for inventory items, equipment, and ground items. This is the most common missing interaction.
2. **Player context menu** — Quick actions (Tell, Whisper, Give, Group Invite, Look) when clicking player names.
3. **Help content: add Sprite commands** — The sprite system is fully implemented in the UI but undocumented in the help panel.

### Medium Priority (Quality of life)

4. **Container "Put" action** — Let players put items into open containers from the inventory panel.
5. **Lock button** — Add to room features panel for doors/containers.
6. **Mob context enrichment** — Use MobInfo metadata to show shop/quest/dialogue actions in EntityPopout.
7. **Admin panel completeness** — Add setlevel, dispel, reload, smite buttons.

### Low Priority (Nice to have)

8. **Direction peeking** — Minimap hover or exit tooltips.
9. **Whisper UI** — Dedicated whisper channel or button in ChatPanel.
10. **`Char.Score` GMCP** — Structured character sheet data for richer CharacterPanel.
11. **`Room.LookTarget` GMCP** — Structured examine results for popovers.
12. **Pose quick-action** — Emote preset for `/pose`.

---

## 7. Summary Table

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
| World Features | 7 | 6/7 (86%) | Lock |
| Groups | 5 | 5/5 (100%) | 0 |
| Guilds | 11 | 11/11 (100%) | 0 |
| Crafting | 4 | 4/4 (100%) | 0 |
| Friends | 3 | 3/3 (100%) | 0 |
| Mail | 5 | 5/5 (100%) | 0 |
| Sprites | 3 | 3/3 (100%) | Help docs |
| Sharding | 1 | 1/1 (100%) | 0 |
| Staff | 9 | 5/9 (56%) | 4 admin cmds |
| Utility | 7 | 7/7 (100%) | 0 |
| **GMCP Packages** | **51 emitted** | **51/51 handled** | **0** |

**All commands remain accessible via text input** — the gaps above are only about missing *clickable UI shortcuts*. The text-based feature set is at 100% parity by definition since the web client includes a full terminal.
