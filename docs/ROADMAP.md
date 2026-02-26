# AmbonMUD — Roadmap & Future Projects

This document outlines planned features, completed work, and strategic next steps for AmbonMUD's development.

---

## Current State (February 2026)

AmbonMUD has a **mature infrastructure** and **solid gameplay foundation**:

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
✅ Swarm load-testing module

### Gameplay
✅ 4 races, 4 classes, 6 primary attributes
✅ **102 class-specific abilities** (25+ per class across 50 levels)
✅ Status effects (DoT, HoT, STAT_BUFF/DEBUFF, STUN, ROOT, SHIELD)
✅ Group/party system with N:M threat tables
✅ Items (equippable + consumable)
✅ Gold currency + mob drops + shops
✅ Rich communication (say/tell/gossip/emote/etc.)
✅ NPC dialogue trees + behavior tree AI
✅ Individual mob respawn timers
✅ HP/mana regen
✅ Zone resets
✅ Quest system (Phase 1: basic tracking)
✅ Achievement system + titles
✅ Web-based admin dashboard

**Test coverage:** 78 test files covering all systems.

---

## Completed Projects

### Phase A — Combat & Ability System

| Project | Status | Highlights |
|---------|--------|-----------|
| Status Effects (#1) | ✅ Done | DoT, HoT, STAT_BUFF/DEBUFF, STUN, ROOT, SHIELD; configurable stacking |
| Group/Party Combat (#5) | ✅ Done | N:M combat, threat tables, group XP/loot distribution |
| 102 Abilities (Feb 2026) | ✅ Done | 25+ per class, levels 1–50, config-driven |

### Phase B — Living World

| Project | Status | Highlights |
|---------|--------|-----------|
| NPC Dialogue & Behaviors (#2) | ✅ Done | Dialogue trees, behavior tree AI (aggro, patrol, wander, coward) |
| Quest System (#3) | ✅ Done (Phase 1) | Objectives, rewards, quest log, persistence |
| Economy & Shops (#4) | ✅ Done (core) | Gold persistence, mob drops, buy/sell/list commands |
| Achievements & Titles (#11) | ✅ Done | Categories, hidden achievements, cosmetic titles |

---

## Planned Projects

### Phase C — Endgame & Replayability

| # | Project | Effort | Status | Key Features |
|---|---------|--------|--------|--------------|
| **6** | Procedural Dungeons | Very large | ⏳ Pending | Randomized layouts, difficulty scaling, boss encounters, replayable content |
| **7** | Crafting & Gathering | Medium-large | ⏳ Pending | Material nodes, recipes, skill levels, quality tiers, economic depth |

**Unlocks:** Infinite replayable content, non-combat progression, economic loops.

---

### Phase D — Community & Polish

| # | Project | Effort | Status | Key Features |
|---|---------|--------|--------|--------------|
| **10** | Auto-Map & Enhanced Web Client | Medium | ⏳ Pending | Spatial map rendering, ability buttons, chat panels, mobile layout |
| **13** | Social Systems (Guilds/Friends/Mail) | Large | ⏳ Pending | Guild hierarchy, friends list, offline mail with attachments |
| **12** | Player Housing | Medium-large | ⏳ Pending | Personal rooms, furniture, access control, persistent storage |

**Unlocks:** Player retention, community engagement, modern UX.

---

### Phase E — Builder & Operator Tooling

| # | Project | Effort | Status | Key Features |
|---|---------|--------|--------|--------------|
| **8** | OLC / World Builder | Very large | ⏳ Pending | In-game room/mob/item/zone creation, real-time editing, zero-restart iteration |
| **9** | Persistent World State & Events | Medium-large | ⏳ Pending | Doors, levers, containers, server events, seasonal content, world flags |
| **14** | Admin Dashboard | Large | ✅ Partial | Player lookup, metrics, basic controls; enhancement opportunities remain |

**Unlocks:** Rapid content creation, dynamic world, operational visibility.

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
- Player-to-player trading with confirmation flow
- Gold sinks (ability training fees, fast-travel costs, item repair)
- Vendor inventory refresh on zone reset
- Gold balance in GMCP `Char.Vitals`

### Achievements
- Stat bonuses per achievement (+1% crit, +5 health, etc.)
- Leaderboards for specific achievements
- Achievement tiers (bronze/silver/gold)
- Community events triggered by achievement milestones

### Admin Dashboard
- Live metrics visualization (Grafana integration or custom charts)
- Advanced world inspector (zone tree, player positions on map)
- Event log viewer (login/logout, combat, level-ups, errors)
- Shard health page (for sharded deployments)
- Config hot-reload (select values without restart)
- Advanced player management (edit quest state, inventory, attributes)
- Persistent audit log

---

## Suggested Priority & Sequencing

### Start Here (High Impact, Medium Effort)

1. **Auto-Map & Enhanced Web Client (#10)** — Highest player-visible impact. Requires no engine changes; all data already flows via GMCP. Dramatically improves UX.
2. **Crafting & Gathering (#7)** — Economic depth, non-combat progression. Medium effort, self-contained.
3. **Persistent World State (#9)** — Enables dynamic content (doors, levers, seasonal events). Foundation for later projects.

### Build Community (Medium Effort, High Retention)

4. **Social Systems (#13)** — Guilds, friends, mail. Enables player-to-player interaction and offline messaging.
5. **Player Housing (#12)** — Personal investment, long-term retention. Builds on economy and persistent state.

### Enable Creators (High Effort, Enables Everything Else)

6. **OLC / World Builder (#8)** — Very large effort, but unlocks rapid content iteration. Builder community can exponentially expand world.
7. **Procedural Dungeons (#6)** — Infinite replayable content. Builds on group combat and status effects.

---

## Dependency Graph

```
Status Effects (#1) [DONE] ──→ Procedural Dungeons (#6) (boss mechanics)
                             ──→ Group Combat (#5) [DONE] (area effects)

NPC Dialogue (#2) [DONE] ──→ Quest System (#3) [DONE] (quest givers)
                            ──→ Economy (#4) [DONE] (vendor NPCs)

Economy (#4) [DONE] ──→ Crafting (#7) (sell crafted items)
                     ──→ Player Housing (#12) (purchase houses)
                     ──→ Guilds (#13) (guild bank)

Quest System (#3) [DONE] ──→ Achievements (#11) [DONE] (quest achievements)

Persistent World State (#9) ──→ Player Housing (#12) (item storage)

Everything else is independent and can start in any order.
```

---

## Performance & Scale Expectations

**Current proven capacity (STANDALONE mode):**
- ~50 concurrent players without degradation
- Full test suite passes in <30 seconds
- Tick latency <10ms (100ms tick window is comfortable)
- Redis integration handles multi-process workloads

**Known scaling limiters:**
- Single-zone performance: Procedural dungeons (#6) with instancing mitigates
- Builder tooling: OLC (#8) is a prerequisite for content velocity
- Player retention: Housing (#12), guilds (#13), crafting (#7) essential for long-term engagement

---

## Long-Term Vision (Beyond Phase E)

**Not currently planned, but possible futures:**

- **PvP systems:** Arena, guild wars, faction conflict
- **Endgame raids:** Multi-group challenges with loot tiers
- **Reputation & faction systems:** Karma tracks, faction-locked content
- **Creature tamers / pets system:** Capture and train companion mobs
- **Permadeath/hardcore mode:** High-risk, high-reward progression
- **Web-based character builder:** Optimize builds before creation
- **Mobile companion app:** Check mail, browse achievements, manage housing

---

## How to Contribute

See [DEVELOPER_GUIDE.md](./DEVELOPER_GUIDE.md) for setup instructions and [ARCHITECTURE.md](./ARCHITECTURE.md) for design principles.

**Quick wins for contributors:**
- Add new abilities (config-driven, no code changes needed)
- Create new zones (YAML world files)
- Enhance the web client UI (xterm.js + GMCP data)
- Improve admin dashboard (expand existing panels)
- Write tests for edge cases

**Reaching out:** Open an issue on GitHub to discuss ideas or claim a project.

---

**Last updated:** February 26, 2026
