# AmbonMUD — Proposed Features & Improvements

This document captures potential directions for extending AmbonMUD. Each proposal includes motivation, a rough scope assessment, and notes on how it fits the existing architecture. These are unordered suggestions, not a committed roadmap.

---

## 1. Per-Mob Stats & Difficulty Tiers

**What:** Give each mob its own HP, min/max damage, armor, XP reward, and an optional `level` field in the zone YAML. Tier definitions (e.g., `weak`, `standard`, `elite`, `boss`) could provide defaults so authors don't have to specify every field.

**Why:** Currently all mobs are effectively identical in combat power. A level-1 rat and a level-10 bandit captain are mechanically the same. Difficulty tiers let zone authors signal encounter strength without boilerplate.

**Scope:** Medium. Changes to `MobRegistry`/`MobState`, `WorldLoader` validation, `CombatSystem` damage rolls, YAML spec doc, and tests.

**Architecture fit:** The `MobState` data class already lives cleanly inside the engine; adding fields is additive and backward-compatible (defaults resolve missing fields).

---

## 2. Loot Tables & Random Drops

**What:** Allow mobs to carry probabilistic item drops defined in the zone YAML (e.g., `drops: [{itemId: "rusty_sword", chance: 0.3}]`). On mob death, the loot roll fires and surviving items land in the room.

**Why:** Right now items are pre-placed on mobs as a static list. There's no randomness or reward excitement to a kill beyond XP. Loot tables are a foundational MUD mechanic.

**Scope:** Small–medium. Extend the zone YAML spec and `WorldLoader`, add a `rollDrops()` helper in `CombatSystem`, update `ItemRegistry.placeMobDrop()`. No engine architecture changes needed.

**Stretch:** Named loot tables shared across mobs (e.g., `table: "bandit_common"`) to avoid YAML duplication.

---

## 3. Score / Character Sheet Command

**What:** A `score` (or `stats`) command that prints a formatted character sheet: name, level, XP to next level, HP/max HP, equipment summary with stat contributions, and any active status effects.

**Why:** Players currently have no way to see their own stats in aggregate. The data is all there — HP, level, XP, equipment — it just isn't surfaced in one place.

**Scope:** Small. New command parse → `CommandRouter` handler → formatted `OutboundEvent`. No new data model needed.

**Sample output:**
```
[ Arandel — Level 7 Adventurer ]
  HP  : 28 / 34      XP : 4,210 / 5,000
  Dmg : 1–6          Armor: +4 (body: leather, hand: buckler)
  Con : +2 (head: iron helm)
```

---

## 4. Individual Mob Respawn (vs. Zone-Wide Reset)

**What:** Each mob definition gets its own `respawnSeconds` field. When killed, the mob is scheduled to respawn in its origin room after that interval, independently of the zone lifespan reset.

**Why:** Zone-wide resets are blunt — either everything resets at once or nothing does. Individual respawn lets designers have fast-respawning trash mobs alongside rare, slow-respawning bosses. It also means a player kill feels meaningful without permanently emptying a zone.

**Scope:** Medium. Add a per-mob respawn timer to `MobSystem`, extend the zone YAML spec, handle the case where a mob's origin room no longer exists (zone was force-reset mid-respawn), and add tests.

---

## 5. Skill & Ability System

**What:** A lightweight active-skill layer on top of the existing combat system. Players unlock skills on level-up (configurable table). Each skill has a cooldown, cost (e.g., stamina or flat HP), and effect (bonus damage, temporary defense boost, stun, etc.).

**Example skills:**
| Name | Effect | Cooldown |
|------|--------|----------|
| `bash` | High damage, chance to stun 1 tick | 8s |
| `shield block` | +50% armor for 2 ticks | 15s |
| `backstab` | 3× damage on first strike | 30s |

**Why:** Combat is currently pure auto-attack. Skills give players agency and make level progression feel rewarding beyond raw stat bumps.

**Scope:** Large. New `SkillRegistry`, `CooldownTracker`, command parsing for skill names, YAML config for the skill table, UI feedback, and tests. This is the single biggest lift on this list.

**Architecture fit:** Skills are dispatched like commands; effects integrate into `CombatSystem` via existing damage/defense hooks.

---

## 6. Item Use & Consumables

**What:** A `use <item>` command for consumable items (potions, scrolls, food). Each item definition gains an optional `onUse` effect block: `healHp`, `grantXp`, `applyBuff`, etc. Items with `consumable: true` are removed from inventory on use.

**Why:** Items are currently purely passive stat modifiers via equip slots. Consumables add an economy layer (find/buy/use) and meaningful resource management during combat.

**Scope:** Medium. New command, YAML spec extension, effect resolution in the engine, inventory removal on consume, tests.

---

## 7. Social & Channel System Expansion

**What:** Expand the current `say/tell/gossip/emote` set with:
- `whisper <player> <msg>` — visible only to target, with "X whispers to you" feedback
- `shout <msg>` — broadcasts to current zone (not just local room)
- OOC (out-of-character) channel: `ooc <msg>`
- `pose <text>` — freeform third-person action (like emote but without auto-prepending name)

**Why:** Social interaction is a core draw of MUDs. The existing set is functional but minimal. These additions are low-risk, high-quality-of-life improvements.

**Scope:** Small per command. Each is a new parse + route + `OutboundEvent` type. Zone-scoped `shout` requires a small addition to `PlayerRegistry` (lookup by zone).

---

## 8. Admin / Staff Commands

**What:** A privileged command tier unlocked by a configurable `staff` flag on a player account. Initial set:

| Command | Effect |
|---------|--------|
| `goto <room>` | Teleport to any room ID |
| `transfer <player> <room>` | Move another player |
| `spawn <mob>` | Instantiate a mob in current room |
| `shutdown` | Graceful server shutdown with broadcast |
| `reload world` | Hot-reload zone YAML without restart |
| `kick <player>` | Disconnect a session |

**Why:** Currently there is zero in-game tooling. Even basic operations (moving a stuck player, reloading content after a YAML edit) require a server restart. This is a significant operational gap for live game management.

**Scope:** Medium. New `isStaff` field in `PlayerRecord`, command routing guard, implementations touching `PlayerRegistry`/`MobRegistry`/`ItemRegistry`. Hot-reload is the hardest piece (requires careful re-validation without side effects on live sessions).

---

## 9. Player-to-Player Trading / Item Giving

**What:** `give <item> <player>` transfers an item from the sender's inventory to a nearby player's inventory, with a confirmation message to both parties.

**Why:** The item system is fully functional but items can only move between a player and the world (floor). Direct player trade is a prerequisite for any economy or cooperative play.

**Scope:** Small. New command + route; reuses existing `ItemRegistry` move semantics. The main design question is whether to require both players in the same room (yes, for simplicity).

---

## 10. Persistent World State (Beyond Player Files)

**What:** Store zone reset timestamps, mob placement, and room item state in a lightweight persistence layer (SQLite or a structured file store) so the world survives server restarts without instantly full-resetting all zones.

**Why:** Every restart currently resets every zone to initial state. Long-lived items placed by players (dropped gear, etc.) vanish. For any serious play this breaks immersion and economy.

**Scope:** Large. Requires new `WorldStateRepository` interface, migration of `MobRegistry`/`ItemRegistry` to load/save world state, and careful handling of schema evolution as zones change. SQLite via JDBC would fit cleanly into the existing architecture.

**Alternative (simpler):** Persist only room-floor item state (not mob positions), which captures the most valuable data with much less complexity.

---

## 11. Quest / Task System

**What:** A declarative quest engine where zone YAML can define simple tasks: kill N mobs, collect an item, deliver an item to an NPC, visit a room. Completion triggers rewards (XP, items, unlock of new exits).

**Why:** Static XP grind is the weakest part of the current design. Quests provide narrative context, pacing milestones, and replayability.

**Scope:** Large. Needs `QuestRegistry`, `QuestProgress` per-player (requires persistence), event hooks from `CombatSystem`/`ItemRegistry`/movement, a new YAML section in zone files, and significant test coverage. This is best done after per-mob stats and loot tables are in place.

---

## 12. Telnet Protocol Improvements

**What:** Implement a subset of the Telnet negotiation protocol (RFC 854 / relevant options):
- `NAWS` (window size detection) for adaptive line-width formatting
- `TTYPE` (terminal type) to auto-detect ANSI capability instead of requiring `ansi on`
- `GMCP` (Generic MUD Communication Protocol) for rich data exchange with modern clients

**Why:** The current telnet transport treats everything as raw bytes after line decoding. Real telnet clients (MUSHclient, Mudlet) negotiate capabilities on connect. GMCP in particular enables rich client UIs (health bars, map panes) without changing the core game engine.

**Scope:** Medium (NAWS/TTYPE) to Large (GMCP). NAWS/TTYPE are self-contained changes to `BlockingSocketTransport` and the telnet decoder. GMCP requires a new structured message layer and engine-side event emission.

---

## 13. Web Client Improvements

**What:** Upgrade the single-page demo client:
- Health bar and XP bar rendered from structured GMCP data (or parsed ANSI)
- Clickable room exits (buttons below terminal)
- Simple map panel (ASCII or canvas-based)
- Command history (up/down arrow), tab-complete for common commands
- Mobile-friendly layout

**Why:** The current xterm.js client is functional but bare. For players arriving via the web, first impressions matter. A lightweight HUD makes the game much more accessible without touching the engine.

**Scope:** Frontend-only work (HTML/CSS/JS). No engine changes needed for most items. Map panel would benefit from GMCP room data.

---

## 14. Configuration-Driven Starting Experience

**What:** Replace the hardcoded starting room and player stats with a richer new-player flow:
- Configurable starting room per zone (or a "nexus" concept)
- Optional class selection at character creation (warrior/rogue/mage) granting different starting stats
- A short "tutorial corridor" zone that walks new players through movement, combat, and equipping

**Why:** New players currently enter the world with no context and have to discover mechanics by experimentation. A short onboarding path dramatically reduces the learning curve.

**Scope:** Medium. Class selection is a new auth-flow step; tutorial zone is pure YAML content; starting stat differentiation touches `PlayerProgression` defaults.

---

## Prioritization Suggestion

If forced to pick a first batch, these offer high value for relatively contained effort:

| # | Feature | Effort | Impact |
|---|---------|--------|--------|
| 3 | Score command | Small | High — immediate QoL |
| 9 | Give item to player | Small | High — enables cooperation |
| 2 | Loot tables | Small–Med | High — rewards exploration |
| 1 | Per-mob stats | Medium | High — makes world feel alive |
| 4 | Individual mob respawn | Medium | High — fixes zone emptying |
| 7 | Social expansion | Small | Medium — player retention |
| 8 | Admin commands | Medium | High — operational necessity |
| 6 | Consumables | Medium | Medium — adds economy |

Features 5 (skills), 10 (persistent world), 11 (quests), and 12 (GMCP) are the biggest architectural lifts and are best tackled after the table above is in place.
