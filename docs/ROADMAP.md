# AmbonMUD — Roadmap & Future Projects

This document is the feature ledger: what's built, what's closed, and what remains as an enhancement backlog. As of the current release, **every planned phase (A through F) is complete** and the outstanding work is enhancement opportunities rather than milestone items.

---

## Current State (April 2026)

AmbonMUD has a **mature infrastructure** and **complete gameplay foundation**:

### Infrastructure
✅ Event-driven tick engine (100ms)
✅ Dual transports: telnet (NAWS/TTYPE/GMCP) + WebSocket
✅ Event bus abstraction (Local/Redis/gRPC)
✅ Write-behind coalescing persistence
✅ YAML or PostgreSQL backends
✅ Redis L2 cache with HMAC-signed pub/sub
✅ gRPC engine/gateway split
✅ Zone-based sharding + zone instancing
✅ Prometheus/Grafana observability
✅ Snowflake session IDs
✅ Isolated BCrypt auth thread pool (tunable `authThreads`)
✅ Virtual threads for telnet transport (JDK 21 `newVirtualThreadPerTaskExecutor`)

### Gameplay
✅ 4 races, 4 classes + 1 debug class (Swarm), 6 primary attributes (stat definitions data-driven via `StatRegistry`)
✅ **~126 class-specific abilities** — trainer-based learning with skill points; multi-classing available at level 10
✅ Status effects (DoT, HoT, STAT_BUFF/DEBUFF, STUN, ROOT, SHIELD)
✅ Group/party system with N:M threat tables
✅ Items (equippable + consumable) + item enchanting
✅ Gold currency + mob drops + shops + bank NPC + auction house
✅ Player-to-player trading with confirmation flow
✅ Consent-based PvP dueling
✅ Rich communication (say/tell/gossip/emote/etc.)
✅ NPC dialogue trees + behavior tree AI
✅ Individual mob respawn timers
✅ HP/mana regen
✅ Zone resets
✅ Quest system (objectives, rewards, tracking)
✅ Achievement system + titles
✅ Guilds with ranks, guild chat, roster management
✅ Friends list + offline mail
✅ Crafting & gathering with specialization, recipe discovery, quality tiers, rare yields
✅ Player housing (personal rooms, furniture, vaults, access control)
✅ Procedural dungeons (template-driven, instanced, 4 difficulty tiers, boss encounters)
✅ Pet/companion system (SUMMON_PET ability type, level-scaled stats)
✅ Faction & reputation system (7 standing tiers, quest/kill integration)
✅ Day/night cycle, dynamic per-zone weather, seasonal events
✅ Leaderboard system and hall of fame
✅ Web-based admin dashboard
✅ Remember-me auth tokens for persistent login

### Content Creation
✅ **Ambon Arcanum** — standalone desktop creator tool with visual zone editor, room/mob/item/shop editors, class/race designer, config editor, and YAML round-trip preservation

**Test coverage:** ~160 test files covering all systems, plus the integration suite and GMCP contract tests.

---

## Completed Projects

### Phase A — Combat & Ability System

| Project | Status | Highlights |
|---------|--------|-----------|
| Status Effects (#1) | ✅ Done | DoT, HoT, STAT_BUFF/DEBUFF, STUN, ROOT, SHIELD; configurable stacking |
| Group/Party Combat (#5) | ✅ Done | N:M combat, threat tables, group XP/loot distribution |
| Class Ability Catalog (Feb 2026) | ✅ Done | ~25 per class per level band, levels 1–50, config-driven in `engine.abilities.definitions` |

### Phase A.5 — Engine Internals

| Project | Status | Highlights |
|---------|--------|-----------|
| Data-Driven Stats (Mar 2026) | ✅ Done | Full stat system: `StatMap`, bindings, persistence, GMCP, world YAML, web client; see [DATA_DRIVEN_STATS_PLAN.md](./DATA_DRIVEN_STATS_PLAN.md) |
| Hardcoded Config Extraction (Mar 2026) | ✅ Done | `baseHp`, `baseMana`, `startingGold`, `threatMultiplier` all data-driven; see [CREATOR_CONFIG_REFERENCE.md](./CREATOR_CONFIG_REFERENCE.md) |
| Player Sprites (Mar 2026) | ✅ Done | Tier/staff/achievement sprites, `SpriteRegistry`, GMCP `Char.Sprites`, player sprite selection commands; Flyway V19 |
| Admin API (Mar 2026) | ✅ Done | Admin HTTP server with JSON API and HTML dashboard; see [ADMIN_API_REFERENCE.md](./ADMIN_API_REFERENCE.md) |

### Phase B — Living World

| Project | Status | Highlights |
|---------|--------|-----------|
| NPC Dialogue & Behaviors (#2) | ✅ Done | Dialogue trees, behavior tree AI (aggro, patrol, wander, coward) |
| Quest System (#3) | ✅ Done (Phase 1) | Objectives, rewards, quest log, persistence |
| Economy & Shops (#4) | ✅ Done (core) | Gold persistence, mob drops, buy/sell/list commands |
| Achievements & Titles (#11) | ✅ Done | Categories, hidden achievements, cosmetic titles |

### Phase C — Endgame & Replayability

| Project | Status | Highlights |
|---------|--------|-----------|
| Crafting & Gathering (#7) | ✅ Done (Phase 1 + 2) | Phase 1: gather/craft/recipes, skills, workshop zone. Phase 2: rare yields, recipe discovery, specialization (+25% XP), quality tiers (Normal→Masterwork) |
| Procedural Dungeons (#6) | ✅ Done (Mar 2026) | Template-driven instanced dungeons with 4 difficulty tiers (Lore→Heroic), BFS layout generation, party-level + difficulty scaling, boss completion rewards. First dungeon: The Sunken Crypt |

### Phase D — Community & Polish

| Project | Status | Highlights |
|---------|--------|-----------|
| Social Systems (#13) | ✅ Done | Guilds (create/disband/invite/accept/leave/kick/promote/demote/motd/roster/info), guild chat (`gchat`), friends (add/remove/list), offline mail (send/read/delete). Flyway V9 (mail), V11 (guilds), V16 (friends) |
| Auto-Map & Enhanced Web Client (#10) | ✅ Done | PixiJS canvas v4 client: JRPG-style world/battle scenes, HUD panels, crafting/mail/housing UI, GMCP-driven data |
| Player Housing (#12) | ✅ Done (Mar 2026) | Personal rooms, furniture placement, vaults with capacity limits, access control, Housing GMCP + web panel. PRs #802, #805 |

### Phase E — Builder & Operator Tooling

| Project | Status | Highlights |
|---------|--------|-----------|
| Ambon Arcanum (Creator Tool) | ✅ Done | Standalone desktop app: visual zone map editor, room/mob/item/shop editors, class/race designer, config editor, YAML round-trip preservation. Replaces OLC (#8). |
| Persistent World State (#9) | ✅ Done | WorldStateRegistry, persistent door/lever/container state, world features handler |
| Admin Dashboard (#14) | ✅ Done | Player lookup, metrics, admin controls, JSON API |

### Phase F — Economy, Social Depth & World Systems

| Project | Status | Highlights |
|---------|--------|-----------|
| Player-to-Player Trading (#849) | ✅ Done (Apr 2026) | Interactive item and gold transfers with confirmation flow; `trade` command family |
| Auction House (#850) | ✅ Done (Apr 2026) | Player-driven marketplace; persistent listings in `data/auction_listings.json`; `auction` command family |
| PvP Dueling (#851) | ✅ Done (Apr 2026) | Consent-based dueling (`duel` challenge/accept/decline); no item loss |
| Faction & Reputation System (#852) | ✅ Done (Apr 2026) | 7 standing tiers (Hated → Revered); affected by mob kills and quest rewards; enemy faction relationships; Flyway V23 |
| Pet / Companion System (#853) | ✅ Done (Apr 2026) | SUMMON_PET ability type; level-scaled stats; one active pet per player; `pet` command family |
| Item Enchanting (#854) | ✅ Done (Apr 2026) | `enchant`/`enchanting_table` crafting station; stat and damage bonuses; `maxEnchantmentsPerItem` config; Flyway V? |
| Bank NPC System (#855) | ✅ Done (Apr 2026) | Gold and item vault via bank rooms; `deposit`/`withdraw`/`bank`; Flyway V24 |
| Day/Night Cycle, Weather & Seasonal Events (#856) | ✅ Done (Apr 2026) | 24-hour world clock; 6 weather types with per-zone transitions; date-triggered events with flag system; `time` command; `World.Time`/`World.Weather`/`World.Events` GMCP |
| Leaderboards & Hall of Fame (#858) | ✅ Done (Apr 2026) | `leaderboard`/`lb` command; `halloffame` for top historical rankings |
| Trainer-Based Abilities & Multi-Classing | ✅ Done (Apr 2026) | Skill points (1 per 2 levels) spent at class trainers; `train`/`train learn`/`train unlock`; multi-classing from level 10; `Trainer.List` + `Char.Classes` GMCP |

---

## Closed / Not Planned

| # | Project | Resolution |
|---|---------|------------|
| **8** | OLC / World Builder | Replaced by Ambon Arcanum (standalone creator tool). In-game building is not planned. |

---

## Web Client Parity

See [WEB_CLIENT_PARITY_REPORT.md](./WEB_CLIENT_PARITY_REPORT.md) for the full audit. **All gaps have been resolved** — the web client has complete feature parity with the text command interface.

---

## Enhancement Opportunities (Future Iterations)

### Combat & Abilities
- Dispel mechanic (`dispel` command or counter-spell ability type)
- Immunity/resistance windows after crowd control expires
- Area-of-effect abilities with group targeting
- Threat scaling by class (tanks vs. healers)

### NPC Systems
- `CALL_FOR_HELP` behavior (alert nearby mobs)
- `VENDOR` and `TRAINER` behaviors (automatic shop/training interface)
- Guard NPCs gated by quest flags or faction standing
- Conditional aggro (attack only certain classes/levels)

### Quest System
- Quest chains with branching paths and alternate endings
- Optional bonus objectives for extra rewards
- Time-limited quests with failure states
- Dynamic quest scaling by party level

### Economy
- ~~Player-to-player trading with confirmation flow~~ — implemented (PR #849)
- Gold sinks (ability training fees, fast-travel costs, item repair)
- Vendor inventory refresh on zone reset
- Gold balance in GMCP `Char.Vitals`

### Achievements
- Stat bonuses per achievement (+1% crit, +5 health, etc.)
- Leaderboards for specific achievements
- Achievement tiers (bronze/silver/gold)
- Community events triggered by achievement milestones

### Social Systems
- ~~Guild chat~~ — implemented as `gchat` command
- ~~Offline mail~~ — implemented with send/read/delete/list commands
- ~~Friends list~~ — implemented with add/remove/list commands
- ~~Guild bank / shared storage~~ — implemented as bank NPC system (PR #855)
- Mail attachments (items, gold)
- ~~Friend online/offline notifications via GMCP~~ — implemented (`Friends.Online`/`Friends.Offline`)

### Internationalization & Content Packs
- Externalized gameplay message templates with parameter substitution. Currently inline `SendText` / `SendInfo` / `SendError` strings are embedded in Kotlin across the handlers. Moving them to locale/content packs would enable no-code tuning of tone and full localization support. (The only remaining item from the original data-driven gap audit.)

### Admin Dashboard
- Live metrics visualization (Grafana integration or custom charts)
- Advanced world inspector (zone tree, player positions on map)
- Event log viewer (login/logout, combat, level-ups, errors)
- Shard health page (for sharded deployments)
- Config hot-reload (select values without restart)
- Advanced player management (edit quest state, inventory, attributes)
- Persistent audit log

---

## What's Next

All planned phases (A through F) are complete. Future work is driven by the enhancement opportunities above and community feedback. Good starting points for contributors:

- Add new dungeon templates (YAML-driven, see [DUNGEON_TEMPLATE_REFERENCE.md](./DUNGEON_TEMPLATE_REFERENCE.md))
- Add new abilities (config-driven, no code changes needed)
- Create new zones (YAML world files)
- Expand crafting recipes, enchantments, and gathering nodes
- Add new faction definitions and faction-locked content
- Add new seasonal event definitions

---

## Completed Dependency Graph

All planned projects and their dependencies are complete:

```
Status Effects → Group Combat → Procedural Dungeons (boss mechanics)
NPC Dialogue → Quest System → Achievements
Economy → Crafting (Phase 1 + 2) → Player Housing
Persistent World State → World Features (doors, containers, levers)
Ambon Arcanum (replaces OLC) — standalone creator tool
```

---

## Performance & Scale Expectations

**Load-tested capacity (STANDALONE mode, February 2026):**
- **70 sustained concurrent players**, **141 peak sessions** (telnet + WebSocket)
- Engine tick p99 **< 4 ms** against a 100 ms budget — engine is not the bottleneck
- Zero tick overruns at peak load
- JVM heap ~40 MB at 141 sessions; process CPU < 1%
- Full test suite passes in < 30 seconds

**Current throughput ceilings (tunable):**
- Login funnel: `authThreads: 8` + cost-10 BCrypt ≈ 30–80 new logins/sec. Configurable via `login.authThreads` and `login.maxConcurrentLogins`.
- Telnet sessions: now uses JDK 21 virtual threads (PR #313), eliminating platform-thread overhead for concurrent connections.

**All known scaling limiters have been resolved:**
- ~~Telnet transport thread model~~: virtual threads (PR #313)
- ~~Single-zone performance~~: Procedural dungeons (#6) with instancing
- ~~Builder tooling~~: Ambon Arcanum creator tool
- ~~Player retention~~: Housing, guilds, crafting, dungeons all implemented
- ~~Persistent world state~~: WorldStateRegistry with door/lever/container state

---

## Long-Term Vision (Beyond Current Phases)

**Not currently planned, but possible futures:**

- **Endgame raids:** Multi-group challenges with loot tiers
- ~~**PvP systems:** Arena, guild wars, faction conflict~~ — consent-based dueling implemented (PR #851); arena/guild wars remain future
- ~~**Reputation & faction systems:** Karma tracks, faction-locked content~~ — reputation system implemented (PR #852)
- ~~**Creature tamers / pets system:** Capture and train companion mobs~~ — companion pets implemented via SUMMON_PET (PR #853)
- **Permadeath/hardcore mode:** High-risk, high-reward progression
- **Web-based character builder:** Optimize builds before creation
- **Mobile companion app:** Check mail, browse achievements, manage housing

---

## How to Contribute

See [DEVELOPER_GUIDE.md](./DEVELOPER_GUIDE.md) for setup instructions and [ARCHITECTURE.md](./ARCHITECTURE.md) for design principles.

**Quick wins for contributors:**
- Add new dungeon templates (YAML-driven, see [DUNGEON_TEMPLATE_REFERENCE.md](./DUNGEON_TEMPLATE_REFERENCE.md))
- Add new abilities (config-driven, no code changes needed)
- Create new zones (YAML world files)
- Add crafting recipes and gathering nodes
- Enhance the v4 web client UI (React + PixiJS + GMCP data)
- Write tests for edge cases

**Reaching out:** Open an issue on GitHub to discuss ideas or claim a project.

---

**Last updated:** April 2, 2026
