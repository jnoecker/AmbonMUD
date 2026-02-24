# Refactoring Review: Duplicated Logic & Missing Abstractions

**Date:** 2026-02-24
**Scope:** Full codebase — 75 main-source Kotlin files (~10,890 lines), 66 test files
**Branch:** `claude/review-refactoring-opportunities-NM72v`

---

## Summary

The codebase is well-architected with clear separation of concerns. However, there are approximately **180+ lines of duplicated code** across 10 locations. The worst hotspots are:

1. **Event bus layer** — channel depth-tracking, Redis envelope signing, and subscriber setup are each duplicated between inbound and outbound buses
2. **Persistence layer** — `PlayerRecord` DTO schema and `toDomain()` mapping appear in three places; timer/metrics scaffolding repeated in YAML and Postgres repos
3. **MudServer heartbeats** — four nearly-identical `scope.launch { while (isActive) { delay(); runCatching { ... }.onFailure { ... } } }` loops

---

## 🔴 High-Impact Duplication

### 1. Heartbeat Loop Boilerplate in `MudServer.kt` — 4 occurrences, ~50 lines

**Where:**
- `src/main/kotlin/dev/ambon/MudServer.kt:390–402` — player-index heartbeat
- `src/main/kotlin/dev/ambon/MudServer.kt:409–420` — zone lease heartbeat
- `src/main/kotlin/dev/ambon/MudServer.kt:424–439` — zone load-report heartbeat
- `src/main/kotlin/dev/ambon/MudServer.kt:465–478` — auto-scale evaluation heartbeat

**Pattern (identical structure in all four):**
```kotlin
someJob = scope.launch {
    while (isActive) {
        delay(intervalMs)
        runCatching {
            // one function call here
        }.onFailure { err ->
            log.warn(err) { "... heartbeat failed" }
        }
    }
}
```

**Suggested refactor:** Extract a `CoroutineScope.launchPeriodic` extension function:
```kotlin
fun CoroutineScope.launchPeriodic(
    intervalMs: Long,
    name: String,
    block: suspend () -> Unit,
): Job = launch {
    while (isActive) {
        delay(intervalMs)
        runCatching { block() }.onFailure { err ->
            log.warn(err) { "$name failed" }
        }
    }
}
```

Each of the four call sites collapses from ~10 lines to 3 lines. **Estimated reduction: ~40 lines.**

---

### 2. `PlayerFile` / `PlayerJson` / `PlayerRecord` — Triple DTO Schema, ~46 lines duplicated

**Where:**
- `src/main/kotlin/dev/ambon/persistence/PlayerRecord.kt:10–32` — canonical domain class (21 fields)
- `src/main/kotlin/dev/ambon/persistence/YamlPlayerRepository.kt:35–57` — `PlayerFile` (21 identical fields)
- `src/main/kotlin/dev/ambon/persistence/RedisCachingPlayerRepository.kt:19–41` — `PlayerJson` (21 identical fields)

The three data classes are field-for-field identical. Every time a new attribute is added to `PlayerRecord`, developers must update all three locations (and `PlayersTable.kt` for Postgres), or risk silent schema drift in the cache.

**Suggested refactor:** Remove `PlayerFile` and `PlayerJson`. Annotate `PlayerRecord` itself with Jackson annotations and use it directly for YAML and JSON serialization:
```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayerRecord(
    @JsonProperty("id")    val id: PlayerId,
    @JsonProperty("name")  val name: String,
    // ...
)
```

The custom `Jackson` module and `@JvmInline` on `PlayerId` require a value-class serializer, but that is a one-time fix. Alternatively, introduce a single `PlayerDto` (used by both YAML and Redis) with a bidirectional mapper. Either approach eliminates ~46 lines of duplicated field lists and the two `toDomain()` methods (see item 3 below).

---

### 3. `toDomain()` Mapping — Duplicated in YAML and Redis repos, ~26 lines each

**Where:**
- `src/main/kotlin/dev/ambon/persistence/YamlPlayerRepository.kt:199–225` — `PlayerFile.toDomain()`
- `src/main/kotlin/dev/ambon/persistence/RedisCachingPlayerRepository.kt:160–186` — `PlayerJson.toDomain()`
- `src/main/kotlin/dev/ambon/persistence/PostgresPlayerRepository.kt:150–175` — `ResultRow.toPlayerRecord()`

All three perform field-for-field copy-outs into `PlayerRecord`, including the identical legacy migration guard:
```kotlin
val migratedCon = if (constitution == 0) 10 else constitution
```

The Postgres variant has the same migration guard at line 158. If this migration rule ever changes or a second field needs migration, all three files must be updated.

**Suggested refactor:** Resolves naturally with item 2. If there is one serializable DTO, there is one `toDomain()`. The Postgres `ResultRow` mapper is unavoidably different (Exposed DSL), but can be reduced to a single file if a shared `PlayerRecord.Companion.from(fields…)` factory is introduced.

**Estimated reduction:** ~52 lines (two duplicate `toDomain()` bodies).

---

### 4. Redis Bus Subscriber Setup — Identical in Inbound and Outbound, ~16 lines

**Where:**
- `src/main/kotlin/dev/ambon/bus/RedisInboundBus.kt:33–49` — `startSubscribing()`
- `src/main/kotlin/dev/ambon/bus/RedisOutboundBus.kt:34–50` — `startSubscribing()`

Both methods follow the same 5-step algorithm:
1. `subscriberSetup.startListening(channelName) { message -> `
2. Parse envelope with Jackson
3. Drop if `instanceId == self`
4. Drop if signature invalid (warn)
5. Convert to domain event, `delegate.trySend()`; log parse errors

The only differences are the event type and log message text.

**Suggested refactor:** Extract an abstract `RedisEventBus<E, Env>` base class (or a top-level helper function) parameterized on event type and envelope type:
```kotlin
fun <Env : Any, Event : Any> startRedisSubscriber(
    setup: BusSubscriberSetup,
    channel: String,
    instanceId: String,
    sharedSecret: String,
    mapper: ObjectMapper,
    label: String,
    parseEnvelope: (String) -> Env,
    signatureCheck: (Env, String) -> Boolean,
    toEvent: (Env) -> Event?,
    sendToDelegate: (Event) -> Unit,
) { ... }
```

**Estimated reduction:** ~16 lines duplicated × 2 = ~32 lines collapsed to 1 shared function.

---

## 🟡 Medium-Impact Opportunities

### 5. `LocalInboundBus` / `LocalOutboundBus` — Identical Channel Depth-Tracking, ~35 lines

**Where:**
- `src/main/kotlin/dev/ambon/bus/LocalInboundBus.kt:11–45`
- `src/main/kotlin/dev/ambon/bus/LocalOutboundBus.kt:11–49`

Both classes are structurally identical: a `Channel<T>`, an `AtomicInteger depth`, and three operation methods (`send`, `trySend`, `tryReceive`) that increment/decrement `depth` with the same guard logic. The only difference is the event type parameter.

**Suggested refactor:** Extract `DepthTrackingChannel<T>` that wraps `Channel<T>` and owns the depth-tracking logic. Both local bus classes become thin wrappers that delegate to it:
```kotlin
class DepthTrackingChannel<T>(capacity: Int = Channel.UNLIMITED) {
    private val channel = Channel<T>(capacity)
    private val depth = AtomicInteger(0)
    suspend fun send(event: T) { ... }
    fun trySend(event: T): ChannelResult<Unit> { ... }
    fun tryReceive(): ChannelResult<T> { ... }
    fun depth(): Int = depth.get()
    fun close() = channel.close()
}
```

**Estimated reduction:** ~25 lines. Also makes the depth-tracking logic testable in isolation.

---

### 6. Redis Envelope Signature Helpers — Duplicated in Inbound and Outbound, ~6 lines

**Where:**
- `src/main/kotlin/dev/ambon/bus/RedisInboundBus.kt:112–117`
- `src/main/kotlin/dev/ambon/bus/RedisOutboundBus.kt:147–152`

Both classes define private extension functions `withSignature` and `hasValidSignature` that delegate to `hmacSha256`. The implementations are byte-for-byte identical; only the `payloadToSign()` field order differs (inbound uses `defaultAnsiEnabled`/`line`; outbound uses `text`/`enabled`).

The shared helpers (`withSignature`, `hasValidSignature`) should move to a top-level function in the `bus` package since neither depends on any instance state:
```kotlin
// BusEnvelopeSignature.kt
internal fun hmacSignature(secret: String, payload: String): String = hmacSha256(secret, payload)
internal fun verifySignature(secret: String, payload: String, signature: String): Boolean =
    signature.isNotBlank() && signature == hmacSha256(secret, payload)
```

The `payloadToSign()` functions remain per-class since their field lists differ. **Estimated reduction:** ~4 lines; primarily reduces cryptographic divergence risk.

---

### 7. Repository Timer/Metrics Scaffolding — Duplicated in YAML and Postgres, ~20 lines

**Where:**
- `src/main/kotlin/dev/ambon/persistence/YamlPlayerRepository.kt:77–93` (`findByName`), `97–105` (`findById`), `159–195` (`save`)
- `src/main/kotlin/dev/ambon/persistence/PostgresPlayerRepository.kt:19–31` (`findByName`), `34–46` (`findById`), `113–148` (`save`)

Both `findByName` and `findById` in each repository wrap their logic in:
```kotlin
val sample = Timer.start()
try { ... }
finally { sample.stop(metrics.playerRepoLoadTimer) }
```

Both `save()` methods additionally call `metrics.onPlayerSave()` on success and `metrics.onPlayerSaveFailure()` in the catch/finally.

**Suggested refactor:** Either (a) extract a `MetricsPlayerRepository` decorator that wraps any `PlayerRepository` and records timing, leaving the concrete repos metric-free; or (b) add inline helper functions `timedLoad { }` and `timedSave { }` that absorb the try/finally boilerplate. Option (a) follows the Decorator pattern already used by `WriteCoalescingPlayerRepository` and `RedisCachingPlayerRepository`.

**Estimated reduction:** ~20 lines of scaffolding across 6 method bodies.

---

### 8. `findByName` / `findById` Cache-Miss Pattern in `RedisCachingPlayerRepository`, ~15 lines

**Where:** `src/main/kotlin/dev/ambon/persistence/RedisCachingPlayerRepository.kt:47–81`

Both `findByName` and `findById` follow the same template:
```kotlin
try {
    val record = withContext(Dispatchers.IO) { /* read from cache */ } ?: null
    if (record != null) return record
} catch (e: Exception) {
    log.warn(e) { "Redis error ... - falling through to delegate" }
}
val record = delegate.findByXxx(arg)
if (record != null) cacheRecord(record)
return record
```

**Suggested refactor:** Extract a private `cachedLookup` helper:
```kotlin
private suspend fun cachedLookup(
    warningContext: String,
    fromCache: suspend () -> PlayerRecord?,
    fromDelegate: suspend () -> PlayerRecord?,
): PlayerRecord? {
    try {
        val cached = fromCache()
        if (cached != null) return cached
    } catch (e: Exception) {
        log.warn(e) { "Redis error in $warningContext - falling through to delegate" }
    }
    val record = fromDelegate()
    if (record != null) cacheRecord(record)
    return record
}
```

**Estimated reduction:** ~12 lines; also makes the fallthrough policy testable in one place.

---

## 🟢 Minor / Style Improvements

### 9. Registry Loader Boilerplate (`AbilityRegistryLoader` / `StatusEffectRegistryLoader`)

**Where:**
- `src/main/kotlin/dev/ambon/engine/abilities/AbilityRegistryLoader.kt:12–56`
- `src/main/kotlin/dev/ambon/engine/status/StatusEffectRegistryLoader.kt:10–53`

Both are `object` singletons with a single `load(config, registry)` function that iterates a config map, does string-to-enum `when` mapping, and calls `registry.register(...)`. The structural duplication is real but the domain content diverges substantially (abilities vs status effects), so the ROI on a generic `DefinitionLoader<C,D>` is modest. A lower-effort improvement is to replace the `when (str.uppercase()) { ... else -> continue }` chains with `enumValueOf`-style extension functions to make the unknown-value handling consistent and centralized.

---

### 10. `StaticZoneRegistry` No-Op Methods

**Where:** `src/main/kotlin/dev/ambon/sharding/StaticZoneRegistry.kt:56–66`

`claimZones()` and `renewLease()` are explicit no-op overrides with comments explaining why. The `ZoneRegistry` interface at `ZoneRegistry.kt:62` already provides a no-op default for `reportLoad`; the same default-implementation approach could be applied to `claimZones` and `renewLease`, marked as optional for static registries. This would clean up `StaticZoneRegistry` at the cost of making the interface less strict.

Given that `RedisZoneRegistry` must implement these methods non-trivially, leaving them abstract and keeping the no-op overrides explicit is actually safer. This is a low-priority style concern only.

---

### 11. `const val` String Literals for Redis Event Type Names

**Where:**
- `src/main/kotlin/dev/ambon/bus/RedisInboundBus.kt:120–145` and `RedisOutboundBus.kt:155–199`

Event type strings (`"Connected"`, `"LineReceived"`, `"SendText"`, etc.) appear both in the `publish()` `when` branches and the `toEvent()` `when` branches as untyped string literals. A typo would cause silent event loss (falling to the `else -> null` branch). Moving these to `companion object` constants or aligning them with `InboundEvent`/`OutboundEvent` class simple names would catch mismatches at compile time.

---

## 📐 Architectural Suggestions

### A. Persistence DTO consolidation is the highest-leverage change

The `PlayerRecord`/`PlayerFile`/`PlayerJson` triple is the most maintenance-risky duplication. Any new player attribute (e.g. a new stat, currency type, or flag) currently requires edits in four places: `PlayerRecord.kt`, `YamlPlayerRepository.kt`, `RedisCachingPlayerRepository.kt`, and `PlayersTable.kt` (plus a Flyway migration). A single serializable DTO or direct use of `PlayerRecord` for serialization cuts that to two (domain class + DB table).

### B. The Decorator chain in persistence is already the right pattern

`WriteCoalescingPlayerRepository → RedisCachingPlayerRepository → Yaml/PostgresPlayerRepository` is a clean Decorator stack. The `MetricsPlayerRepository` suggested in item 7 would fit naturally as an additional outer decorator, keeping the concrete repos focused on persistence logic only.

### C. Bus layer generics opportunity

`RedisInboundBus` and `RedisOutboundBus` share ~60% of their code (subscriber setup, signing, publishing pattern, delegate delegation). A generic `RedisBus<InEvent, OutEvent, Env>` base class would unify them and make it straightforward to add a third bus variant (e.g., a gRPC-backed bus for hybrid deployments) without duplicating the signing and retry logic again.

### D. Heartbeat management should be a first-class concern

`MudServer.start()` currently owns four `Job` fields and their launch logic. As the system grows, more periodic tasks (e.g., metrics flushing, world state snapshots) will likely be added. A `HeartbeatManager` or `PeriodicTaskRunner` class that takes a list of `(intervalMs, name, block)` descriptors would centralize lifecycle management and make it easy to add/remove tasks without touching `MudServer`.

---

## Impact Summary

| Finding | Files | Lines Saved | Priority |
|---------|-------|-------------|----------|
| 1. Heartbeat loops | 1 | ~40 | High |
| 2. DTO triple schema | 3 | ~46 | High |
| 3. `toDomain()` mapping | 3 | ~52 | High |
| 4. Redis subscriber setup | 2 | ~32 | High |
| 5. Channel depth tracking | 2 | ~25 | Medium |
| 6. Envelope signature helpers | 2 | ~4 | Medium |
| 7. Repository timer scaffolding | 2 | ~20 | Medium |
| 8. Cache-miss pattern | 1 | ~12 | Medium |
| **Subtotal** | | **~231 lines** | |
