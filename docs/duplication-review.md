# AmbonMUD Duplication & Abstraction Review

## Phase 1: Discovery

### 1) Directory / module map
- Root Gradle project with one submodule (`:swarm`).
  - Root declared in `settings.gradle.kts` and `:swarm` included explicitly.
- Main runtime code is under `src/main/kotlin/dev/ambon` with clear package boundaries:
  - `engine`, `transport`, `bus`, `grpc`, `gateway`, `persistence`, `sharding`, `metrics`, `config`, `session`.
- Test code mirrors runtime packages under `src/test/kotlin/dev/ambon`.
- Content and static assets under `src/main/resources` (`world`, `web`, DB migrations, login/banner assets).

### 2) Primary languages/frameworks
- **Kotlin/JVM** (Kotlin plugin + JDK toolchain 21).
- **Ktor** (server core/netty/websockets).
- **Coroutines**.
- **gRPC/Protobuf**.
- **Exposed + Flyway + Hikari + PostgreSQL driver**.
- **Redis (Lettuce)**.
- **Micrometer Prometheus**.
- **JUnit 5 + kotlinx-coroutines-test**.

### 3) Build/module boundaries
- Root build config and dependencies: `build.gradle.kts`.
- Module boundary: `:swarm` included from `settings.gradle.kts`.
- App entrypoint configured as `dev.ambon.MainKt`.

### 4) Docs/architecture skim
- README explicitly documents event-driven architecture, deployment modes, bus abstractions, Redis/gRPC layers, and persistence layering.

---

## Phase 2–4: Findings

## 🔴 High-Impact Duplication

### 1) Redis bus envelope/signing/publish/subscribe logic duplicated across inbound + outbound buses
**What**
- `RedisInboundBus` and `RedisOutboundBus` repeat the same structural flow:
  1. Deserialize envelope from Redis.
  2. Ignore own-instance events.
  3. Verify HMAC signature.
  4. Convert envelope ↔ domain event.
  5. Delegate locally + publish on send.
- The only differences are event shapes and a few fields.

**Where**
- `src/main/kotlin/dev/ambon/bus/RedisInboundBus.kt`:
  - envelope and subscribe pipeline (`21-49`), publish pipeline (`72-110`), signing helpers (`112-117`), mapping (`119-146`).
- `src/main/kotlin/dev/ambon/bus/RedisOutboundBus.kt`:
  - envelope and subscribe pipeline (`22-50`), publish pipeline (`67-145`), signing helpers (`147-152`), mapping (`154-199`).

**Suggested refactor**
- Extract a reusable base abstraction, e.g.:
  - `SignedRedisBusAdapter<E, Env>` with hooks:
    - `eventToEnvelope(event: E, instanceId: String): Env?`
    - `envelopeToEvent(env: Env): E?`
    - `payloadToSign(env: Env): String`
  - Keep `RedisInboundBus`/`RedisOutboundBus` as thin config wrappers.
- This preserves current architecture contracts (bus adapters remain transport-layer concerns) while removing repeated error-handling/signature code.

**Estimated impact**
- ~80–120 lines eliminated and significantly lower maintenance risk for future envelope/signing changes.

---

### 2) Event-variant mapping duplicated across Redis and gRPC mappers (shotgun-surgery hotspot)
**What**
- Adding a new `InboundEvent`/`OutboundEvent` variant requires synchronized edits in multiple manual `when` mappers:
  - Redis bus serialize + deserialize blocks.
  - Protobuf serialize + deserialize blocks.
- This is not just duplication in one file; it is a distributed mapping pattern repeated across protocol boundaries.

**Where**
- Redis inbound mapping: `src/main/kotlin/dev/ambon/bus/RedisInboundBus.kt` (`75-105`, `119-146`).
- Redis outbound mapping: `src/main/kotlin/dev/ambon/bus/RedisOutboundBus.kt` (`70-140`, `154-199`).
- Protobuf inbound mapping: `src/main/kotlin/dev/ambon/grpc/ProtoMapper.kt` (`25-68`, `71-97`).
- Protobuf outbound mapping: `src/main/kotlin/dev/ambon/grpc/ProtoMapper.kt` (`100-179`, `182-217`).

**Suggested refactor**
- Introduce a centralized event codec registry (e.g., `EventCodecRegistry`) with per-variant codec objects.
- Each codec declares how to encode/decode for each medium (Redis envelope fields, Proto oneof).
- Existing files become adapters that delegate to registry lookups.

**Estimated impact**
- Not immediate line reduction as much as **future-change reduction**: one conceptual place per event variant instead of 4+ edits per new event.

---

### 3) CommandParser contains long repeated “prefix + empty check + construct command” branches
**What**
- Many command branches share near-identical parsing scaffolding:
  - `matchPrefix(...){ rest -> ... }?.let { return it }`
  - trim/check empty
  - construct `Command.X` or `Command.Invalid(...)`
- Repeated across social, item, combat, admin, and shop commands.

**Where**
- `src/main/kotlin/dev/ambon/engine/commands/CommandParser.kt`:
  - large repeated branch section (`185-368`), including repeated split/trim patterns (`200-208`, `233-264`, `279-318`, `320-363`).

**Suggested refactor**
- Introduce small parser combinators/helpers, e.g.:
  - `parseRequiredArg(aliases, usage, ctor)`
  - `parseTargetMessage(aliases, usage, ctor)`
  - `parseTwoArgs(aliases, usage, ctor)`
- Keep specialized handlers (e.g., `give`, `look <dir>`) inline where domain-specific.

**Estimated impact**
- ~60–100 lines reduced, plus easier onboarding and lower risk when adding command aliases.

---

## 🟡 Medium-Impact Opportunities

### 1) Gateway reconnect loops duplicate retry/backoff/metrics mechanics in single- and multi-engine paths
**What**
- Two reconnect loops contain overlapping control flow:
  - for-attempt loop
  - compute backoff delay
  - metrics + logging
  - success/failure branching
- One path reconnects a specific engine entry, the other reattaches bidi stream objects, but retry scaffolding is duplicated.

**Where**
- `src/main/kotlin/dev/ambon/gateway/GatewayServer.kt`:
  - multi-engine reconnect loop (`412-437`).
  - single-engine reconnect loop (`487-523`).

**Suggested refactor**
- Extract a shared helper like `suspend fun retryReconnect(label, block): Boolean` handling delay/log/metrics.
- Pass reconnect-specific actions as lambdas.

**Estimated impact**
- ~25–40 lines reduced and behavior consistency improved across deployment modes.

---

### 2) Player mapping logic spread across DTO and Postgres repository, risking drift
**What**
- `PlayerRecord` field mapping is manually duplicated in:
  - `PlayerDto.toDomain()/from(...)`
  - `PostgresPlayerRepository.create/save/toPlayerRecord()`
- This is a structural duplication hotspot for persistence evolution.

**Where**
- `src/main/kotlin/dev/ambon/persistence/PlayerDto.kt` (`33-58`, `62-85`).
- `src/main/kotlin/dev/ambon/persistence/PostgresPlayerRepository.kt`:
  - create-return mapping (`76-92`), upsert mapping (`106-129`), row mapping (`134-158`).

**Suggested refactor**
- Introduce centralized mapping helpers per backend boundary:
  - `PlayerRecord.toInsertStatement(...)`
  - `ResultRow.toPlayerRecord()` moved to shared mapper file
  - Keep `PlayerDto` as canonical transport-format converter.
- Optional: add contract tests that assert field parity between DTO and SQL mapping.

**Estimated impact**
- Moderate line reduction; strong protection against schema/field drift.

---

### 3) Redis inbound/outbound tests duplicate fixtures and signing helpers
**What**
- Test fixtures (`Fake*Publisher`, `Fake*SubscriberSetup`), signed JSON builders, and several test cases are mirrored almost 1:1.

**Where**
- `src/test/kotlin/dev/ambon/bus/RedisInboundBusTest.kt` (`13-159`).
- `src/test/kotlin/dev/ambon/bus/RedisOutboundBusTest.kt` (`13-137`).

**Suggested refactor**
- Extract shared test support (`RedisBusTestFixtures.kt`) with reusable fake publisher/subscriber and signature helpers.

**Estimated impact**
- ~40–70 test lines removed; easier to add new signed-envelope test cases consistently.

---

## 🟢 Minor / Style Improvements

### 1) Local bus wrappers are almost identical generic channel facades
**What**
- `LocalInboundBus` and `LocalOutboundBus` each wrap `DepthTrackingChannel<T>` with near-identical methods.

**Where**
- `src/main/kotlin/dev/ambon/bus/LocalInboundBus.kt` (`7-21`).
- `src/main/kotlin/dev/ambon/bus/LocalOutboundBus.kt` (`8-24`).

**Suggested refactor**
- Consider a tiny generic base class/composition utility for shared operations.
- Keep interfaces separate for API clarity, but reduce mechanical duplication.

---

### 2) RedisInterEngineBus builds envelope twice with only target-channel variation
**What**
- `sendTo` and `broadcast` repeat payload/envelope/json serialization steps.

**Where**
- `src/main/kotlin/dev/ambon/sharding/RedisInterEngineBus.kt` (`42-57`, `59-73`).

**Suggested refactor**
- Small helper `publishEnvelope(targetEngineId: String?, message: InterEngineMessage)`.

---

## 📐 Architectural Suggestions

1. **Adopt codec-oriented event boundaries**
   - A formal codec registry for domain events (Redis + Proto + maybe future transports) would reduce distributed manual mapping and make adding event variants safer.

2. **Prefer table-driven parser registration for commands**
   - A command spec table (aliases, arg shape, usage string, builder) can preserve readability while avoiding growth of a single monolithic parser method.

3. **Establish “single source of truth” for persistence field mappings**
   - Introduce mapper contract tests that fail when a new `PlayerRecord` field is not handled by all persistence layers (DTO + SQL + Redis cache).

4. **Standardize reconnect policy orchestration**
   - Encapsulate retry policy and metrics emission in a reusable component to keep mode-specific gateway logic focused on stream wiring.

---

## Discovery References
- Project/module boundaries: `settings.gradle.kts` (`1-5`).
- Build tech stack and dependencies: `build.gradle.kts` (`3-80`, `82-88`).
- High-level architecture and deployment modes: `README.md` (`4-30`).
