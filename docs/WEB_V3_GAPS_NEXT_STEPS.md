# Web Client v3: Known Gaps and Next Steps

Date: 2026-02-27

## Known Gaps

1. Unused GMCP surface in UI
- Server emits packages that v3 currently ignores.
- Not rendered today: `Char.Skills`, `Comm.Channel`, `Group.Info`, `Char.Achievements`, `Core.Ping`, `Char.StatusVars`.
- Impact: useful gameplay context exists but is not visible in v3.

2. Frontend is monolithic
- `web-v3/src/App.tsx` contains most logic (state, ws lifecycle, reducers, rendering).
- Impact: feature work and testing are slower; regressions are harder to isolate.

3. No frontend checks in CI
- CI currently validates only Kotlin/Gradle checks.
- Missing: `web-v3` lint/type/build verification.
- Impact: frontend breakage can merge undetected.

4. Local dev ergonomics are incomplete
- v3 dev server setup does not document/provide a WS proxy to backend `/ws`.
- Impact: local iteration may require manual setup depending on launch method.

5. Limited frontend test coverage
- Backend transport tests validate protocol and routing, but v3 React behavior has no dedicated automated tests.
- Impact: UI and GMCP reducer regressions rely on manual testing.

6. Client map model is heuristic only
- map graph is inferred from observed exits and local room IDs.
- no persistence or reconciliation strategy across reconnects/sessions.
- Impact: map can be incomplete/inaccurate in complex movement cases.

## Recommended Next Steps (Prioritized)

1. Foundation: split `App.tsx` into modules
- Extract:
  - `useMudSocket` (connect/reconnect/send, ws events)
  - `gmcpReducer` or package handler map
  - `useCommandHistory`
  - `useMiniMap`
  - panel components
- Outcome: faster, lower-risk feature additions.

2. Add one high-value GMCP feature end-to-end
- Best first options:
  - `Comm.Channel` chat panel, or
  - `Char.Skills` abilities panel.
- Include minimal UI plus reducer support and manual test checklist.

3. Wire frontend checks into CI
- Add a CI job (or step) to run in `web-v3/`:
  - install deps
  - lint
  - typecheck/build
- Outcome: catches frontend breakage before merge.

4. Add frontend tests for GMCP handling
- Unit tests for parser + reducer package mapping.
- Smoke tests for key render states (pre-login, connected, data-populated).
- Outcome: protects protocol-to-UI contract.

5. Improve dev workflow docs
- Document exact local commands and topology for running backend + v3 dev server together.
- Document how WS path `/ws` is reached during dev.

6. Expand GMCP package support in UI
- Phase 1: `Comm.Channel`, `Char.Skills`.
- Phase 2: `Group.Info`, `Char.Achievements`.
- Phase 3: `Core.Ping` handling and optional connection telemetry.

## Candidate Work Queue

1. Refactor: split v3 app into hooks/components (no behavior change).
2. Feature: `Comm.Channel` panel with channel filters.
3. Feature: `Char.Skills` panel with mana/cooldown hints.
4. Infra: CI job for `web-v3` lint/build.
5. Tests: GMCP reducer test suite.
6. Feature: `Group.Info` compact party widget.

## Suggested Definition of Done for Any New v3 Feature

- GMCP package contract documented in code comments.
- UI behavior covered by at least one automated test.
- Manual acceptance steps written in PR notes.
- No regression in `/v3` serving or `/ws` protocol behavior.
