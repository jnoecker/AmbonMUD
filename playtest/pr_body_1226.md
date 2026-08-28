Closes #1226, plus the two related asks: a metrics audit covering everything added since the original Grafana setup, and public read-only dashboards.

## Metrics audit

The gameplay metrics were frozen at initial-setup vintage — XP, level-ups, deaths, combat ticks, mobs, zones. **Nothing** for auction, lottery, gambling, trade, duels, bank, crafting, enchanting, dungeons, housing, pets, prestige, puzzles, guilds, mail, or demo accounts.

## New instrumentation (commit 1)

One bounded-cardinality counter family — `game_events_total{system, event}` — using the same whitelist pattern as `KNOWN_TICK_PHASES` (unknown pairs are logged and dropped, all whitelisted pairs pre-registered so they exist at scrape time even at zero). Wired at the success point of each system:

| system | events |
|---|---|
| **demo** (#1226) | created, claimed |
| quest | accepted, completed, abandoned |
| crafting | craft, gather, enchant |
| auction | listed, sold, cancelled |
| tavern | lottery_ticket (by count), gamble, gamble_win |
| bank | deposit_gold, withdraw_gold, deposit_item, withdraw_item |
| trade / duel / dungeon | completed / started+completed / entered+completed |
| puzzle / housing / stylist / prestige / pet / guild / mail / ability | solved / purchased+expanded / race_change / prestiged / summoned / created / sent / cast |

Plus **`demo_players_online`** — online players with no `playerId`, i.e. unclaimed demo characters — so `players_online` splits into demo vs registered traffic.

Plumbing: `EngineContext` gains a `metrics` field (defaults to `GameMetrics.noop()`, so no test harness changes); GameEngine wires the callback-based systems; `QuestSystem` gains `onQuestAccepted`/`onQuestAbandoned` hooks in its existing callback style. Tests cover increment, count-weighted increment, unknown-pair dropping, full-whitelist pre-registration, and the demo gauge.

## Dashboards (commit 2)

`ambon_gameplay.json` gains: **Demo Players Online / Demo Created (24h) / Demo Claimed (24h)** stats, and six hourly timeseries — demo, quest, economy (auction/tavern/bank/trade), crafting, adventure (dungeon/duel/puzzle/ability), social & progression. Hourly `increase()` rather than per-second `rate()` because the demo's traffic rounds to zero at per-second resolution.

## Public Grafana (commit 3)

- nginx: `/grafana/` basic-auth **removed** (with an in-config comment explaining the deliberate exposure); `/prometheus/` and `/admin/` stay gated.
- The container already had anonymous Viewer enabled — but un-gating nginx would have exposed the hardcoded `GF_SECURITY_ADMIN_PASSWORD=admin` as a working login. A new `generate-grafana-env` ExecStartPre derives the admin password from the **SSM admin token** (re-running `fetch-admin-token` itself, since `secrets.env` is otherwise only written when `ambonmud.service` first starts); the `admin` fallback only applies to dev installs with no SSM parameter, where nginx isn't fronting Grafana at all.
- Hardening: sign-up disabled, `GF_EXPLORE_ENABLED=false` (anonymous = provisioned dashboards only, no ad-hoc PromQL), gravatar/analytics off.
- Local `docker-compose.yml` mirrors the anonymous-viewer settings; `DEPLOYMENT.md` documents the rationale and the SSM-shell steps to apply this to the running instance (user data only executes at first boot — **the live box needs either an instance refresh or the documented manual steps**).

## Verification

`ktlintCheck`, full `test`, `integrationTest` pass; `npx tsc --noEmit` clean on the CDK app; dashboard JSON validated.
