# Web Client Feature Parity Report (Consolidated)

**Date:** 2026-04-09 (updated)
**Source:** Three independent parity studies merged and deduplicated, then verified against current codebase.
**Scope:** All MUD text commands/features vs web-v3 client UI and GMCP coverage.

---

## Executive Summary

The web client now has **functionally complete parity** with the text command interface. The original v4 systems still have full GMCP coverage and web UI, and the later gameplay additions that were once tracked as follow-up parity work now ship with first-class panels or structured in-panel flows.

**Systems added after the original v4 parity study that are now covered:**
- Trainer system (skill points, class learning, multi-classing)
- Prestige progression and perks
- Wallet/currencies
- Pet/companion management
- Faction & reputation tracking
- Auction house / player marketplace
- Player-to-player trading
- PvP dueling
- Bank NPC storage
- Lottery
- Procedural dungeon entry/resume flow
- Day/night cycle, weather, and seasonal events
- Leaderboard system and hall of fame
- Staff/admin console with contextual pickers and confirmation flows

---

## 1. Features with Full Coverage

| Feature | GMCP Packages | Web UI |
|---------|--------------|--------|
| Navigation & Movement | `Room.Info`, `Zone.Map` | Canvas world scene, minimap, exit buttons, recall button |
| Room contents | `Room.Mobs`, `Room.Items`, `Room.Players`, `Room.MobInfo` | Canvas entities with labels, HP bars, role badges |
| World features | `Room.Features` | Dedicated feature drawer, canvas badges for doors/containers/levers, contextual action buttons, sign text |
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
| Crafting | `Crafting.Skills/Recipes/Nodes/Result` | Crafting panel with recipe browser, skill display, and structured crafting/enchant result feedback |
| Trainer / Multi-classing | `Trainer.List`, `Char.Classes` | Trainer panel with learn/unlock flows and skill point display |
| Guilds | `Guild.Info/Members/Chat`, `Guild.Invite` | Guild tab with create/invite/accept/promote/demote/kick/MOTD/leave/disband |
| Groups | `Group.Info`, `Group.Invite` | Group tab with HP/Mana bars, invite/accept/decline/kick/leave |
| Friends | `Friends.List/Online/Offline` | Friends tab with add/remove/tell, online notifications |
| Housing | `Housing.Info/Rooms` | Housing panel with room management |
| Pets | `Char.Pet` | Character entry point plus dedicated pet management view |
| Factions & Reputation | `Char.Factions` | Dedicated factions view with standings, descriptions, and recent activity |
| Wallet / Currencies | `Char.Currencies` | Dedicated wallet view with per-currency cards and recent activity |
| Prestige | `Prestige.Info`, `Char.Vitals` | Dedicated prestige view with perks, eligibility, and advancement |
| Lottery | `Lottery.Info` | Dedicated lottery view with jackpot, ticket counts, buy actions, and refresh |
| Auction House | `Auction.List` | Auction panel with browse, list, my listings, buy, and cancel flows |
| Trading | `Trade.State` | Trade panel with item/gold offers and confirmation flow |
| Dueling | `Duel.State`, `Duel.Challenge` | Dedicated duel view with challenge, response, status, and flee actions |
| Bank | `Char.Bank` | Bank panel for gold and item storage |
| Leaderboards | `Leaderboard.Data` | Leaderboard panel with category switching and hall-of-fame coverage |
| Dungeons | `Dungeon.Info`, `Dungeon.Catalog` | Dedicated dungeon view with catalog, difficulty selection, enter, resume, and leave |
| World atmosphere | `World.Time`, `World.Weather`, `World.Events` | Structured world summary in the character/systems experience |
| Phase/instances | `Zone.Instances` | Instance selector in action bar |
| Admin/Staff tools | `Staff.WorldInfo`, `UI.Feedback` | Dedicated admin console with room/player/mob pickers, safety confirmations, and staff feedback |
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
- ✅ Guild management actions (create, invite, accept, promote, demote, kick, MOTD, leave, disband)
- ✅ Group management actions (accept/decline, invite, kick, leave)
- ✅ Friends management actions (add, remove with confirmation, click-to-tell)
- ✅ Command autocomplete and help coverage
- ✅ Command-parity CI check
- ✅ GMCP contract tests
- ✅ Server.Commands metadata package
- ✅ Trainer, prestige, wallet, lottery, pet, faction, dungeon, duel, trade, auction, and bank systems promoted to dedicated web views
- ✅ Staff/admin console expanded from basic teleport tooling to a full operations surface
