# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project aims to adhere to [Semantic Versioning](https://semver.org/).

## [Unreleased] - 2026-02-22 to 2026-02-25

### Added

#### Core Gameplay Systems
- Implement Phase 1 quest system with objectives, rewards, and tracking (#177) (bafa626)
- Implement behavior tree system for mob AI and movement with state machines (#173) (c78de8e)
- Add dialogue system with NPC conversations and conditional choices (#170) (2de7f5b)
- Add status effect system with DOT/HOT/shields/CC mechanics (#161) (ffd3efe)
- Add player economy system with shops and mob gold drops (#160) (9e139fe)
- Add individual mob respawn timer independent of zone-wide reset (#60, #149) (64da831)
- Add zone instancing (layering) for hot-zone load distribution (#147) (7a2db26)
- Add player races and classes with attribute system (#139) (5379280)
- Add primary attributes, Race/Class enums, and mechanical effects (#137) (da9febd)
- Add social channel commands: whisper, shout, ooc, and pose (#128) (9c3c3a8)
- feat(combat): add explicit death summary and safe respawn messaging (#127) (2894b50)
- feat(items): add consumable use and player give commands (#120) (ad62962)

#### World Content
- Add four low-level training zones to the world (#142) (ed76709)
- Add multiple low-level training zones connected to hub (#130) (30ea306)

#### Client & Protocol Support
- Web client improvements: XP bar, nav bar, mini-map, command history, tab-completion (#69, #154) (504ecba)
- Add GMCP Room.Mobs support and web client mob panel (#152) (a0801a0)
- Add GMCP packages, web UI panels, and comprehensive tests (#150) (b5a5600)
- Add GMCP support: structured JSON alongside text output (#143) (4cd805e)
- Implement phase 1 telnet negotiation support for NAWS and TTYPE (#135) (a818d07)

#### Infrastructure & Testing
- Add configurable Kotlin swarm load-testing module (#121) (db48dee)

### Changed

#### Performance & Core Architecture
- Performance and correctness improvements across persistence and networking (#175) (5403337)
- feat(sharding): O(1) cross-engine tell routing via Redis player-location index (#134) (81cf733)

#### Refactoring & Code Quality
- refactor(test): migrate CombatSystemTest to shared drainAll and loginOrFail helpers (#168) (3a597ed)
- Refactor player creation to use request object and extract utility functions (#166) (ff35236)
- Harden Redis bus envelopes with shared-secret HMAC signatures (#138) (73c7a31)
- Add Claude Code project settings, hooks, and commands (#163) (2ff4d63)

### Fixed

#### Crashes & Stability
- Fix Netty startup crash on Windows with JDK 21+ (#151) (a15ea57)
- Fix server startup crash: add training zones to application.yaml world resources (#146) (3ae28e2)
- Fix swarm login flow for race/class selection steps (#144) (f653bae)

#### Testing & CI
- Fix flaky GrpcOutboundBusTest and add cloud environment guidance (#158) (8911fd3)
- fix: prevent test hangs from causing stale Gradle daemons and file locks (#112) (a47a008)

#### Build & Dependencies
- fix: update JVM toolchain to 21 for cloud environment compatibility (#117) (49e457b)

#### Quality & Warnings
- Fix Hoplite Warning (#157) (cac1bdd)
- Fix bugs and harden swarm load tester (#156) (cb2893f)

### Security

- Harden Redis bus envelopes with shared-secret HMAC signatures (#138) (73c7a31)

### Documentation

#### Design & Planning
- Add quest system user flow plan (#176) (10b3ebb)
- Add next large projects roadmap document (#155) (9ca6350)
- docs: add comprehensive refactoring review identifying duplication opportunities (#162) (ea8a0ca)
- docs: add interview-ready scaling story narrative (#131) (b29f6be)
- docs: add observability coverage review (#123) (4fcd4d6)
- docs: comparative review of skill system PRs #115 and #116 (#118) (c90580a)
- docs: add replicated entry-zone routing plan (#111) (ccfb9aa)

#### Project Maintenance
- Update README.md to document implemented but undocumented features (#153) (448b955)
- Archive completed design docs; update onboarding and project status (#164) (a847c38)
- Add ktlint style guide to CLAUDE.md (76e18c0)
- Add git workflow guidance to CLAUDE.md (d5c9ccc)
- Document PR review and fix workflow (81c92d5)
- Decouple engine/command tests from production world files (#132) (da00204)

---

## Summary

- **Total commits analyzed:** 49
- **Date range:** February 22–25, 2026
- **Commits by category:**
  - Added: 18 entries
  - Changed: 4 entries
  - Fixed: 8 entries
  - Security: 1 entry
  - Documentation: 13 entries

**Key milestones:** Complete core gameplay loop with quests, economy, status effects, mob AI behavior trees, dialogue, and GMCP protocol support. Infrastructure hardening and test reliability improvements. Comprehensive project documentation and design narratives.
