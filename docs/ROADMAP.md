# AmbonMUD — Feature Status

What's built. This is a living inventory of shipped systems, not a forward-looking roadmap.

## Current State (June 2026)

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
✅ 3 races (Human, Sylvan, Stoneheart), 5 classes (Warrior, Mage, Cleric, Rogue, Ranger), 6 primary attributes (stat definitions data-driven via `StatRegistry`)
✅ Class-specific abilities — trainer-based learning with skill points; multi-classing available
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
✅ Friends list + offline mail (letters can attach gold and an item; recipients `mail claim`)
✅ Aineroia's Dice — six-die tavern gambling game with the Luneqrae coin
✅ Inn rooms — `rest` sets the recall point and grants 2× HP/mana regen
✅ Guest sessions upgradeable to permanent accounts via `claim`
✅ Crafting & gathering with specialization, recipe discovery, quality tiers, rare yields
✅ Player housing (personal rooms, furniture, vaults, access control)
✅ Procedural dungeons (template-driven, instanced, 4 difficulty tiers, boss encounters)
✅ Pet/companion system (SUMMON_PET ability type, level-scaled stats)
✅ Faction & reputation system (7 standing tiers, quest/kill integration)
✅ Day/night cycle, dynamic per-zone weather, seasonal events
✅ Leaderboard system and hall of fame
✅ Web-based admin dashboard
✅ Remember-me auth tokens for persistent login
✅ Painted-art panel reskins (Staff Control, Crafting, Auction, Lottery, Housing, Stylist, Dice, social boards) with server-resolved assets via `Server.Assets` GMCP — see `docs/ART_CONTRACT.md`
✅ NPC dialogue voice-overs (ElevenLabs clips over GMCP) — see `docs/VOICE_OVER_CONTRACT.md`

---

### Content Creation
✅ **Ambon Arcanum** — desktop worldbuilding studio with a visual zone map editor, entity editors for rooms, mobs, items, shops, quests, gathering nodes, and recipes, a full class/race/ability
designer with stat formulas and status effects, AI art generation for every entity type, a rich lore system with articles, maps, timelines, and relationship graphs, public lore showcase
publishing, and format-preserving YAML round-trip editing

---

**Last updated:** June 9, 2026
