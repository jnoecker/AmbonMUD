# Feature Verification Audit

**Date:** 2026-04-04
**Scope:** All features claimed in CLAUDE.md verified against actual implementation (backend, GMCP, frontend, tests)

## Executive Summary

Verified **55+ features** across 9 domains. All backend systems are fully implemented with tests.
The main gaps are in frontend UI coverage (6 systems have GMCP stubs but no UI panels) and
documentation completeness (27+ commands undocumented in the command category table).

| Domain | Features Checked | PASS | PARTIAL | MISSING |
|--------|-----------------|------|---------|---------|
| Combat & Abilities | 6 | 6 | 0 | 0 |
| Progression & Economy | 6 | 4 | 2 | 0 |
| Social & Communication | 7 | 6 | 1 | 0 |
| Quests & Content | 8 | 5 | 3 | 0 |
| Crafting & Trading | 7 | 4 | 3 | 0 |
| Player Features | 7 | 2 | 5 | 0 |
| Infrastructure | 10 | 10 | 0 | 0 |
| Frontend Coverage | — | 25 | 2 | 6 |
| Command System | — | — | — | — |
| **Totals** | **51** | **37** | **14** | **0** |

---

## Verdicts by Feature

### Combat & Abilities — All PASS

| Feature | Backend | GMCP | Frontend | Tests | Verdict |
|---------|---------|------|----------|-------|---------|
| CombatSystem (tick-based, threat, death/respawn) | Complete | Char.Vitals, Char.Combat | Full | CombatSystemTest | **PASS** |
| AbilitySystem (casting, cooldowns, class restrict) | Complete | Char.Skills, Char.Cooldown | Full | AbilitySystemTest | **PASS** |
| StatusEffectSystem (DOT/HOT/stun/root/shield) | Complete | Char.StatusEffects | Full | StatusEffectSystemTest | **PASS** |
| MobSystem (spawn, respawn, drops, registry) | Complete | Room.Mobs/Add/Update/Remove | Full | MobRespawnTest, MobRegistryTest | **PASS** |
| BehaviorTreeSystem (YAML-driven mob AI) | Complete | Implicit (mob movement) | N/A | BehaviorTreeSystemTest | **PASS** |
| PvP / Dueling | Complete | Char.Vitals (pvpKills/Deaths) | Full | PvpCombatTest | **PASS** |

**Notes:**
- Ability prerequisite/skill tree/tier fields exist in schema and GMCP but no abilities in `application.yaml` currently use them.
- Most world mobs use default (stationary) behavior; few have explicit behavior tree YAML.

---

### Progression & Economy — 4 PASS, 2 PARTIAL

| Feature | Backend | GMCP | Frontend | Tests | Verdict |
|---------|---------|------|----------|-------|---------|
| PlayerProgression (XP curve, level-up, class scaling) | Complete | Char.Vitals | Full | PlayerProgressionTest | **PASS** |
| TrainerRegistry (learn/unlock/reset, multiclass) | Complete | Trainer.List, Char.Classes | Full (TrainerPanel) | TrainerRespecTest | **PASS** |
| PrestigeSystem (ranks, perks, XP cost) | Complete | Char.Vitals (prestige fields) | No panel | PrestigeSystemTest | **PARTIAL** |
| CurrencySystem (secondary currencies, quest rewards) | Complete | Char.Currencies | No panel | CurrencySystemTest | **PARTIAL** |
| LeaderboardSystem (7 categories, top-N) | Complete | Leaderboard.Data | Full (LeaderboardPanel) | LeaderboardSystemTest | **PASS** |
| ShopRegistry (buy/sell, pricing, shop YAML) | Complete | Shop.List, Shop.Close | Full (ShopPopout) | CommandRouterShopTest | **PASS** |

**Gaps:**
- **Prestige:** Backend complete with 20 ranks and perks. Data sent via Char.Vitals but no dedicated UI panel for viewing perks/progression.
- **Currencies:** Backend complete with 3 currencies (quest_points, honor, crafting_tokens). GMCP sent but no UI panel to display balances.

---

### Social & Communication — 6 PASS, 1 PARTIAL

| Feature | Backend | GMCP | Frontend | Tests | Verdict |
|---------|---------|------|----------|-------|---------|
| GroupSystem (invite/accept/leave/kick, XP sharing) | Complete | Group.Info, Group.Invite | Full (ChatPanel) | GroupSystemTest (21 tests) | **PASS** |
| GuildSystem (create through gchat, YAML+PG persist) | Complete | Guild.Info/Members/Chat/Invite | Full (ChatPanel) | GuildSystemTest (17 tests) | **PASS** |
| GuildHallSystem (buy/expand/enter/leave) | Complete | Guild.Hall | Full | GuildHallSystemTest | **PASS** |
| FriendsSystem (add/remove, online notifications) | Complete | Friends.List/Online/Offline | Full (ChatPanel) | FriendsSystemTest (16 tests) | **PASS** |
| Mail (send/read/delete/compose, online+offline delivery) | Complete | Mail.List/Message/Notification | Full (MailPanel) | MailHandlerTest (14 tests) | **PASS** |
| Communication (say/tell/whisper/gossip/shout/ooc/pose) | Complete | Comm.Channel | Full (ChatPanel) | CommandRouterBroadcastTest | **PASS** |
| Describe (set/clear/check player description) | Complete | N/A (text-only) | No UI editor | Implicit | **PARTIAL** |

**Gaps:**
- **Describe:** Commands work but there is no UI panel for viewing/editing player descriptions. Staff can `describe check <player>` but regular players only have text commands.

---

### Quests & Content — 5 PASS, 3 PARTIAL

| Feature | Backend | GMCP | Frontend | Tests | Verdict |
|---------|---------|------|----------|-------|---------|
| QuestSystem (accept/abandon/log/rewards) | Complete | Quest.List/Update/Complete/Available | Full (QuestPanel) | QuestSystemTest | **PASS** |
| AchievementSystem (criteria, progress, titles) | Complete | Char.Achievements | Full (CharacterPanel) | AchievementSystemTest | **PASS** |
| DialogueSystem (NPC trees, choices, quest integration) | Complete | Dialogue.Node/End | Full (DialogueOverlay) | DialogueSystemTest | **PASS** |
| DungeonManager (procedural gen, scaling, boss detection) | Complete | N/A (text commands) | Canvas integration | DungeonManagerTest | **PASS** |
| PuzzleSystem (riddle/sequence, rewards) | Complete | N/A (text commands) | Exploration integration | PuzzleSystemTest | **PASS** |
| AutoQuestSystem (session-only bounties) | Complete | Quest.Auto | Stub (no UI panel) | AutoQuestSystemTest | **PARTIAL** |
| DailyQuestSystem (daily/weekly rotation, streaks) | Complete | Quest.Daily/Weekly | Not handled in client | DailyQuestSystemTest | **PARTIAL** |
| GlobalQuestSystem (server-wide cooperative) | Complete | Quest.Global | Stub (no UI panel) | GlobalQuestSystemTest | **PARTIAL** |

**Gaps:**
- **AutoQuest/DailyQuest/GlobalQuest:** All three have complete backend implementations with persistence (daily) or session-only state. GMCP packages are emitted but the web client either stubs them or doesn't handle them yet.

---

### Crafting & Trading — 4 PASS, 3 PARTIAL

| Feature | Backend | GMCP | Frontend | Tests | Verdict |
|---------|---------|------|----------|-------|---------|
| CraftingSystem (gather/craft/recipes/quality/discovery) | Complete | Crafting.Skills/Recipes/Nodes/Result | Full (CraftingPanel) | CraftingSystemTest | **PASS** |
| TradeSystem (initiate/offer/accept/cancel) | Complete | Trade.State | Full (TradePanel) | TradeSystemTest | **PASS** |
| Enchanting (enchant items, station, definitions) | Complete | Crafting.Result (type=enchant) | Partial (no dedicated panel) | EnchantSystemTest | **PASS** |
| AuctionSystem (list/sell/buy/cancel, JSON persist) | Complete | Auction.List | Partial (data received, no panel) | AuctionSystemTest | **PARTIAL** |
| BankSystem (deposit/withdraw gold+items, bank rooms) | Complete | Char.Bank | Stub (no panel) | BankCommandTest | **PARTIAL** |
| LotterySystem (tickets, drawings, jackpot, JSON persist) | Complete | Lottery.Info | Stub (no panel) | LotterySystemTest | **PARTIAL** |
| Gambling (dice, tavern rooms, cooldowns) | Complete | N/A (text-only) | No panel | LotterySystemTest | **PASS** |

**Gaps:**
- **Bank:** GMCP packet sent, client has stub handler but no UI panel. Players can only interact via text commands.
- **Lottery:** GMCP packet sent but no UI panel.
- **Auction:** GMCP data received and state set, but no visible browse/purchase panel.

---

### Player Features — 2 PASS, 5 PARTIAL

| Feature | Backend | GMCP | Frontend | Tests | Verdict |
|---------|---------|------|----------|-------|---------|
| SpriteSystem (registry, loader, chooser, variants) | Complete | Char.Sprites | Full (CharacterPanel sprite chooser) | SpriteRegistryTest, SpriteLoaderTest | **PASS** |
| HousingSystem (buy/expand/describe/invite/guests) | Complete | Housing.Info | Full (HousingPanel) | HousingSystemTest | **PASS** |
| PetSystem (summon, dismiss, name, templates) | Complete | Char.Pet | Stub (no panel) | PetSystemTest | **PARTIAL** |
| ReputationSystem (factions, standings, mob kills) | Complete | Char.Factions | Stub (no panel) | ReputationSystemTest | **PARTIAL** |
| WeatherSystem (per-zone, transitions, types) | Complete | World.Weather | Stub (no panel/effects) | WeatherSystemTest | **PARTIAL** |
| WorldTimeSystem (day/night cycle, periods) | Complete | World.Time | Stub (no display) | WorldTimeSystemTest | **PARTIAL** |
| WorldEventSystem (date-triggered, seasonal) | Complete | World.Events | Stub (no panel) | WorldEventSystemTest | **PARTIAL** |

**Gaps:**
- **Pet/Faction/Weather/Time/Events:** All five have complete backends, emit GMCP correctly, but the web client has no-op stub handlers that discard the data. No UI renders these features.
- **Housing lock/unlock:** Documentation claims lock/unlock commands exist but they are **NOT implemented** in the codebase. The `House` sealed interface has no Lock/Unlock variants.

---

### Infrastructure — All PASS

| Feature | Implementation | Tests | Verdict |
|---------|---------------|-------|---------|
| Telnet transport (BlockingSocketTransport, GMCP negotiation) | Complete | TelnetLineDecoderTest | **PASS** |
| WebSocket transport (Ktor, auto-opt-in for 47 GMCP packages) | Complete | KtorWebSocketTransportTest | **PASS** |
| GMCP (GmcpEmitter: 92 send functions, 86+ client handlers) | Complete | GmcpEmitterTest (50+ tests) | **PASS** |
| Persistence (WriteCoalescing → RedisCache → YAML/Postgres) | Complete | 15+ test files | **PASS** |
| Event bus (Local/Redis/gRPC, interchangeable) | Complete | 6 bus test files | **PASS** |
| OutboundRouter (per-session queues, backpressure, prompt coalescing) | Complete | OutboundRouterTest | **PASS** |
| Sharding (ZoneRegistry, HandoffManager, InterEngineBus, scaling) | Complete | 6 sharding test files | **PASS** |
| gRPC (EngineGrpcServer, ProtoMapper, bidirectional streaming) | Complete | EngineGrpcServerTest | **PASS** |
| Deployment modes (STANDALONE / ENGINE / GATEWAY) | Complete | Mode validation | **PASS** |
| Screen reader support (ANSI stripping, box-drawing replacement) | Complete | ScreenReaderFilterTest | **PASS** |

---

### Frontend Coverage Summary

**Full UI Support (25 systems):**
Character identity, Vitals, Equipment, Inventory, Combat, Skills, Status Effects,
Quests, Achievements, Dialogue, Chat, Shop, Mail, Crafting, Housing, Navigation/Minimap,
NPCs, Trading, Leaderboards, Trainer, Groups, Friends, Auction (data only), Admin/Staff tools, Sprites

**Partial UI Support (2 systems):**
Guild management (metadata only in CharacterPanel), Group invites (notification only)

**No UI Support — GMCP Stubs (6 systems):**
Bank, Pets, Factions/Reputation, World Time, World Weather, World Events

---

### Command System Audit

**138 total Command variants** (106 top-level + 32 nested in sealed interfaces).
All have registered handlers across 36 handler files. No unhandled commands.

**Documentation gaps in command category table:**

The CLAUDE.md command list is accurate for the commands it includes, but omits several categories entirely:

| Undocumented Category | Commands Missing from Docs |
|-----------------------|--------------------------|
| Housing | House (Status, ListTemplates, Buy, Expand, SetTitle, SetDescription, Invite, Kick, Guests) |
| Auction | AuctionList, AuctionSell, AuctionBuy, AuctionCancel |
| Quest extensions | QuestAuto, QuestAutoInfo, QuestAutoAbandon, DailyQuests, WeeklyQuests, GlobalQuestInfo |
| Admin (extras) | SetLevel, Dispel, Reload, Broadcast, Possess, Return, Invis |
| PvP/Duel | Duel, DuelAccept, DuelDecline |
| Pet | PetStatus, PetDismiss, PetName |
| Prestige | Prestige, PrestigeInfo |
| Lottery/Gambling | LotteryInfo, LotteryBuy, Gamble |
| Currencies | Currencies |
| Reputation | Reputation |
| Leaderboard | Leaderboard (standalone; HallOfFame mentioned but aliases same command) |
| World features | OpenFeature, CloseFeature, UnlockFeature, LockFeature, SearchContainer, GetFrom, PutIn, Pull, ReadSign, Answer |
| UI/Utility | Phase, Noop, Unknown, Invalid |

---

## Issues Found

### Documentation Claims Not Implemented

1. **Housing lock/unlock commands** — CLAUDE.md mentions `House (... Lock, Unlock)` but these command variants do not exist in the `House` sealed interface and no handler implements them.

### Frontend Features Not Rendered

These backend systems emit GMCP data that the web client receives but discards (stub handlers):

| System | GMCP Package | Status |
|--------|-------------|--------|
| Bank | Char.Bank | Stub — no panel |
| Pets | Char.Pet | Stub — no panel |
| Factions | Char.Factions | Stub — no panel |
| World Time | World.Time | Stub — no display |
| World Weather | World.Weather | Stub — no effects |
| World Events | World.Events | Stub — no panel |
| Prestige | (via Char.Vitals) | Data available, no detail panel |
| Currencies | Char.Currencies | Data available, no panel |
| Daily/Weekly Quests | Quest.Daily, Quest.Weekly | Not handled in client |

### Documentation Gaps

- ~40 command variants not listed in the CLAUDE.md command category table (all implemented, just undocumented)
- The command category table covers the core commands well but is missing several entire systems
