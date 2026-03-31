# Web Client Feature Parity Report (Consolidated)

**Date:** 2026-03-31 (updated)
**Source:** Three independent parity studies merged and deduplicated, then verified against current codebase.
**Scope:** All MUD text commands/features vs web-v3 client UI and GMCP coverage.

---

## Executive Summary

The web client has **near-complete parity** with the text command interface. All core systems — navigation, combat, inventory/equipment, spells, quests, dialogue, shops, chat, mail, crafting, housing, character stats, world features, achievements, titles, emotes, and admin tools — have full GMCP coverage and web UI.

Remaining gaps are limited to **management actions** for social systems (guilds, groups, friends) where data display exists but some action buttons are missing.

---

## 1. Remaining Gaps

### 1.1 Guild Management Actions

**GMCP available:** `Guild.Info`, `Guild.Members`, `Guild.Chat` — all handled.
**Displayed:** Guild name/tag/rank/MOTD, member list with online status.
**Missing actions:** Create, invite, accept invite, kick, promote, demote, set MOTD, leave, disband.

**Issue:** [#814](https://github.com/jnoecker/AmbonMUD/issues/814)

### 1.2 Group Management Actions

**GMCP available:** `Group.Info` — shows member HP/Mana bars.
**Displayed:** Group tab with leader + members + vitals. Invite available via entity popout.
**Missing actions:** Accept/decline invite, leave, kick.

**Issue:** [#815](https://github.com/jnoecker/AmbonMUD/issues/815)

### 1.3 Friends Management Actions

**GMCP available:** `Friends.List`, `Friends.Online`, `Friends.Offline` — all handled.
**Displayed:** Friends tab with online/offline status, level, zone. Add available via entity popout.
**Missing actions:** Remove friend, click-to-tell.

**Issue:** [#816](https://github.com/jnoecker/AmbonMUD/issues/816)

---

## 2. Features with Full Coverage

| Feature | GMCP Packages | Web UI |
|---------|--------------|--------|
| Navigation & Movement | `Room.Info`, `Zone.Map` | Canvas world scene, minimap, exit buttons, recall button |
| Room contents | `Room.Mobs`, `Room.Items`, `Room.Players`, `Room.MobInfo` | Canvas entities with labels, HP bars, role badges |
| World features | `Room.Features` | Contextual action buttons (doors, containers, levers, signs) |
| Combat | `Char.Combat`, `Char.Combat.Event`, `Char.Vitals` | Battle scene, combat log, HP/Mana bars, target card, flee button |
| Spells & Abilities | `Char.Skills`, `Char.Cooldown` | Spellbook panel, quickbar with cooldown sweeps |
| Status Effects | `Char.StatusEffects` | Character panel effects tab, battle scene indicators, mob effects |
| Inventory | `Char.Items.List/Add/Remove` | Inventory panel with use/wear/give/drop/put/examine actions |
| Equipment | `Char.Equipment.Slots` | Paperdoll equipment panel with remove action |
| Quests | `Quest.List/Update/Complete/Available` | Quest panel with accept/abandon, progress tracking |
| Dialogue | `Dialogue.Node/End` | Canvas dialogue overlay with clickable choices |
| Shops | `Shop.List/Close` | Shop popout with buy/sell tabs |
| Chat (all channels) | `Comm.Channel` | Chat panel with say/tell/gossip/shout/ooc/gtell/gchat |
| Emotes | `Comm.Channel` | Emote picker with preset emotes |
| Achievements | `Char.Achievements` | Character panel achievements tab with progress bars |
| Titles | `Char.Achievements` | Title selector in character panel |
| Character Stats | `Char.Stats` | Stats tab with attributes (base vs effective), derived combat stats |
| Score | `Char.Vitals`, `Char.Name`, `Char.Stats` | Score tab composing full character summary |
| XP/Gold gains | `Char.Gain` | Floating gain notifications with level-up details |
| Login flow | `Login.Prompt/Error`, `Session.*` | Login modal with race/class selection, character picker, remember-me |
| Who list | `Server.Who` | Structured who list with sorting and click-to-tell |
| Mail | `Mail.List/Message/Notification` | Mail panel with inbox, message viewer, compose |
| Crafting | `Crafting.Skills/Recipes/Nodes/Result` | Crafting panel with recipe browser, skill display |
| Housing | `Housing.Info/Rooms` | Housing panel with room management |
| Phase/instances | `Zone.Instances` | Instance selector in action bar |
| Admin/Staff tools | `Staff.WorldInfo` | Admin panel with zone/room browser, teleport |
| Commands metadata | `Server.Commands` | Dynamic command palette, autocomplete |
| Connection health | `Core.Ping` | Ping/latency tracking |
| GMCP contract tests | — | GmcpWebContractTest validates server ↔ client field parity |
| Command-parity CI | — | Automated check prevents command drift |

---

## 3. Previously Reported Gaps — Now Resolved

The following items from the original parity studies have been implemented:

- ✅ GMCP protocol: Zone.Map support-check, Room.MobInfo fields, Char.Combat.Event field names, Core.Ping, Char.Gain fields
- ✅ Mail system GMCP + web UI
- ✅ Crafting & gathering GMCP + web UI
- ✅ World features (doors, containers, levers, signs) GMCP + web UI
- ✅ Structured Who list GMCP + web UI
- ✅ Character stats tab
- ✅ Score composite view
- ✅ Title and gender controls
- ✅ Phase/instance selector
- ✅ Flee and recall buttons
- ✅ Inventory context actions (use, put, search)
- ✅ Emote picker
- ✅ Combat target selector (entity popout)
- ✅ Mob status effects in battle scene
- ✅ Entity popout contextual actions (quest giver, shop, dialogue, attack)
- ✅ Command autocomplete and help coverage
- ✅ Command-parity CI check
- ✅ GMCP contract tests
- ✅ Server.Commands metadata package
