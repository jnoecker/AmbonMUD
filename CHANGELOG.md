# Changelog

All notable changes to this project are documented in this file.

## [2026-04] - 2026-04-03

### Pre-Release Architecture Review & Hardening (20 PRs, #863–#887)
Comprehensive architectural review across 8 domains (engine, persistence, transport, commands, game systems, config, sharding, tests) followed by systematic remediation.

**Critical fixes:**
- **Player death cleanup**: 8 game systems now cleaned up on death — groups, trades, duels, pets, status effects, cooldowns, dialogue, dungeons (#865).
- **Economy atomicity**: Trade and auction gold/item transfers are now atomic; gold re-validated at completion time to prevent duplication exploits (#864).
- **Engine concurrency**: GMCP dirty sets wrapped in try/finally to prevent leak on flush exceptions; room member map cleanup for empty rooms (#863).
- **Redis cache coherence**: Cache now updated on save (was only on read); write-coalescing eviction race fixed with lock (#866).

**Security hardening:**
- **gRPC HMAC authentication**: Shared-secret auth interceptor with timestamp-based replay protection for ENGINE↔GATEWAY trust boundary (#887).
- **Admin JSON injection fix**: Error responses now use Jackson serialization instead of string interpolation (#869).
- **Constant-time auth comparison** via `MessageDigest.isEqual()` (#869).
- **WebSocket origin validation** checks Origin against Host header (#869).
- **GMCP JSON parsing** replaced string splitting with Jackson for input validation (#869).
- **Connection limits** on telnet transport with configurable maximum (default 5000) (#868).
- **Snowflake session ID overflow** prevention — spin-waits instead of returning duplicates (#868).

**Operational hardening:**
- **Production mode** (`server.productionMode`): Rejects placeholder secrets (`changeme`, `CHANGE_ME`) at startup (#885).
- **ENGINE port conflict fixed**: Metrics HTTP default changed from 9090 to 9099; validation rejects gRPC/metrics port collision (#885).
- **Shutdown idempotency**: `stop()` guarded with `AtomicBoolean` in MudServer, EngineServer, GatewayServer (#885).
- **Gateway structured shutdown**: Replaced `Thread.join()` with `CompletableDeferred`-based signal (#885).
- **Metrics bind address**: Configurable `metricsHttpHost` with warning when exposed in ENGINE/GATEWAY mode (#885).
- **Admin rate limiting**: Staff toggle (5s), broadcast (10s), reload (30s) cooldowns; concurrent reload guard (#879).
- **Hot-reload guard**: Prevents overlapping reloads with 409 Conflict response (#879).

**Config & metrics:**
- **Metric cardinality fix**: `DisconnectReason` and `GrpcDropReason` enums normalize arbitrary strings to fixed tag values (#867).
- **Config validation additions**: startRoom required, world time hours ordered, faction cross-refs fail on undefined, sharding requires Redis, equipment slot orders unique, XP exponent ≥ 1.0, outbound queue capacity capped (#867).
- **gRPC control plane timeout** increased from 250ms to 2000ms (configurable) (#871).
- **Redis failure handling**: `isConnected()` method, WARN logging, `redis_unavailable_total` metric (#871).

**Command system:**
- **Item handler state validation**: wear/remove/get/drop/give blocked during combat, dialogue, and trade (#870).
- **Input length limit** of 2000 characters in CommandParser (#870).
- **Broadcast rate limiting**: Gossip/shout/OOC limited to 1 per 2 seconds, staff exempt (#870).
- **Error message standardization**: ~60 error paths changed from `SendText` to `SendError` across 13 handlers (#879).
- **Dialogue choice range** extended from 1–9 to 1–99 (#879).

**Game system edge cases:**
- Status effect `maxStacks=0` treated as 1 to prevent infinite stacking (#878).
- Quest/achievement callbacks skip when player HP ≤ 0 (same-tick death) (#878).
- Dungeon failed creation cleanup removes partial rooms/mappings (#878).

**Persistence polish:**
- Flyway V27: Index on `auth_token_hash` column (#875).
- Stat key normalization on load; stat values coerced to ≥ 1 (#875).
- `FlushResult` data class reports success + failure counts (#875).
- Auction file persistence uses atomic write (temp + rename) (#866).
- HikariCP timeouts configured (maxLifetime 30m, connection 30s, idle 10m) (#866).
- YAML auth-token in-memory index for O(1) lookup (#884).

**Memory leak prevention:**
- GmcpEmitter `lastZoneBySession` bounded with LRU cap (10,000) (#874).
- Threat table periodic stale entry sweep every 60 seconds (#874).
- Grace period `fullDisconnect()` made idempotent with guard set (#874).
- Handoff timeout recovery restores player to source room (#871).

**Protocol & engine polish:**
- `allPlayers()` cached once per tick instead of allocating 3+ times (#876).
- ProtoMapper logs WARN on unknown event types (#876).
- GrpcOutboundDispatcher drains pending events on shutdown (2s timeout) (#876).
- Round-robin session assignment uses `Math.floorMod()` to handle integer overflow (#876).

**Test coverage:**
- 6 new test files: MultiSystemIntegrationTest, ShutdownCleanupTest, DuelCommandTest, TradeCommandTest, DungeonCommandTest, HousingCommandTest (#873).
- MobSystemTest expanded from 1 to 8 tests (#873).
- ~130 new tests total across all PRs; test file count now ~144.

### Web Client UX
- **Auth token relogin**: When saved auth token expires, character name is auto-sent to skip straight to password prompt instead of showing "Enter your character name" (#886).



### Web Client Feature Parity (15 issues, #722–#736)
Closed the full web client parity backlog from the consolidated feature report (#721). Every MUD command now has a web UI affordance; no gameplay workflow requires the terminal.

- **Data-driven help panel** from `Server.Commands` GMCP — eliminates static help drift (#722, #737).
- **Item inspection**: Examine button on inventory, ground, and container items (#723, #738).
- **Player context menu**: Tell, Whisper, Give, Group Invite, Friend Add, Look on player sprites (#724, #739).
- **Crafting panel refresh button** replaces "type `craftskills` to load" hint (#725, #740).
- **Admin panel**: setlevel, dispel, reload, and broadcast commands (#726, #741).
- **Mail compose**: full in-panel body editor with Send/Cancel — the last hybrid terminal workflow (#727, #742).
- **Container Put action** from inventory panel (#728, #745).
- **Lock button** on room features panel (#729, #743).
- **Mob context enrichment**: EntityPopout adapts actions based on `Room.MobInfo` flags — shop/quest/dialogue badges (#730, #744).
- **Group/Guild invite GMCP**: structured accept/decline cards in social panels (#731, #746).
- **`Room.LookTarget` GMCP**: structured examine results with floating inspect card (#732, #749).
- **`Server.Commands` enrichment + command palette** (Ctrl+K): searchable launcher populated from server metadata (#733, #751).
- **`UI.Feedback` GMCP**: machine-readable `code`, `scope`, `command` fields on feedback payloads (#734, #750).
- **Command-parity CI**: automated tests diffing parser commands vs web autocomplete and GMCP packages vs client handlers (#735, #748).
- **Direction peeking**: Shift+Click on exit buttons sends `look <direction>` (#736, #747).

### Staff Tools
- **Mob possession system**: `possess <mob>` / `return` / `recall` — move mobs, speak as them, fight as them. Behavior tree paused while possessed. Disconnect auto-releases. (#754, #757, #764)
- **Staff invisibility**: `invis` toggle hides staff from Room.Players, who list, and movement broadcasts. Auto-enabled during possession. Eye icon button in header bar. (#758)
- **Admin broadcast**: `broadcast <message>` staff command with `Server.Broadcast` GMCP and dismissible full-screen modal on the canvas (#759).
- **Spawn mob browser**: searchable mob template list in admin panel, grouped by zone (#752).
- **Spawn room announcements**: spawned mobs now broadcast text + `Room.AddMob` GMCP to all players in the room (#753).
- **Possess button**: staff-only "Possess" action in mob EntityPopout (#757).
- **Possession combat**: `kill`/`flee` commands routed during possession; mob death auto-releases (#764).

### Canvas & Visual
- **Crafting on canvas**: gathering nodes render as clickable sprites (moss-green tint), crafting stations as a badge. Click-to-gather and click-to-recipes. Image field threaded through YAML → domain → GMCP → canvas. (#765, #767)
- **Gameplay color remap**: replaced Material Design neon colors (damage `#ff6b6b`, heal `#6bff8a`, etc.) with jewel-tone variants matching the Surreal Gentle Magic palette — soft coral, muted sage, pale blue, warm gold. Applied across CSS, combat log, canvas CombatAnimator, BattleScene, StatusEffectDisplay, and EntityPopout. (#762)
- **Login modal elevation**: stronger backdrop blur (12px), lavender glow border, breathing title animation (#763).
- **Button press bloom**: radial white gradient `::after` pseudo-element on `:active` for tactile feedback (#763).
- **Mobile vitals**: show abbreviated HP/Mana/XP numbers on <960px screens instead of hiding them (#763).

### Design System & Quality
- **Color token normalization**: 29 hard-coded hex colors extracted to CSS variables; 7 new semantic tokens added (`--text-white`, `--bg-deep`, `--color-gold-bright`, `--color-gold-coin-*`, `--color-toast-text`, `--color-accent-violet`). (#761)
- **Button gradient tokens**: `--button-gradient-primary/secondary/tertiary` for consistent hierarchy (#763).
- **Panel border token**: `--line-panel` replaces hard-coded `rgb(88 89 114 / 30%)` (#763).
- **Focus indicators**: `outline: 2px solid transparent` on all `:focus-visible` selectors for Windows High Contrast Mode (#760).
- **Touch targets**: `min-height: 44px` enforced on 6 undersized button classes; canvas EntityPopout buttons 28→44px (#760).
- **ARIA accessibility**: labels on help search, emote buttons, feature buttons, inventory pickers, popout close buttons; `aria-live` regions for search result counts; Home/End keys in command palette (#760, #761).
- **Hover media query**: `.soft-button:hover` transform wrapped in `@media (hover: hover)` (#760).

### Infrastructure (early March)
- **Live demo instance** at [mud.ambon.dev](https://mud.ambon.dev) — t4g.nano EC2 with YAML persistence, nginx TLS termination via Let's Encrypt, and auto-deploy on every push to `main`.
- **EC2 CDK stack** (`infra/lib/ec2-stack.ts`): optional `hostname` context var installs nginx + certbot, opens ports 80/443, writes a `setup-tls` helper script on the instance.
- **Auto-deploy workflow** (`.github/workflows/deploy-demo.yml`): triggers on CI success for `main`, SSMs `update-ambonmud <sha>` to pull the new image and restart the service in-place (player data untouched).

### Changed
- **Default persistence backend changed from `POSTGRES` to `YAML`**. The server now starts with zero external dependencies. Users who want PostgreSQL must set `ambonMUD.persistence.backend=POSTGRES` explicitly.
- **Redis disabled by default** (`redis.enabled: false`).
- **Telnet I/O uses JVM virtual threads** (PR #313).
- **Docker image builds on native ARM64 runner** — reduces CI build time from ~20 min to ~3 min.
- **Container user pinned to UID/GID 1001** in the Dockerfile.

## [2026-02] - 2026-02-28

### Added
- Added `labyrinth` as the 10th world zone (`world/labyrinth.yaml`).
- Added debug-only `SWARM` player class (`PlayerClass.debugOnly = true`) for load-testing. Enabled via `-Pconfig.ambonMUD.engine.debug.enableSwarmClass=true`; never appears in production character creation.

### Fixed
- Fixed `-Pconfig.*` Gradle property overrides being silently ignored. The `applyConfigOverrides` helper was producing `config.override.config.ambonMUD.*` system properties (double `config.` prefix) instead of `config.override.ambonMUD.*`, so all runtime config overrides (logging level, port, etc.) were no-ops. Now strips the leading `config.` before prepending `config.override.`.

### Changed
- Raised `login.maxConcurrentLogins` from `50` to `150` and `login.authThreads` from `4` to `8`. Previous defaults caused bots to time out in `WAIT_NAME` under high-concurrency ramps: the login semaphore saturated at 50, rejected bots retried in a loop, and the 60-second FSM timer expired mid-cycle. With 8 BCrypt threads the sustained login throughput is ~30–80 logins/sec, clearing 150 simultaneous connections in under 5 seconds.

### Documentation
- Updated `docs/SCALING_STORY.md` with load-test validated numbers (70 sustained players, 141 peak sessions, engine tick p99 < 4 ms), auth funnel bottleneck analysis, virtual threads roadmap item, and a revised 90-second interview summary.
- Updated `docs/ROADMAP.md` with measured capacity numbers and virtual threads (#301) as a tracked infrastructure item.
- Updated `docs/ARCHITECTURE.md` Design Decision #15 to document debug-only classes and the `-Pconfig.*` override mechanism.
- Updated `AGENTS.md` world zone count (9 → 10).

## [2026-02] - 2026-02-25

### Added
- Introduced a full login and authentication flow with better guardrails, session takeover support, and persisted ANSI preferences for returning players. (9a5aecb, 3053eb9, c8ea059, 652a724)
- Delivered core RPG combat progression: wearable equipment, attack/defense + constitution stats, regen, level/XP progression, score command, and richer combat feedback. (006b449, fe00bfd, c166cfe, 4f7803a, b3cb4eb, e99aeb3, 896f0b9)
- Expanded social/gameplay commands with emotes, whispers, shout/ooc/pose channels, consumable `use`, item `give`, and staff moderation/admin command set. (99e7457, 9c3c3a8, ad62962, 5d31597)
- Added world and content systems including periodic repop, loot tables, per-mob tiers/stats, individual mob respawn timers, tutorial/training zones, and a connected portal area. (42a8579, 6e7e2a4, 87ad3c0, 64da831, 104ea83, ed76709, 30ea306, 33adc7c)
- Added questing and narrative layers with phase-1 quests, achievements/titles, dialogue trees, behavior-tree mob AI, and stateful rooms (doors, containers, levers, signs). (bafa626, c8d20d2, 2de7f5b, c78de8e, efab49e)
- Introduced economy features: gold currency, shops, `buy/sell/list/gold` gameplay loop, and mob gold drops. (9e139fe)
- Added observability stack and operations UX: structured logging, Prometheus metrics, ENGINE-mode scrape endpoint, admin HTTP dashboard, and per-phase/tick/queue latency metrics. (a91f2c4, 8a66e51, 9a2be5f, 58c6e4d, cc219e8, 9fcefbd, 7ece286)
- Added WebSocket transport plus browser client improvements (xterm client, XP/nav/minimap/mob panels, history, and tab completion) and GMCP package support. (4be10a6, b5a5600, a0801a0, 504ecba, 4cd805e)
- Added scalability architecture: Redis bus, Redis L2 cache, gRPC gateway split, zone-based engine ownership, zone instancing, session ID hardening, telnet NAWS/TTYPE negotiation, and player-location indexing for O(1) cross-engine tell routing. (c5ac046, d95843a, 3d88e58, 665e41d, 7a2db26, 703e276, a818d07, 42bf807, 81cf733)
- Added PostgreSQL persistence backend (with Docker Compose full stack) as an alternative to YAML persistence. (c346a1f, bea068e)
- Added a configurable swarm load-testing module and follow-up swarm behavior hardening/refactors. (db48dee, cb2893f, f653bae, 85a3c46)

### Changed
- Refactored player creation and test helpers to reduce duplication and improve maintainability while preserving behavior. (ff35236, 3a597ed)
- Relaxed ktlint rules and added multi-instance local run configurations to reduce contributor friction. (f47b938, d833f30)
- Updated project/developer docs broadly, including onboarding, roadmap/plans, scaling narratives, and project status updates. (6574b80, 9ca6350, a847c38, c25ed9d)

### Fixed
- Fixed cloud/runtime compatibility and startup issues, including Java 21 toolchain alignment, Netty startup on Windows, world-resource startup crashes, and Hoplite warnings. (49e457b, a15ea57, 3ae28e2, cac1bdd)
- Fixed reliability issues in tests and tooling, including flaky async tests and Gradle daemon/file-lock failures from hanging tests. (8911fd3, 7667f24, a47a008)
- Fixed combat and engine correctness issues including repeated equipment bonus computation and inbound phase time budgeting. (45b0880, 08d9be4)

### Security
- Hardened Redis bus envelopes with shared-secret HMAC signatures for event authenticity/integrity. (73c7a31)

### Infrastructure
- Upgraded major build/runtime dependencies (Ktor 3, Gradle 9, JUnit 6, Kotlin/JVM, gRPC, Lettuce, Micrometer, Jackson) and enabled CodeQL scanning + Dependabot tuning. (6ca14fc, 4c19039, e72889e, 760e724, 715aef8, 578fd8d, d168599, 70380ff, 678640f)
- Added extensive performance-focused optimizations across engine/transport/scheduler/GMCP paths to reduce per-tick allocations and improve throughput under load. (5403337, f8ad52d, f1ec72b, b16e70c, 0055a96, d137981, 10a41ac, a963dfa, 7778ea4, 3e60abc, 8107068, 1759a8e, 39a3b68)

### Documentation
- Added and reorganized substantial technical documentation, including migration plans, observability/scalability reviews, and contributor-facing guidance. (6d1eb74, 4fcd4d6, ccfb9aa, b29f6be, 651d687)

## [2026-01] - 2026-01-26

### Added
- Bootstrapped AmbonMUD from an initial telnet echo server into a navigable multi-room world with chat and command parsing. (a5aedcc, 714fef0, 485ea06)
- Added ANSI output rendering and prompt styling, including richer terminal presentation utilities. (03ab348, aed01d4)
- Added YAML-based world definitions with namespaced room IDs, multi-zone loading, and cross-zone exits. (9310ec8, 26161f3, 6db4227)
- Added core command set expansion, including emotes, exits listing, and directional room lookups. (99e7457, 22c34c9)
- Added persistence foundations via in-memory and YAML player repositories. (51efa71, e3465ff)
- Added scheduler support for delayed/periodic actions and introduced NPC periodic behaviors plus room-presence/movement messaging. (e7745e5, cf4f663, 3e07e5f)
- Added combat/world-state primitives with mob spawning, item registry, and validated room/mob item spawn support. (dddeaa4, a9bed1a, 50a545f)

### Fixed
- Fixed dependency injection/session ownership drift issues impacting runtime stability. (1108a6f, f0aa387)

### Infrastructure
- Made Gradle wrapper executable to support project bootstrap across environments. (33404a8)

### Documentation
- Expanded and revised foundational project docs (README and design decisions) to describe architecture and setup. (afc6336, 72a8188, 88c5b6e)
