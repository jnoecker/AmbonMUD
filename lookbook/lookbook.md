# AmbonMUD Lookbook

A visual tour of AmbonMUD — every major subsystem of the web client, representative
rooms from across the Auringold world, and all 128 rooms of the Auringold Academy.
Captured from a live server running the full Auringold dataset (Ambon).

## The Game at a Glance

AmbonMUD is a multiplayer fantasy world in the classic MUD tradition, rebuilt for the
browser: every room, character, and panel is rendered over painted scenes, while the
same world remains fully playable over plain telnet. New players can jump in instantly
as a guest with the demo mode and claim their character later, or create an account in
a painted login flow that walks them through choosing one of **nine races** and **six
classes** (Bulwark, Arcanist, Veil, Herald, Scrollwright, and Songweaver).

**Adventure & progression.** Characters level by exploring and fighting through
hand-authored zones, from the 128-room Auringold Academy tutorial onward. Class
trainers teach new abilities with earned skill points, and multi-classing lets a
character branch into additional classes over time. Abilities, status effects, and
tactical choices (including a wimpy auto-flee threshold) drive real-time, tick-based
combat with damage toasts, enemy vitals, and a combat log. Beyond fighting, a complete
**pacifist path** (the Akathavae) progresses through illumination and a personal
Arcanum journal instead of violence. Rare cosmetic mob variants — albino, ember,
shadow-touched, spectral, and kin — appear on a small chance for collectors and
bragging rights.

**Quests & goals.** NPCs offer branching, voice-acted dialogue with structured quest
offers; the quest log tracks active, daily, weekly, bounty, and global quests.
Achievements, leaderboards, factions with reputation tiers, special currencies (quest
points, honor, crafting tokens), and a prestige system give long-term goals past the
level cap.

**Economy & crafting.** Shops buy and sell with configurable economy multipliers; the
bank stores gold and items; an auction house runs player-to-player sales; direct
trading handles face-to-face deals. Gathering nodes feed a recipe-driven crafting
system, with enchanting to improve gear and item manuals to study what you've found.

**Places & possessions.** Player housing is bought through realtors and furnished;
guilds get shared halls. Inns offer rest, pets can be adopted and named, the stylist
re-styles your look, and a wardrobe manages outfits.

**Diversions & world life.** Lottery drawings, dice tables, jukeboxes with zone
soundtracks, puzzles and riddle doors, instanced dungeons, and PvP dueling round out
the world — which itself lives on a day/night clock with seasons, weather, and
scheduled world events that change the sky overhead.

**Together.** Groups for shared adventuring and XP, guilds with boards and halls,
friends lists, mail with attachments, global and group chat channels, and a who's-online
board keep it social.

## Under the Hood

A one-page technical orientation for software engineers.

**Server.** Kotlin on JDK 21. A single-threaded game engine advances the world on a
100&nbsp;ms tick; all gameplay state lives inside the engine, and the engine speaks to
the outside world only through typed inbound/outbound event buses — transports contain
no game logic, and the engine contains no transport code. The same binary runs as a
single **standalone** process or splits into **gateway** (transports) and **engine**
(simulation) processes connected over gRPC, with Redis-backed buses, optional player
caching, and zone sharding/instancing hooks for horizontal scale.

**Protocols.** Plain telnet for classic clients, and WebSocket with **GMCP** packages
for the web client — room state, vitals, maps, shop/trainer/bank inventories, dialogue,
quests, and painted-art asset URLs all stream as structured GMCP messages emitted by
the server.

**Web client.** React 19 + PixiJS. The world view is a canvas scene seated over painted
room art; UI panels are DOM, skinned by a *painted panel contract*: the server registers
art asset keys, delivers URLs via GMCP, and the client locks each panel's aspect ratio
to the art and seats live controls onto the painting with percentage insets — with a
CSS-only fallback when art is absent.

**World as data.** Zones, rooms, mobs, items, shops, trainers, quests, recipes,
puzzles, and dungeons are declarative YAML validated by a strict loader. The world
ships separately from the binary: production assembles its dataset (config overlay +
zone files + sprites + achievements) from object storage at boot, so content updates
deploy without rebuilding the server. The companion desktop app **Arcanum** (Tauri +
React) edits the same schema — entity editors, validation mirroring the server's rules,
AI art and ElevenLabs voice generation with content-addressed publishing to R2/CDN.

**Persistence.** Pluggable player store — YAML files with atomic writes for simple
deployments or PostgreSQL (Flyway-migrated) for production — wrapped in a
write-coalescing worker and an optional Redis cache.

**Operations.** Docker images deployed via GitHub Actions with health-checked restarts,
an admin HTTP API for live reloads and moderation, Prometheus metrics, and CI running
lint, unit, and integration suites on every PR.

## Login & Character Creation

The painted login flow: name entry, returning-character picker, and the race and class galleries.

### Name entry
![Name entry — the cottage login scene with the Start Demo side panel](screenshots/subsystems/01-login-name.jpg)
*Name entry — the cottage login scene with the Start Demo side panel*

### Welcome Back
![Welcome Back — saved-character picker](screenshots/subsystems/02-login-picker.jpg)
*Welcome Back — saved-character picker*

### New-character confirmation
![New-character confirmation](screenshots/subsystems/03-login-confirm.jpg)
*New-character confirmation*

### Race selection
![Race selection — nine races of Ambon in their painted niches](screenshots/subsystems/04-race-select.jpg)
*Race selection — nine races of Ambon in their painted niches*

### Class selection
![Class selection — the six classes](screenshots/subsystems/05-class-select.jpg)
*Class selection — the six classes*

## The World View

### The Adventurer's View

![World view](screenshots/subsystems/06-game-room-view.jpg)
*The in-game view: painted room scene with player and NPC sprites, vitals HUD, minimap, room narrative, exits compass, and the panel dock.*

## Character & Progression

### Character sheet
![Character sheet — portrait, stats, settings, and links to professions, achievements, and prestige](screenshots/subsystems/10-character-panel.jpg)
*Character sheet — portrait, stats, settings, and links to professions, achievements, and prestige*

### Professions
![Professions](screenshots/subsystems/10b-professions.jpg)
*Professions*

### Achievements
![Achievements](screenshots/subsystems/10c-achievements.jpg)
*Achievements*

### Prestige
![Prestige](screenshots/subsystems/10d-prestige.jpg)
*Prestige*

### Inventory
![Inventory](screenshots/subsystems/11-inventory-panel.jpg)
*Inventory*

### Equipment
![Equipment](screenshots/subsystems/12-equipment-panel.jpg)
*Equipment*

### Trainer
![Trainer — spending skill points on class abilities](screenshots/subsystems/31b-trainer-trained.jpg)
*Trainer — spending skill points on class abilities*

### Spellbook
![Spellbook — learned abilities with hotbar binding](screenshots/subsystems/13-spellbook-panel.jpg)
*Spellbook — learned abilities with hotbar binding*

### Quest log with an active quest
![Quest log with an active quest](screenshots/subsystems/14-quests-panel.jpg)
*Quest log with an active quest*

## Social

### Chat board
![Chat board](screenshots/subsystems/16-chat.jpg)

### Who's online
![Who's online](screenshots/subsystems/17-who.jpg)

### Friends
![Friends](screenshots/subsystems/18-friends.jpg)

### Guild board
![Guild board](screenshots/subsystems/19-guild.jpg)

### Group board
![Group board](screenshots/subsystems/20-group.jpg)

### Mail
![Mail](screenshots/subsystems/21-mail.jpg)

### Help compendium
![Help compendium](screenshots/subsystems/22-help.jpg)

## Commerce & Services

### Shop
![Shop — buy/sell with the Shop That Sells Everything](screenshots/subsystems/30-shop.jpg)
*Shop — buy/sell with the Shop That Sells Everything*

### Bank vault
![Bank vault — gold and item storage](screenshots/subsystems/33-bank.jpg)
*Bank vault — gold and item storage*

### Auction house
![Auction house](screenshots/subsystems/32-auction.jpg)
*Auction house*

### Stylist
![Stylist — cosmetic restyling](screenshots/subsystems/34-stylist.jpg)
*Stylist — cosmetic restyling*

### Housing realtor
![Housing realtor](screenshots/subsystems/35-housing.jpg)
*Housing realtor*

### Inn
![Inn — rest and recovery](screenshots/subsystems/36-inn.jpg)
*Inn — rest and recovery*

### Crafting recipes
![Crafting recipes](screenshots/subsystems/41-crafting.jpg)
*Crafting recipes*

## Games & Diversions

### Lottery
![Lottery](screenshots/subsystems/36b-lottery.jpg)
*The lottery board in the academy's games parlor — jackpot, ticket numbers, and the nightly drawing*

### Dice table
![Dice table](screenshots/subsystems/37b-dice.jpg)

### Jukebox
![Jukebox](screenshots/subsystems/38-jukebox.jpg)
*The jukebox in the Parlor of Borrowed Songs — pay a few coins and your chosen track plays for everyone in the room*

### Puzzle cabinet
![Puzzle cabinet](screenshots/subsystems/43-puzzle.jpg)

## NPC Dialogue, Quests & Combat

### NPC dialogue
![NPC dialogue — branching conversation with a quest giver](screenshots/subsystems/44-dialogue.jpg)
*NPC dialogue — branching conversation with a quest giver*

### Quest accepted
![Quest accepted](screenshots/subsystems/46-quest-accepted.jpg)
*Quest accepted*

### Combat
![Combat — damage toasts, enemy vitals, and the flee escape hatch](screenshots/subsystems/47-combat-1.jpg)
*Combat — damage toasts, enemy vitals, and the flee escape hatch*

### Combat continues
![Combat continues](screenshots/subsystems/48-combat-2.jpg)
*Combat continues*

## Around the World

Representative rooms from the live zones.

### The Celestial Plaza (Playtesting)
![The Celestial Plaza (Playtesting)](screenshots/rooms/playtesting-celestial-plaza.jpg)

### The Shop That Sells Everything
![The Shop That Sells Everything](screenshots/rooms/playtesting-shop.jpg)

### The Bank of Borrowed Time
![The Bank of Borrowed Time](screenshots/rooms/playtesting-vault.jpg)

### The Trainer That Trains Every Class
![The Trainer That Trains Every Class](screenshots/rooms/playtesting-trainer.jpg)

### The Mad Jester's Gameroom
![The Mad Jester's Gameroom](screenshots/rooms/playtesting-gameroom.jpg)

### The Battle Arena of the Gods
![The Battle Arena of the Gods](screenshots/rooms/playtesting-arena.jpg)

### The Undercroft of Sundry Affairs
![The Undercroft of Sundry Affairs](screenshots/rooms/playtesting-undercroft.jpg)

### A Monster Pit of Low Level Creatures
![A Monster Pit of Low Level Creatures](screenshots/rooms/playtesting-pve-mob.jpg)

### Before the Gate (Aineroia's Cottage)
![Before the Gate (Aineroia's Cottage)](screenshots/rooms/aineroia_cottage-before_gate.jpg)

### The West Garden
![The West Garden](screenshots/rooms/aineroia_cottage-west_garden.jpg)

### The Small Pond
![The Small Pond](screenshots/rooms/aineroia_cottage-small_pond.jpg)

### The Study
![The Study](screenshots/rooms/aineroia_cottage-study.jpg)

### The Poison Garden
![The Poison Garden](screenshots/rooms/aineroia_cottage-poison_garden.jpg)

### Krioshaeu's Cabin (Celestial Sanctum)
![Krioshaeu's Cabin (Celestial Sanctum)](screenshots/rooms/celestial_sanctum-krioshaeu_cabin.jpg)

## The Auringold Academy — All 128 Rooms

Every room of the Auringold Academy tutorial zone, in zone-file order.

<div>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/krioshaeu_cabin.jpg" alt="Krioshaeu's Cabin" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Krioshaeu's Cabin</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/endless_starry_void.jpg" alt="The Endless Starry Void" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Endless Starry Void</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/wagon_drifting_stars.jpg" alt="Outside a Wagon Drifting Through the Stars" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Outside a Wagon Drifting Through the Stars</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/zoetrope_carriage.jpg" alt="The Zoetrope Carriage" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Zoetrope Carriage</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/memory_creation.jpg" alt="A Memory of Creation" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Memory of Creation</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/fortune_teller_parlor.jpg" alt="Fortune Teller's Astral Parlor" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Fortune Teller's Astral Parlor</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/heralds_of_creation.jpg" alt="The Heralds of Creation" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Heralds of Creation</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/first_archae.jpg" alt="The First Archae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The First Archae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/tempest_between_worlds.jpg" alt="The Tempest Between Worlds" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Tempest Between Worlds</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/turbulent_waters.jpg" alt="Turbulent Waters" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Turbulent Waters</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/sandy_shoal.jpg" alt="A Sandy Shoal" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Sandy Shoal</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/foothills_kaerinlith.jpg" alt="The Foothills Below Kaerinlith" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Foothills Below Kaerinlith</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/sylflorae_farm.jpg" alt="A Sylflorae Farm" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Sylflorae Farm</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/clockwork_forest.jpg" alt="A Clockwork Forest" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Clockwork Forest</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/deep_clockwork_forest.jpg" alt="Deeper in the Clockwork Forest" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Deeper in the Clockwork Forest</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/clockwork_clearing.jpg" alt="A Clearing in the Clockwork Forest" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Clearing in the Clockwork Forest</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/springwright_workshop.jpg" alt="The Springwright's Workshop" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Springwright's Workshop</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/beside_clockwork_pond.jpg" alt="Beside the Clockwork Pond" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Beside the Clockwork Pond</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/clockwork_dias.jpg" alt="Mechanical Dias at the Center of a Clockwork Pond" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Mechanical Dias at the Center of a Clockwork Pond</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/mine_opening.jpg" alt="The Opening of a Shining Crystal Mine" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Opening of a Shining Crystal Mine</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/shining_crystal_mine.jpg" alt="A Shining Crystal Mineshaft" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Shining Crystal Mineshaft</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/void_below_mountain.jpg" alt="The Void Below the Mountain" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Void Below the Mountain</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/sleeping_stone_statues.jpg" alt="Cavern of Sleeping Stone Lithae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Cavern of Sleeping Stone Lithae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/arrival_king.jpg" alt="The Arrival of the King" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Arrival of the King</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/world_seed.jpg" alt="The World Seed" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The World Seed</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/great_betrayal.jpg" alt="The Great Betrayal" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Great Betrayal</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/clockwork_battle.jpg" alt="The Clockwork Battle" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Clockwork Battle</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/obsidian_dagger.jpg" alt="The Birth of the Obsidian Dagger" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Birth of the Obsidian Dagger</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/carnival_prophecy.jpg" alt="The Fortune Teller's Prophecy" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Fortune Teller's Prophecy</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/emberfell.jpg" alt="The Emberfell" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Emberfell</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/humble_forest_cabin.jpg" alt="A Humble Forest Cabin" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Humble Forest Cabin</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/royal_wedding.jpg" alt="The Royal Wedding" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Royal Wedding</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/royal_graveyard.jpg" alt="In a Tiny, Brightly-Lit Graveyard" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">In a Tiny, Brightly-Lit Graveyard</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/caldera.jpg" alt="The Caldera" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Caldera</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/arcane_tempest.jpg" alt="Inside the Arcane Tempest" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Inside the Arcane Tempest</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/verathaios_workshop.jpg" alt="Verathaios's Workshop" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Verathaios's Workshop</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/aolexia.jpg" alt="Aolexia" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Aolexia</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/aolia.jpg" alt="Aolia" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Aolia</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/shattered_fae_wood.jpg" alt="The Shattered Fae Wood" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Shattered Fae Wood</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/lustriae.jpg" alt="The Lustriae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Lustriae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/pyrae.jpg" alt="The Pyrae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Pyrae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/aureliae.jpg" alt="The Aureliae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Aureliae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/ophirae.jpg" alt="The Ophirae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Ophirae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/mycorae.jpg" alt="The Mycorae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Mycorae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/night_wind.jpg" alt="A Shrieking Night Wind" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Shrieking Night Wind</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/luneqrae.jpg" alt="The Luneqrae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Luneqrae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/moon.jpg" alt="Aineroia Lights the Night Sky" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Aineroia Lights the Night Sky</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/aetherae.jpg" alt="The Aetherae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Aetherae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/call_for_heroes.jpg" alt="The Call for Heroes" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Call for Heroes</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/desert_sands.jpg" alt="The Hot Desert Sands" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Hot Desert Sands</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/aineron.jpg" alt="Aineron" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Aineron</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/veratheron.jpg" alt="Veratheron" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Veratheron</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/phosmaren.jpg" alt="Phosmaren" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Phosmaren</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/thalvorek.jpg" alt="Thalvorek" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Thalvorek</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/mycollum.jpg" alt="Mycollum" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Mycollum</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/creeping_darkness.jpg" alt="The Creeping Darkness" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Creeping Darkness</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/tessikon.jpg" alt="The Coming-Soon Portal to Tessikon" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Coming-Soon Portal to Tessikon</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/faegrimm.jpg" alt="Faegrimm" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Faegrimm</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/cindaron.jpg" alt="Cindaron" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Cindaron</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/lagauri.jpg" alt="Lagauri" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Lagauri</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/nakhara.jpg" alt="Nakhara" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Nakhara</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/voxspore.jpg" alt="Voxspore" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Voxspore</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/lyrasael.jpg" alt="Lyrasael" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Lyrasael</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/vulgorth.jpg" alt="Vulgorth" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Vulgorth</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/krioshaion.jpg" alt="Krioshaion" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Krioshaion</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/path_kaerinlith.jpg" alt="The Path to Kaerinlith" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Path to Kaerinlith</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/cozy_inn.jpg" alt="The Cozy Inn of Dreams" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Cozy Inn of Dreams</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/gemstone_mineshaft.jpg" alt="A Mineshaft Full of Gemstones" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Mineshaft Full of Gemstones</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/enchanted_bakery.jpg" alt="The Enchanted Bakery of Auringold" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Enchanted Bakery of Auringold</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/auringold_tavern.jpg" alt="The Retired Professor Tavern" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Retired Professor Tavern</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/ship_graveyard.jpg" alt="A Graveyard of Sunken Ships" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Graveyard of Sunken Ships</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/coral_reef.jpg" alt="A Coral Reef" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Coral Reef</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/seaweed_thicket.jpg" alt="A Seaweed Thicket" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Seaweed Thicket</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/sunken_ship_hull.jpg" alt="Within the Hull of a Sunken Ship" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Within the Hull of a Sunken Ship</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/vault.jpg" alt="Kvarcgagap's Vault" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Kvarcgagap's Vault</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/wading_pond.jpg" alt="Wading Across a Clockwork Pond" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Wading Across a Clockwork Pond</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/caravan_stable.jpg" alt="A Stable of Clockwork Horses" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Stable of Clockwork Horses</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/kitsarae_study.jpg" alt="The Path to Enlightenment" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Path to Enlightenment</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/aineroia_cottage.jpg" alt="Aineroia's Cottage" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Aineroia's Cottage</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/yaleron.jpg" alt="Yaleron - A City Under Seige" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Yaleron - A City Under Seige</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/necrambon.jpg" alt="Necrambon" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Necrambon</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/resistance_camp.jpg" alt="A Resistance Camp in the Mountains" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Resistance Camp in the Mountains</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/dream_hotel.jpg" alt="Hotel at the Edge of Dreams" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Hotel at the Edge of Dreams</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/general_store.jpg" alt="Madam Daydreams' General Store" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Madam Daydreams' General Store</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/library_fairytales.jpg" alt="The Library of Fairytales" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Library of Fairytales</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/lantern_cloister.jpg" alt="The Lantern Cloister" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Lantern Cloister</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/kiriton_winter.jpg" alt="Harsh Kiriton Winter" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Harsh Kiriton Winter</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/underground_river.jpg" alt="An Underground River" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">An Underground River</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/waterfall.jpg" alt="A Mountain Waterfall" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">A Mountain Waterfall</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/crafters_workshop.jpg" alt="Crafter's Workshop" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Crafter's Workshop</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/betrayer_study.jpg" alt="The Betrayer's Study" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Betrayer's Study</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/kitsarae_mountain_basin.jpg" alt="The Awakening of the Kitsarae" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Awakening of the Kitsarae</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/academy_quad.jpg" alt="The Academy Quad" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Academy Quad</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/orientation_hall.jpg" alt="The Orientation Hall" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Orientation Hall</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/proving_yard.jpg" alt="The Proving Yard" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Proving Yard</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/sparring_circle.jpg" alt="The Sparring Circle" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Sparring Circle</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/beast_pen.jpg" alt="The Misbehaving Menagerie" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Misbehaving Menagerie</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/spotters_walk.jpg" alt="The Spotters' Walk" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Spotters' Walk</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/duel_ring.jpg" alt="The Duel Ring" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Duel Ring</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/market_row.jpg" alt="Market Row" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">Market Row</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/bursar_bank.jpg" alt="The Bursar's Vault" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Bursar's Vault</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/auction_hall.jpg" alt="The Auction Hall of Whispered Bids" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Auction Hall of Whispered Bids</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/provisioners_nook.jpg" alt="The Provisioner's Nook" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Provisioner's Nook</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/campus_post_office.jpg" alt="The Campus Post Office" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Campus Post Office</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/broker_office.jpg" alt="The Estates Broker's Office" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Estates Broker's Office</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/guild_registry.jpg" alt="The Guild Registry" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Guild Registry</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/gathering_garden.jpg" alt="The Gathering Garden" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Gathering Garden</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/almanac_terrace.jpg" alt="The Almanac Terrace" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Almanac Terrace</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/old_mine_gate.jpg" alt="The Old Mine Gate" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Old Mine Gate</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/crystal_seam.jpg" alt="The Crystal Seam" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Crystal Seam</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/alchemy_lab.jpg" alt="The Alchemy Lab" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Alchemy Lab</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/tinkers_bench.jpg" alt="The Tinker's Bench" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Tinker's Bench</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/enchanting_sanctum.jpg" alt="The Enchanting Sanctum" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Enchanting Sanctum</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/hall_of_records.jpg" alt="The Hall of Records" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Hall of Records</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/prestige_vestibule.jpg" alt="The Vestibule of Ascension" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Vestibule of Ascension</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/riddle_gallery.jpg" alt="The Riddle Gallery" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Riddle Gallery</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/vault_of_whispers.jpg" alt="The Vault of Whispers" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Vault of Whispers</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/secret_archive.jpg" alt="The Secret Archive" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Secret Archive</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/games_parlor.jpg" alt="The Parlor of Small Fortunes" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Parlor of Small Fortunes</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/song_parlor.jpg" alt="The Parlor of Borrowed Songs" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Parlor of Borrowed Songs</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/twilight_salon.jpg" alt="The Twilight Salon" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Twilight Salon</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/catacombs_landing.jpg" alt="The Catacombs Landing" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Catacombs Landing</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/ossuary_walk.jpg" alt="The Ossuary Walk" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Ossuary Walk</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/catacomb_chapel.jpg" alt="The Chapel of Patient Rest" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Chapel of Patient Rest</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/memento_mori_shrine.jpg" alt="The Memento Mori Shrine" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Memento Mori Shrine</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/restless_repository.jpg" alt="The Restless Repository" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Restless Repository</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/gates_approach.jpg" alt="The Approach to the Gates" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Approach to the Gates</figcaption></figure>
<figure style="display:inline-block;width:31.5%;margin:0.5%;vertical-align:top;page-break-inside:avoid"><img src="screenshots/academy/gates_of_ambon.jpg" alt="The Gates of Ambon" style="width:100%"><figcaption style="font-size:7pt;text-align:center;margin-top:2pt">The Gates of Ambon</figcaption></figure>
</div>
