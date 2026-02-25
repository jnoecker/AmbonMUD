# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project aims to adhere to [Semantic Versioning](https://semver.org/).

## [Unreleased] - 2026-02-22 to 2026-02-25

### Added

#### Core Gameplay Systems
- **Phase 1 quest system** (#177) (bafa626) — Quest framework with objectives, rewards, and tracking
- **Behavior tree system for mob AI** (#173) (c78de8e) — State machine-based AI for mob movement and combat behavior
- **Dialogue system with NPC conversations** (#170) (2de7f5b) — NPC dialogue with conditional choices and player interaction
- **Status effect system** (#161) (ffd3efe) — DOT/HOT mechanics, shield buffs, crowd-control effects
- **Player economy system** (#160) (9e139fe) — Shops, merchant NPCs, mob gold drops, currency system
- **Player races and classes with attributes** (#139) (5379280) — Race/Class system with primary attribute system integration
- **Primary attributes framework** (#137) (da9febd) — Strength, Dexterity, Constitution, Intelligence, Wisdom, Charisma with mechanical scaling
- **Combat death messaging** (#127) (2894b50) — Explicit death summary and safe respawn messaging system
- **Consumable items** (#120) (ad62962) — Item use mechanics with player give/trade commands
- **Individual mob respawn timers** (#149) (64da831) — Per-mob respawn scheduling independent of zone-wide reset

#### World & Zone Systems
- **Zone instancing (layering)** (#147) (7a2db26) — Hot-zone load distribution through instance sharding
- **Multiple training zones** (#142, #130) (ed76709, 30ea306) — Four connected low-level training areas with progression path

#### Player & Social Systems
- **Social channel commands** (#128) (9c3c3a8) — Whisper, shout, OOC, pose commands for player interaction

#### Client & Protocol Support
- **GMCP protocol support** (#143) (4cd805e) — Structured JSON output alongside traditional text
- **GMCP packages and web UI panels** (#150) (b5a5600) — Comprehensive GMCP protocol implementation with client panels
- **GMCP Room.Mobs support** (#152) (a0801a0) — Room mob listing protocol with web client mob panel
- **Telnet negotiation** (#135) (a818d07) — Phase 1 NAWS (Negotiate About Window Size) and TTYPE (Terminal Type) support
- **Web client enhancements** (#154) (504ecba) — XP progress bar, navigation bar, mini-map, command history, tab-completion

#### Infrastructure & Testing
- **Configurable Kotlin swarm load-testing module** (#121) (db48dee) — Load testing framework for multi-engine deployments
- **Cross-engine player location index** (#134) (81cf733) — O(1) tell routing across multiple engines via Redis

### Changed

#### Performance & Core Architecture
- **Performance and correctness improvements** (#175) (5403337) — Persistence layer optimization and network message handling corrections
- **Refactored player creation** (#166) (ff35236) — Request object pattern with extracted utility functions
- **Test helper consolidation** (#168) (3a597ed) — Migrated CombatSystemTest to shared test utilities (drainAll, loginOrFail)
- **Redis bus security hardening** (#138) (73c7a31) — HMAC signature validation on all Redis message envelopes

#### Project Infrastructure
- **Claude Code integration** (#163) (2ff4d63) — Project settings, hooks, and development commands

### Fixed

#### Server Crashes & Startup
- **Netty Windows JDK 21+ crash** (#151) (a15ea57) — Fixed Netty startup crash on Windows with JDK 21+
- **Training zone startup crash** (#146) (3ae28e2) — Added missing training zones to application.yaml world resources
- **Race/class selection flow** (#144) (f653bae) — Corrected swarm login flow for race and class selection steps

#### Testing & Build
- **Gradle daemon hangs** (#112) (a47a008) — Added layered timeout defenses (JUnit 30s, Gradle 5min, idle 10min) with bounded gRPC/coroutine cleanup and improved teardown ordering
- **Flaky gRPC tests** (#158) (8911fd3) — Fixed flaky GrpcOutboundBusTest with cloud environment guidance
- **JVM toolchain compatibility** (#117) (49e457b) — Updated to JDK 21 for cloud environment compatibility (Foojay resolver proxy issues)

#### Quality & Warnings
- **Hoplite warnings** (#157) (cac1bdd) — Resolved Hoplite configuration warnings
- **Swarm load tester stability** (#156) (cb2893f) — Fixed load tester bugs and hardened against concurrent connection issues

### Security

- **Redis message authentication** (#138) (73c7a31) — Hardened Redis bus envelopes with shared-secret HMAC signatures to prevent tampering

### Documentation

#### Design & Planning Documents
- **Quest system user flow** (#176) (10b3ebb) — User interaction flow plan for phase 1 quest system
- **Large projects roadmap** (#155) (9ca6350) — Next major feature initiatives and architectural directions
- **Refactoring review** (#162) (ea8a0ca) — Comprehensive analysis identifying duplication opportunities and improvement areas
- **Interview-ready scaling narrative** (#131) (b29f6be) — Scaling story for architectural design interviews
- **Observability coverage review** (#123) (4fcd4d6) — Metrics, logging, and tracing capability assessment
- **Ability system review** (#118) (c90580a) — Comparative analysis of two competing ability system PRs with recommendations
- **Replicated entry-zone routing plan** (#111) (ccfb9aa) — Design for distributed zone entry point handling

#### Project Maintenance & Onboarding
- **Architecture and onboarding updates** (#164) (a847c38) — Archived completed design docs and updated onboarding guide
- **README feature documentation** (#153) (448b955) — Documented implemented but previously undiscovered features
- **Ktlint style guide** (76e18c0) — Added Kotlin official style compliance guidelines to CLAUDE.md
- **Git workflow guidance** (d5c9ccc) — Added git development branch and PR workflow to CLAUDE.md
- **PR review workflow** (81c92d5) — Documented PR review and fix workflow
- **Test decoupling** (#132) (da00204) — Decoupled engine/command tests from production world files for faster iteration

---

## Summary

- **Total commits analyzed:** 51 (including this changelog)
- **Date range:** February 22–25, 2026
- **Commits by category:**
  - Added: 20 entries (core gameplay, world, client/protocol, infrastructure)
  - Changed: 4 entries (refactors, performance, security hardening)
  - Fixed: 8 entries (crashes, testing, build compatibility)
  - Security: 1 entry (Redis authentication)
  - Documentation: 13 entries (design docs, onboarding, style guides)

**Repository Inception:** All commits represent initial repository establishment with comprehensive feature implementation from day one, spanning:
- Complete core gameplay loop (quests, economy, status effects, mob AI)
- Multi-transport protocol support (telnet, WebSocket, GMCP)
- Distributed architecture (zone instancing, cross-engine routing)
- Infrastructure hardening (test reliability, security validation, cloud compatibility)
- Extensive project documentation and design narratives
