# Changelog

All notable changes to this project are documented in this file.

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
