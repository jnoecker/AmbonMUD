# Fresh Playtest Feature Audit

Date: 2026-04-08

## Scope

This report is a code-and-test audit of AmbonMUD's implemented feature surface, written for designing a brand-new human playtest world. It intentionally ignores the shipped world content and existing design docs. Every claim below is based on source inspection and, where possible, direct test coverage.

Verification approach:

- Reviewed runtime wiring in the engine, transport, world loader, handlers, and web client.
- Cross-checked implementation against focused tests rather than zone content.
- Ran a focused verification suite covering world loading, room features, puzzles, dungeons, bank, guilds, trade, housing, crafting, dialogue, time/weather, currencies, reputation, lottery, auction, daily/auto/global quests, achievements, status effects, and web parity. The targeted `gradlew test --tests ...` run completed successfully.

## Executive Summary

AmbonMUD has a much larger implemented feature surface than a basic room-mob-item MUD. The runtime supports:

- Traditional MUD play: movement, combat, inventory, equipment, abilities, status effects, death, loot, shops, trainers, quests, dialogue, social channels.
- World-authored interaction features: doors, keyed locks, keyed containers, levers, signs, puzzles, gathering nodes, crafting stations, banks, taverns, trainers, shops, PvP zones, zone resets, dungeon templates, media-rich rooms, and zone map coordinates.
- Larger MMO-style systems: guilds, groups, friends, mail, player trade, auction house, housing, pets, reputation/factions, alternate currencies, achievements, leaderboards, prestige, daily/weekly/auto/global quests, and lottery/gambling.
- Multiple client/runtime modes: telnet, WebSocket, GMCP, standalone mode, engine/gateway split, Redis bus, gRPC gateway, and zone/instance sharding.

The main browser limitation is structural: the live web client ignores plain text output and depends on GMCP plus dedicated UI panels. That means a backend feature can be fully implemented yet still be weak or unusable in the browser unless it emits structured GMCP and has a panel or another visible surface.

## Verified Major Feature Families

### Core world and interaction model

Verified in code:

- Namespaced rooms and multi-zone worlds.
- Cross-zone exits and remote exit handling.
- Zone lifespan/reset timers.
- Zone-level PvP enablement.
- Room map coordinates for zone maps.
- Room media fields: image, video, music, ambient audio.
- Room flags for bank and tavern services.
- Room crafting stations.
- Room features: doors, containers, levers, signs.
- Hot-reload-friendly world merge/replace behavior.

Primary evidence:

- `src/main/kotlin/dev/ambon/domain/world/load/WorldLoader.kt`
- `src/main/kotlin/dev/ambon/domain/world/Room.kt`
- `src/main/kotlin/dev/ambon/domain/world/RoomFeature.kt`
- `src/main/kotlin/dev/ambon/domain/world/World.kt`
- `src/test/kotlin/dev/ambon/world/load/WorldLoaderTest.kt`
- `src/test/kotlin/dev/ambon/world/load/WorldLoaderFeaturesTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/CommandRouterFeaturesTest.kt`

### Combat, progression, and character systems

Verified in code:

- Tick-based combat runtime with mobs, players, kill handling, drops, and XP.
- Abilities/spells with class restriction, mana, cooldowns, and spell-kill integration.
- Status effects on players and mobs, including DOT, HOT, shields, roots, stuns, stat buffs, refresh, stacking, and expiry behavior.
- Character stats, classes, multiclass unlocks, skill points, and trainer-based learning/respec.
- Prestige and sprite unlock/display systems.
- Pet state and pet commands.

Primary evidence:

- `src/main/kotlin/dev/ambon/engine/GameEngine.kt`
- `src/main/kotlin/dev/ambon/engine/AbilitySystem.kt`
- `src/main/kotlin/dev/ambon/engine/status/StatusEffectSystem.kt`
- `src/main/kotlin/dev/ambon/engine/commands/handlers/TrainerHandler.kt`
- `src/main/kotlin/dev/ambon/engine/PetSystem.kt`
- `src/main/kotlin/dev/ambon/engine/PrestigeSystem.kt`
- `src/test/kotlin/dev/ambon/engine/status/StatusEffectSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/TrainerRespecTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/PetCommandTest.kt`
- `src/test/kotlin/dev/ambon/engine/PrestigeSystemTest.kt`

### Economy and item systems

Verified in code:

- Gold economy with mob gold drops.
- Shops with list, buy, and sell flows.
- Bank rooms with gold deposit/withdraw plus item vault storage.
- Direct player-to-player trade with escrow and gold offers.
- Auction house with list, buy, cancel, and expiry behavior.
- Alternate currencies with award/spend/persistence behavior.
- Tavern-gated lottery and dice gambling.
- Item containers and room/container transfer mechanics.
- Equippable, consumable, priced, charged, image/video-backed item definitions.

Primary evidence:

- `src/main/kotlin/dev/ambon/engine/ShopRegistry.kt`
- `src/main/kotlin/dev/ambon/engine/commands/handlers/ShopHandler.kt`
- `src/main/kotlin/dev/ambon/engine/commands/handlers/BankHandler.kt`
- `src/main/kotlin/dev/ambon/engine/TradeSystem.kt`
- `src/main/kotlin/dev/ambon/engine/AuctionSystem.kt`
- `src/main/kotlin/dev/ambon/engine/CurrencySystem.kt`
- `src/main/kotlin/dev/ambon/engine/LotterySystem.kt`
- `src/test/kotlin/dev/ambon/engine/commands/BankCommandTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/TradeCommandTest.kt`
- `src/test/kotlin/dev/ambon/engine/AuctionSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/CurrencySystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/LotterySystemTest.kt`

### Questing, dialogue, and meta progression

Verified in code:

- Standard quests with objectives, rewards, accept/abandon/progress/complete flow.
- NPC dialogue trees with branching choices and gating.
- Daily and weekly quest rotation.
- Zone-generated auto quests/bounties.
- Server-wide global quests.
- Achievements with progress tracking and rewards.
- Reputation/faction standings with positive and negative adjustments.
- Leaderboards for multiple categories.

Primary evidence:

- `src/main/kotlin/dev/ambon/engine/QuestSystem.kt`
- `src/main/kotlin/dev/ambon/engine/dialogue/DialogueSystem.kt`
- `src/main/kotlin/dev/ambon/engine/DailyQuestSystem.kt`
- `src/main/kotlin/dev/ambon/engine/AutoQuestSystem.kt`
- `src/main/kotlin/dev/ambon/engine/GlobalQuestSystem.kt`
- `src/main/kotlin/dev/ambon/engine/AchievementSystem.kt`
- `src/main/kotlin/dev/ambon/engine/ReputationSystem.kt`
- `src/main/kotlin/dev/ambon/engine/LeaderboardSystem.kt`
- `src/test/kotlin/dev/ambon/engine/QuestSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/dialogue/DialogueSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/DailyQuestSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/AutoQuestSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/GlobalQuestSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/AchievementSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/ReputationSystemTest.kt`

### Social and player-organization systems

Verified in code:

- Public and private chat channels, emotes, tells, whispers, shouts, OOC, pose, who.
- Friends list and online/offline notifications.
- Guild creation, invite/accept, roster, ranks, motd, chat, and guild hall support.
- Groups and party-style state.
- Mail compose/read/delete.
- Housing with purchase, templates, guest lists, and editable metadata.
- Duel flow with challenge/accept/decline/cleanup behavior.

Primary evidence:

- `src/main/kotlin/dev/ambon/engine/FriendsSystem.kt`
- `src/main/kotlin/dev/ambon/engine/GuildSystem.kt`
- `src/main/kotlin/dev/ambon/engine/HousingSystem.kt`
- `src/main/kotlin/dev/ambon/engine/DuelSystem.kt`
- `src/main/kotlin/dev/ambon/engine/commands/handlers/CommunicationHandler.kt`
- `src/main/kotlin/dev/ambon/engine/commands/handlers/MailHandler.kt`
- `src/test/kotlin/dev/ambon/engine/commands/CommandRouterGuildTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/MailHandlerTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/SocialChannelCommandsTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/HousingCommandTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/DuelCommandTest.kt`

### Crafting, gathering, puzzles, and dungeons

Verified in code:

- Gathering nodes with respawn, depletion, XP, and rare yields.
- Crafting recipes with materials, skill requirements, character-level requirements, and station requirements/bonuses.
- Puzzle definitions authored in world data.
- Riddle puzzles with alternate answers.
- Sequence puzzles with ordered lever/feature actions and reset-on-fail.
- Puzzle rewards including unlock-exit, gold, XP, and item grants.
- Procedural dungeon instances from templates, with difficulty, scaling, player return room, and cleanup.

Primary evidence:

- `src/main/kotlin/dev/ambon/engine/CraftingSystem.kt`
- `src/main/kotlin/dev/ambon/engine/PuzzleSystem.kt`
- `src/main/kotlin/dev/ambon/engine/dungeon/DungeonManager.kt`
- `src/main/kotlin/dev/ambon/domain/world/data/PuzzleFile.kt`
- `src/main/kotlin/dev/ambon/domain/world/data/DungeonFile.kt`
- `src/test/kotlin/dev/ambon/engine/crafting/CraftingSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/PuzzleSystemTest.kt`
- `src/test/kotlin/dev/ambon/engine/commands/DungeonCommandTest.kt`

### Runtime, persistence, and deployment

Verified in code:

- Telnet transport with GMCP negotiation.
- WebSocket transport with auto-declared GMCP support.
- Standalone deployment plus split engine/gateway deployment modes.
- Zone registry, inter-engine bus, handoff manager, and instance selection for sharded/instanced play.
- Player persistence through a repository abstraction with YAML or Postgres backends and optional Redis caching/coalescing.
- Admin HTTP server and metrics wiring.

Primary evidence:

- `src/main/kotlin/dev/ambon/MudServer.kt`
- `src/main/kotlin/dev/ambon/transport/NetworkSession.kt`
- `src/main/kotlin/dev/ambon/transport/KtorWebSocketTransport.kt`
- `src/main/kotlin/dev/ambon/ServerInfrastructure.kt`
- `src/main/kotlin/dev/ambon/gateway/GatewayServer.kt`
- `src/main/kotlin/dev/ambon/persistence/PlayerRepository.kt`
- `src/main/kotlin/dev/ambon/persistence/YamlPlayerRepository.kt`
- `src/main/kotlin/dev/ambon/persistence/PostgresPlayerRepository.kt`

## Fresh Playtest World Authoring Surface

This is the feature set a new world can deliberately target.

### Room and zone authoring features

Supported at the world-data/runtime level:

- Multiple zones with independent start rooms.
- Zone lifespan/reset timers.
- Zone-level PvP enablement.
- Zone-level graphical/media defaults.
- Cross-zone exits.
- Auto-derived map coordinates from room graph.
- Room image/video/music/ambient fields.
- Room `bank` and `tavern` service flags.
- Room `station` crafting flag.
- Room features:
  - doors
  - keyed locks
  - keyed lock consumption
  - containers
  - keyed containers
  - keyed container key consumption
  - levers
  - signs

### Mob authoring features

Supported in loader/runtime:

- Template ID and display name.
- Keywords.
- Stats, XP, and tier-derived or overridden combat values.
- Gold drops.
- Item drops.
- Respawn.
- Dialogue linkage.
- Quest linkage.
- Faction linkage.
- Behavior trees.
- Image/video media.

### Item authoring features

Supported in loader/runtime:

- Keyword and display name.
- Equipment slot.
- Damage, armor, and stat modifiers.
- Consumable/use behavior.
- Charges.
- Base price.
- Room placement.
- Container placement.
- Image/video media.

### World-authored service and system anchors

Supported in loader/runtime:

- Shops.
- Trainers, including multi-class trainer payloads.
- Gathering nodes.
- Crafting recipes.
- Quest definitions.
- Puzzle definitions.
- Dungeon templates.

## Fresh Playtest World Blueprint

The goal is not narrative completeness. The goal is feature coverage with the fewest areas possible. The recommended layout below is a deliberate QA world, not a lore-first world.

### Area 1: Arrival Hub

Include:

- start room
- sign
- obvious exits
- one NPC with dialogue
- one quest giver
- one trainer
- one help/tutorial shop item

Use it to verify:

- login spawn
- `look`, `exits`, map coordinates
- sign reading
- dialogue
- standard quest accept flow
- trainer list/learn flow
- shop list/buy

### Area 2: Navigation and Feature Corridor

Include:

- one standard open exit
- one closed door
- one locked door with reusable key
- one locked door with consumable key
- one container
- one locked container with key
- one sign
- one lever

Use it to verify:

- move blocking and open/close behavior
- key and lock semantics
- `search`, `get from`, `put in`
- lever interaction
- feature GMCP updates in the browser

### Area 3: Puzzle Wing

Include:

- one riddle room that unlocks an exit
- one sequence room that requires multiple lever pulls
- one wrong-answer fail state
- one reward chest

Use it to verify:

- `answer`
- alternate answers
- sequence tracking
- reset-on-fail
- unlock-exit rewards
- gold/xp/item rewards

### Area 4: Combat Yard

Include:

- low-, mid-, and high-tier mobs
- one caster-style mob or status-inflicting mob
- one faction-tagged mob
- one quest-target mob
- one auto-quest-friendly mob family

Use it to verify:

- basic combat and loot
- spell kills
- status effects
- faction reputation changes
- quest kill objectives
- auto-quest/bounty targeting

### Area 5: Spell and Status Lab

Include:

- safe respawn access
- mobs tuned for repeated testing
- trainer nearby

Use it to verify:

- class-specific abilities
- cooldowns
- mana usage
- DOT/HOT/shield/root/stun behavior
- dispel/effect display

### Area 6: Economy District

Include:

- shop room
- bank room
- auction access room
- trade-friendly open floor
- mail kiosk NPC or obvious mail-safe room

Use it to verify:

- buy/sell/list/gold
- bank gold and bank item storage
- direct trade
- auction list/buy/cancel/sell
- mail send/read/delete

### Area 7: Tavern and Social Hall

Include:

- tavern-flagged room
- seating/open floor for multiple players
- easy access from hub

Use it to verify:

- tavern-only gambling/lottery behavior
- gossip/ooc/shout/emote/pose
- who list in populated room
- browser chat tabs and social widgets

### Area 8: Crafting Yard

Include:

- at least two gathering node types
- at least one rare-yield node
- at least two crafting stations
- recipes spanning different requirements

Use it to verify:

- node discovery
- gather cooldown/depletion/respawn
- skill XP and level gains
- station requirement and station bonus
- recipe filtering/crafting results

### Area 9: Guild and Group Staging Hall

Include:

- broad shared room near hub
- adjacent side room reserved as a guild-hall test destination if needed

Use it to verify:

- group invites/status
- guild creation/invite/accept
- guild ranks, motd, roster, chat
- guild hall payloads in the web client

### Area 10: Housing Showcase

Include:

- housing purchase access point
- at least one template-friendly nearby anchor room

Use it to verify:

- house purchase
- house status
- guest list management
- title/description edits
- housing info payloads in the browser

### Area 11: PvP Proving Ground

Include:

- dedicated PvP-enabled zone
- obvious warning sign
- fast route back to hub

Use it to verify:

- PvP gating by zone
- duel flow versus open PvP stats
- PvP kill/death counters
- honor or PvP-related currency/reward hooks

### Area 12: Dungeon Portal Room

Include:

- dungeon entrance command affordance
- return room set to hub-adjacent safety room
- templates for at least easy and hard difficulty coverage

Use it to verify:

- dungeon entry/leave
- level requirement gating
- difficulty selection
- instance reuse
- cleanup after exit

### Area 13: World-State Overlook

Include:

- one scenic room with image/video/music/ambient configured
- visible route back to hub

Use it to verify:

- room media payloads
- world time updates
- weather updates
- world-event updates
- browser room/canvas presentation

## Entity Checklist For The Fresh QA World

Use this as the minimum content checklist when building the new playtest world.

### Rooms

- start room
- bank room
- tavern room
- shop room
- trainer room
- crafting station room
- PvP room
- dialogue room
- auction/trade room
- dungeon portal room
- housing access room
- media-rich room

### Feature objects

- normal door
- locked door
- consumable-key door
- normal container
- locked container
- lever
- sign

### Keys and utility items

- reusable key
- consumable key
- basic healing consumable
- mana consumable
- equipment item per major slot you want to validate
- tradeable item
- auction-worthy item
- bank-storable item

### Mobs

- low-level trash mob
- medium durable mob
- high-damage mob
- quest target mob
- faction-aligned mob
- status-effect mob
- dialogue-capable NPC
- trainer NPC
- shopkeeper NPC or room-linked shop

### System definitions

- at least 2 quests
- at least 1 daily candidate
- at least 1 weekly candidate
- at least 1 global quest compatible objective set
- at least 1 auto-quest viable mob family
- at least 2 gathering nodes
- at least 3 recipes
- at least 1 riddle puzzle
- at least 1 sequence puzzle
- at least 1 dungeon template

## Web Client Parity

Legend:

- Full: dedicated browser UI plus GMCP data flow; practical to test in the web client.
- Partial: visible in browser, but some actions still depend on typed commands or incomplete panel coverage.
- State-only: GMCP state reaches the browser, but there is little or no dedicated interaction surface.
- Weak/blocked: implemented on the backend, but current browser UX is poor because plain text output is ignored.

### Key constraint

The current web client intentionally drops non-GMCP text:

- `web-v3/src/App.tsx`
- `web-v3/src/hooks/useMudSocket.ts`

That makes text-only command feedback effectively invisible in the browser.

### Parity matrix

| Feature family | Backend status | Web status | Notes |
| --- | --- | --- | --- |
| Login/session basics | Verified | Full | WebSocket path is first-class and auto-declares GMCP support. |
| Room info, map position, players, mobs, items | Verified | Full | Room and entity payloads are GMCP-driven and central to the UI. |
| Inventory and equipment | Verified | Full | Dedicated panels and commands for wear/use/drop/give exist. |
| Shops | Verified | Full | Dedicated shop UI with buy/sell flow. |
| Trainers and classes | Verified | Full | Trainer panel plus class payloads are wired. |
| Quests, daily, weekly, auto, global | Verified | Full | Quest panel surfaces all major quest families. |
| Crafting and gathering | Verified | Full | Crafting panel supports skills, recipes, and nodes. |
| Mail | Verified | Full | Dedicated inbox/compose/read/delete panel. |
| Bank | Verified | Full | Dedicated bank panel supports gold and item storage. |
| Housing | Verified | Full | Housing panel exposes purchase, status, guests, and metadata. |
| Leaderboards | Verified | Full | Dedicated leaderboard panel plus GMCP payloads. |
| Dialogue | Verified | Full | Dialogue GMCP packages are handled in the browser state path. |
| Friends/guild/group/chat | Verified | Full | Chat panel includes social surfaces and friend/guild state. |
| Character vitals/stats/effects/achievements | Verified | Full | Character panel is the aggregation point for many meta systems. |
| Trade | Verified | Partial | Live trade state dialog exists, but offering items/gold still requires typed commands. |
| Auction | Verified | Partial | Browser panel supports refresh and buy, but not the full sell/cancel flow. |
| Room features and containers | Verified | Partial | Feature and container GMCP exists; usable, but interaction depth is lower than telnet. |
| Pets | Verified | State-only | Pet state reaches Character panel; dedicated pet workflow is limited. |
| Factions/reputation | Verified | State-only | Standings are displayed, but interaction is indirect via gameplay. |
| Alternate currencies | Verified | State-only | Visible in Character panel, not a standalone workflow. |
| Lottery/gambling | Verified | State-only | Lottery info GMCP exists, but dedicated play UI is limited. |
| Dungeons | Verified | State-only | Dungeon info is represented, but dungeon actions are still command-driven. |
| Prestige | Verified | State-only | Prestige info is represented, but progression actions remain command-heavy. |
| Duel | Verified | State-only | Challenge/state payloads exist, but no full dedicated browser panel. |
| Staff/admin tooling | Verified | Weak/blocked | Backend and GMCP exist, but current web UX is not a strong admin console. |
| Text-heavy fallback commands | Verified | Weak/blocked | Anything relying mostly on plain text is poor in the browser because text is ignored. |

## What The Fresh Playtest World Should Prioritize

If the goal is full-system human QA rather than lore, prioritize:

1. Feature interactions that are world-authored and easy to regress:
   doors, keyed locks, containers, levers, signs, puzzles, station rooms, bank/tavern flags, PvP zone flags, and dungeon templates.
2. Browser-visible flows:
   room traversal, inventory/equipment, quests, crafting, social, bank, shop, trainer, and housing.
3. Command-heavy flows that still need coverage because the backend supports them:
   trade offering, auction seller workflows, some pet/duel/prestige/dungeon interactions, and staff tools.
4. Systems that need repeated human loops:
   status effects, faction adjustments, daily/auto/global quests, lottery/tavern, and dungeon entry/exit.

## Bottom Line

For a fresh QA world, AmbonMUD can support a compact but very feature-dense test environment. The engine already supports enough room, mob, item, quest, social, economy, puzzle, and instance mechanics to build a serious human validation world without depending on any shipped zone content.

The one major planning caveat is browser parity: if the test world is intended for browser-first QA, it should heavily favor systems with explicit GMCP and panel support, because plain text command feedback is currently not a reliable fallback in the web client.
