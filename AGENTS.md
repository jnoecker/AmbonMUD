# AGENTS.md

This file is the entry point for AI coding agents (Claude Code, etc.) working in this repository. It points at the canonical docs and surfaces the small set of contracts that must not be broken without rewriting them here.

## Read first

- **[`docs/DEVELOPER_GUIDE.md`](docs/DEVELOPER_GUIDE.md)** — project map, command system, persistence stack, configuration, testing, common tasks (add command / ability / status effect / zone / GMCP package / bus event), troubleshooting, cloud-dev notes.
- **[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)** — data flow diagram, event model, engine tick loop, persistence chain, key design decisions.
- **[`CLAUDE.md`](CLAUDE.md)** — condensed contracts and change playbooks; loaded automatically by Claude Code.
- **[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)** — Docker, CDK, env-var reference, CI/CD, EC2 demo, R2 lore overlay.
- **[`docs/GMCP_PROTOCOL.md`](docs/GMCP_PROTOCOL.md)** — GMCP wire format and per-package payload reference (covers the stable subset; see the doc's coverage notice for the complete emitted-package inventory).
- **[`docs/WORLD_YAML_SPEC.md`](docs/WORLD_YAML_SPEC.md)** — zone YAML contract.
- **[`docs/ART_CONTRACT.md`](docs/ART_CONTRACT.md)** — painted UI art: asset registry, `Server.Assets` GMCP delivery, and the panel-reskin pattern.
- **[`docs/VOICE_OVER_CONTRACT.md`](docs/VOICE_OVER_CONTRACT.md)** — NPC dialogue voice-over pipeline (R2 paths, hash spec, `voiceUrl`).
- **[`docs/STYLE_GUIDE.md`](docs/STYLE_GUIDE.md)** + **[`.impeccable.md`](.impeccable.md)** — design system.

If you're starting a code change, the relevant change playbook is in `DEVELOPER_GUIDE.md § 13 Common Tasks`. Don't reinvent the steps; read them.

## Core commands

```bash
./gradlew run            # Start server (telnet :4000, web :8080)
./gradlew demo           # Start server + auto-launch browser demo
./gradlew test           # Unit tests
./gradlew integrationTest
./gradlew ktlintCheck    # Run before every PR
./gradlew ktlintCheck test integrationTest   # CI parity
./gradlew buildWeb       # Build web-v3/ (auto-runs with run/demo)
./gradlew shadowJar      # Fat JAR for the Dockerfile

# Focused tests
./gradlew test --tests "dev.ambon.engine.commands.CommandParserTest"
./gradlew test --tests "*CommandRouter*"

# Multi-instance local topology (engines first, then gateways)
./gradlew runEngine1     # gRPC :9091
./gradlew runEngine2     # gRPC :9092
./gradlew runGateway1    # telnet :4000, web :8080
./gradlew runGateway2    # telnet :4001, web :8081
```

On Windows use `.\gradlew.bat`. Override config at runtime with `-Pconfig.<key>=<value>` (e.g. `-Pconfig.ambonmud.persistence.backend=POSTGRES`).

## Environment

- JDK 21 (Gradle `jvmToolchain(21)`); CI runs Temurin 21.
- Kotlin 2.3 / Gradle wrapper.
- Style: `kotlin.code.style=official`, ktlint 1.5.0 with project overrides in `.editorconfig`. Trailing commas required on multiline lists; no wildcard imports. Relaxed rules: `multiline-expression-wrapping`, `string-template-indent`, `chain-method-continuation`, `function-signature`.

## Architectural contracts (do not break)

These are short, load-bearing rules. Full rationale and the rest of the architecture live in `docs/ARCHITECTURE.md` and `CLAUDE.md`.

1. **Engine ↔ transport isolation.** Engine speaks only `InboundEvent` / `OutboundEvent`. No transport code in engine; no gameplay in transport. ANSI is semantic in engine (`SetAnsi`, `ClearScreen`, `ShowAnsiDemo`), rendered as raw bytes only in `AnsiRenderer` / `PlainRenderer`.
2. **Single-threaded engine.** `GameEngine` runs on a dedicated dispatcher with a 100 ms tick loop. No blocking I/O inside engine systems. Use the injected `Clock`, never `System.currentTimeMillis()`.
3. **Bus abstraction.** Engine receives `InboundBus` / `OutboundBus` — never raw `Channel<T>`. `Local*Bus`, `Redis*Bus`, and `Grpc*Bus` swap behind that interface.
4. **Output semantics.** `OutboundRouter` applies backpressure and coalesces consecutive prompts. `Close` sends final text then closes via callback. Never bypass the router.
5. **World/ID invariants.** `RoomId` = `<zone>:<room>`. Player name 2–16 alnum/underscore, no leading digit. Password non-blank, max 72 (BCrypt). Online-name uniqueness is case-insensitive.
6. **Persistence chain.** `WriteCoalescingPlayerRepository` → `RedisCachingPlayerRepository` (optional) → `YamlPlayerRepository` or `PostgresPlayerRepository`. Every `PlayerRecord` change must survive all three layers including Redis JSON round-trip; `PersistenceFieldCoverageTest` catches mapping omissions. `isStaff` is granted by editing YAML or `is_staff` in Postgres — there is no in-game command.
7. **Event-bus boundary.** New `InboundEvent` / `OutboundEvent` variants must be added to the Redis envelope (type discriminator + signed payload) and to the proto definitions (`events.proto` + `ProtoMapper.kt`) in the same change.

## Testing expectations

- Minimum verification: `./gradlew ktlintCheck test`. Add `integrationTest` when touching integration-tagged areas (HTTP/gRPC/database) or before finalizing broad changes.
- Add tests for every behavioral change.
- Use `MutableClock` for time-sensitive logic; never call `System.currentTimeMillis()` in production code.
- In cloud/CI, prefer `withTimeout`-based polling over `delay(...)`; for negative async assertions use `delay(200)` minimum.
- Reuse `EngineTestHelpers`, `InMemoryPlayerRepository`, `@TempDir`, and H2 PostgreSQL-mode fixtures.

Full test patterns and utilities: `DEVELOPER_GUIDE.md § 12 Testing`.

## Practical notes

- Keep gameplay/state in `engine`; keep protocol/I/O in `transport`. Wire bus/Redis only in `MudServer.kt`.
- Gameplay features follow the pattern `*System.kt` (logic) + `*Handler.kt` (commands) + config in `application.yaml` or world YAML + `*SystemTest`.
- `CommandRouter.kt` is thin dispatch (~110 lines); the work happens in the 37 handler files under `src/main/kotlin/dev/ambon/engine/commands/handlers/`.
- The single biggest files are `GmcpEmitter.kt`, `GameEngine.kt`, `AppConfig.kt`, `AdminHttpServer.kt`, and `WorldLoader.kt`. Check helper systems and handlers before assuming behavior lives in a top-level file.
- Generated protobuf/gRPC sources live under `build/generated/`; ktlint is suppressed there via a child `.editorconfig`.
- Do not commit anything under `data/players/` — runtime state, gitignored.
- Metrics use `io.micrometer.prometheusmetrics`, not the deprecated `io.micrometer.prometheus`.
