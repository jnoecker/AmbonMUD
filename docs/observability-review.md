# Observability Review: Metrics, Monitoring, and Logging Coverage

## Scope
This review covers the current instrumentation and telemetry posture of AmbonMUD with a focus on:

- Runtime metrics (engine, transport, persistence, gateway/gRPC)
- Monitoring readiness (scraping, alerting, dashboards, SLO/SLI posture)
- Logging quality (structure, context, signal-to-noise, operational debugging)

## Current strengths

1. **Good baseline metric surface area already exists.**
   `GameMetrics` tracks session lifecycle, transport throughput/backpressure, engine tick timing, combat/mob/scheduler activity, XP/death/level progression, persistence save/load timing, and gateway reconnect behavior.
2. **Metrics are available in all deployment modes.**
   Standalone/Gateway can expose `/metrics` via Ktor, and ENGINE mode has a dedicated metrics HTTP server.
3. **Package-level logging controls are configurable.**
   Runtime config supports global level + per-package overrides, making targeted debug sessions possible without code changes.
4. **Prometheus + Grafana are already in local infra.**
   This lowers implementation friction for dashboard and alert expansion.

---

## High-impact gaps and opportunities

## 1) Add **state/queue saturation gauges** (highest impact)

### Why this matters
Current counters show failures after they happen (e.g., backpressure disconnects), but there is little early-warning telemetry for **how close** systems are to saturation.

### Recommendations
- Add gauges for:
  - `inbound_bus_queue_depth`
  - `outbound_bus_queue_depth`
  - `session_outbound_queue_depth` (tagged by transport and optionally session bucket)
  - `write_coalescer_dirty_count`
  - `scheduler_pending_actions`
- Add capacity gauges and/or constant tags so dashboards can plot `% utilized`.

### Suggested implementation points
- `MudServer.kt` and `GatewayServer.kt` for channel capacity/depth wiring.
- `OutboundRouter.kt` for per-session outbound pressure aggregation.
- `WriteCoalescingPlayerRepository.kt` for dirty/pending write counts.
- `Scheduler.kt` for pending action count.

---

## 2) Improve **error cardinality + causality** metrics

### Why this matters
Several failures are logged but not consistently reflected in metrics (or the reason taxonomy is too coarse). This limits alert precision.

### Recommendations
- Extend metric reason labels in a controlled enum-style taxonomy (avoid free-form high-cardinality strings).
- Add counters for:
  - authentication failures by phase (`name_invalid`, `password_mismatch`, `locked_out`, etc.)
  - command parse failures by category
  - world load validation failures by rule type
  - zone handoff failures by reason
  - Redis fallback activations (cache miss vs cache error vs bus publish failure)
- Add explicit metric coverage for metrics endpoint failures/startup failures in ENGINE mode.

### Suggested implementation points
- `GameEngine.kt` (login/command/handoff flows)
- `WorldLoader.kt`
- `RedisConnectionManager.kt`, `RedisInboundBus.kt`, `RedisOutboundBus.kt`
- `MetricsHttpServer.kt`

---

## 3) Add **monitoring artifacts** (alerts + dashboards as code)

### Why this matters
Prometheus/Grafana exist, but the repository does not define operational alert policy. This makes incidents depend on ad hoc observation.

### Recommendations
- Add Prometheus alert rules in `infra/` for:
  - sustained tick overruns
  - reconnect budget exhaustion
  - outbound backpressure disconnect spikes
  - player save failures > 0 over rolling windows
  - metrics endpoint down
- Provision baseline Grafana dashboards for:
  - engine health (tick loop latency/overrun)
  - transport health (connections, ingress/egress, backpressure)
  - persistence health (save/load latency + failures)
  - gateway/sharding health (stream drops, reconnects, handoffs)
- Add runbook links from alert annotations.

### Suggested implementation points
- `infra/prometheus.yml` + new `infra/prometheus-alerts.yml`
- `infra/grafana/provisioning` dashboard + alert provisioning JSON/YAML
- `README.md` / `docs/onboarding.md` runbook sections

---

## 4) Move toward **structured logging with correlation context**

### Why this matters
Current logs are plain text with interpolated values. They are human-readable but harder to query/aggregate at scale.

### Recommendations
- Introduce JSON log profile (opt-in via config/env) while preserving current console format for local dev.
- Standardize key fields:
  - `session_id`, `player_name`, `engine_id`, `gateway_id`, `zone`, `room_id`, `event_type`, `reason`
- Use MDC for request/session context in transport and gateway pipelines.
- Add log sampling/rate-limiting for bursty warnings (e.g., repeated stream full/drop warnings).

### Suggested implementation points
- `src/main/resources/logback.xml`
- `Main.kt` for profile toggle
- `KtorTelnetTransport.kt`, `KtorWebSocketTransport.kt`, `GatewayServer.kt`, `GameEngine.kt`

---

## 5) Expand **histogram usage and SLI-friendly metrics**

### Why this matters
Some timers already publish histograms, but not uniformly across latency-sensitive operations.

### Recommendations
- Enable percentile histograms consistently for:
  - persistence save/load timers
  - reconnect recovery duration
  - command routing latency (parse→dispatch→response enqueue)
- Add SLI-style counters:
  - `commands_total{result="ok|error|unknown"}`
  - `logins_total{result="success|failure"}`
  - `handoffs_total{result="success|timeout|reject"}`
- Document target SLOs (e.g., p95 tick duration, reconnect recovery time, save failure rate).

### Suggested implementation points
- `GameMetrics.kt` + call sites in engine/gateway/persistence modules

---

## 6) Add **telemetry tests and contract checks**

### Why this matters
Instrumentation can silently regress when features evolve.

### Recommendations
- Add tests that assert critical meter names/tags for:
  - engine tick / overrun
  - reconnect attempts/success/exhaustion
  - backpressure and forced disconnect behavior
  - persistence failures
- Add a “metric catalog” test (or generated doc) that snapshots key meters and expected labels.

### Suggested implementation points
- Extend `src/test/kotlin/dev/ambon/metrics/GameMetricsTest.kt`
- Add focused integration tests around `OutboundRouter`, gateway reconnect, and persistence wrappers

---

## Prioritized rollout plan

1. **Phase 1 (fast wins):** queue depth gauges + alert rules + dashboard basics.
2. **Phase 2:** structured logging profile + MDC correlation fields.
3. **Phase 3:** SLI/SLO metrics and histogram normalization.
4. **Phase 4:** telemetry contract tests and metric catalog automation.

## Proposed immediate next tasks (small PRs)

1. Add queue/depth gauges (`OutboundRouter`, coalescer, scheduler) and expose capacities.
2. Add `infra/prometheus-alerts.yml` with 5–7 core alerts.
3. Provision one “Engine Health” Grafana dashboard JSON.
4. Add structured-logging config toggle + JSON encoder profile.
5. Expand `GameMetricsTest` with meter/tag assertions for new metrics.

## Expected operational outcomes

If the above is implemented, AmbonMUD should gain:

- Earlier detection of saturation before disconnect cascades.
- Faster root-cause analysis for reconnect/backpressure/persistence incidents.
- Better confidence in sharded/gateway operation under load.
- Repeatable observability via versioned dashboards/alerts and test-backed telemetry contracts.
