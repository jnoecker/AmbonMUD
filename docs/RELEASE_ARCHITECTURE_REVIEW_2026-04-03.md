# AmbonMUD Release Architecture Review (2026-04-03)

## Scope and method

This review focused on release-readiness blockers and architecture-level opportunities across:

- runtime composition (`Main`, `MudServer`, `EngineServer`, `GatewayServer`),
- transport and protocol boundaries (telnet/websocket/gRPC/Redis bus),
- persistence paths (YAML/Postgres/Redis-coalescing),
- operational interfaces (admin HTTP + metrics),
- configuration and deployment defaults.

Code walk-through was paired with a verification attempt via `./gradlew ktlintCheck test`.

---

## Executive summary

### Release blockers (must resolve before grand release)

1. **gRPC engine↔gateway traffic is plaintext and unauthenticated by default.**
   - Gateway connections call `ManagedChannelBuilder...usePlaintext()` and no mTLS/API auth is enforced in the gRPC service.
   - In multi-node/edge deployments this is a critical trust-boundary gap (session events and gameplay state can be intercepted or spoofed if network perimeter is misconfigured).

2. **Admin surface and metrics surfaces are network-exposed without a hard production guardrail.**
   - Admin uses Basic auth token comparison, but no built-in TLS termination/rate-limit/lockout.
   - Metrics endpoint is exposed via HTTP and engine metrics server can run as a standalone listener.
   - Current config validation warns on weak CORS patterns but does not enforce hardened production defaults.

3. **Security-sensitive defaults remain placeholder/unsafe for production unless operators override them perfectly.**
   - Example defaults include DB password `changeme` and Redis bus shared secret `CHANGE_ME`.
   - This is acceptable for local dev, but for “grand release” posture, startup should fail-fast in production mode when insecure defaults are detected.

### Significant non-blocking risks

4. **YAML auth-token lookup path is O(N files) scan by design.**
   - Works for small servers, but token-based login can degrade sharply with larger player counts.

5. **Large God-object concentration in core runtime classes increases regression risk.**
   - `GameEngine` (~2.1k LOC) and `AdminHttpServer` (~1.4k LOC) carry broad responsibilities, raising blast radius for changes and incident response complexity.

6. **Build/test reproducibility depends on external artifact availability during verification.**
   - Local verification failed due inability to fetch JaCoCo artifact (HTTP 403 from Maven Central in this environment).

---

## Findings and recommendations

## 1) P0 blocker — secure gRPC transport and peer auth are not enforced

### Evidence
- Gateway uses plaintext gRPC channels (`usePlaintext`) in both single and multi-engine startup flows.
- Engine gRPC server is started without transport credentials/auth interceptors.
- gRPC stream service accepts inbound event streams and routes sessions without caller authentication.

### Why this matters
In `ENGINE`/`GATEWAY` split deployments, this is the highest-value control plane. A network mistake (flat VPC, permissive SG, sidecar bypass) could allow:
- command/event injection,
- replay of session events,
- metadata leakage.

### Recommendation
- Add mTLS support (`NettyChannelBuilder`/`TlsChannelCredentials`) and server cert validation.
- Add gateway identity auth (JWT/HMAC or mTLS SPIFFE identity) checked in gRPC interceptor.
- Fail startup in `ENGINE`/`GATEWAY` modes when plaintext is configured unless an explicit dev-only override is set.

---

## 2) P0 blocker — operational HTTP surfaces need stronger production controls

### Evidence
- Admin auth is Basic + shared token only.
- Metrics HTTP endpoint is exposed without auth.
- Web transport binds host `0.0.0.0` by default.

### Why this matters
Operational endpoints are typical first compromise points. Even with reverse proxies, the app should provide fail-safe defaults and explicit hardening toggles.

### Recommendation
- Add production-mode validation requiring one of:
  - loopback bind, or
  - trusted reverse-proxy mode + explicit allowlist.
- Add optional native rate-limiting / IP throttle for admin auth.
- Add startup warning/error when admin enabled without TLS termination marker.
- Allow metrics server host binding override and default to loopback in non-standalone prod modes.

---

## 3) P0 blocker — insecure placeholder secrets should fail in production mode

### Evidence
- `application.yaml` includes placeholder/default credentials (`changeme`, `CHANGE_ME`).
- Validation currently checks non-blank but not “known weak default”.

### Why this matters
Configuration drift in release deployments is common. A missed secret override becomes a severe avoidable incident.

### Recommendation
- Add config validator checks rejecting known placeholder values in production profile/mode.
- Emit actionable startup errors naming exact keys.

---

## 4) P1 risk — YAML auth-token lookup is linear scan

### Evidence
- `YamlPlayerRepository.findByAuthTokenHash` scans all player files.

### Why this matters
Token auth latency scales with player-file count and can become a hotspot during reconnect storms.

### Recommendation
- For YAML backend, add optional token-hash index file (atomic update) or in-memory index with reload semantics.
- Document upper bound for recommended YAML backend scale; steer production to Postgres backend by default.

---

## 5) P1 risk — core classes are too large for safe rapid change

### Evidence
- `GameEngine.kt` ~2144 lines.
- `AdminHttpServer.kt` ~1440 lines.

### Why this matters
Large classes reduce testability and make ownership boundaries blurry; high risk during release-week hotfixes.

### Recommendation
- Prioritized refactor plan after release:
  - extract login/auth token flow service,
  - isolate GMCP sync orchestration,
  - split admin route handlers by domain (players/world/config/liveops).

---

## 6) P1 risk — verification pipeline resilience

### Evidence
- `./gradlew ktlintCheck test` failed in this environment due JaCoCo artifact fetch 403.

### Why this matters
Grand release requires predictable CI and reproducible local verification.

### Recommendation
- Ensure CI has mirrored/proxied artifact repositories.
- Add fallback or cache strategy in release runbook (dependency cache warm-up).

---

## Positive architecture notes

- Clean engine/transport event boundary is maintained through `InboundBus`/`OutboundBus` abstraction.
- Backpressure behavior is explicit in outbound routing (including slow-client disconnect strategy).
- Persistence layering (coalescing + optional Redis cache + backend) is well-separated and testable.
- Sharding primitives (zone registry, handoff manager, instance selector) are clearly separated and injectable.

---

## Recommended release gate checklist

Before grand release, require all of the following:

1. gRPC mTLS + peer auth enabled in ENGINE/GATEWAY deployment.
2. Admin interface behind TLS + allowlist + token rotation policy documented.
3. Metrics endpoints bound to private interfaces or protected by network policy.
4. Placeholder secrets rejected at startup in production profile.
5. CI full suite green (`ktlintCheck`, `test`, `integrationTest`) from clean cache in release environment.
6. Load test pass for reconnect + auth-token flows at target CCU.

