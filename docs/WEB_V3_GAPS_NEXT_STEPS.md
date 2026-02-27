# Web Client v3: Known Gaps and Next Steps

Date: 2026-02-27
Status: Updated to reflect current v3 implementation after social and combat-skills panel work.

## Known Gaps

1. Unused GMCP surface in UI
- Server emits packages that v3 currently ignores.
- Not rendered today: `Group.Info`, `Char.Achievements`, `Core.Ping`, `Char.StatusVars`.
- Impact: useful gameplay context exists but is not visible in v3.

2. No frontend checks in CI
- CI currently validates only Kotlin/Gradle checks.
- Missing: `web-v3` lint/type/build verification.
- Impact: frontend breakage can merge undetected.

3. Local dev ergonomics are incomplete
- v3 dev server setup does not document/provide a WS proxy to backend `/ws`.
- Impact: local iteration may require manual setup depending on launch method.

4. Limited frontend test coverage
- Backend transport tests validate protocol and routing, but v3 React behavior has no dedicated automated tests.
- Impact: UI and GMCP reducer regressions rely on manual testing.

5. Client map model is heuristic only
- map graph is inferred from observed exits and local room IDs.
- no persistence or reconciliation strategy across reconnects/sessions.
- Impact: map can be incomplete/inaccurate in complex movement cases.

6. Frontend bundle is large
- `bun run build` reports the main JS chunk above 500 kB.
- Impact: initial load can degrade on slower clients; harder future scaling without code splitting.

## Recommended Next Steps (Prioritized)

1. Add next high-value GMCP feature end-to-end
- Best first option: `Group.Info` compact party panel/widget.
- Include minimal UI plus reducer support and manual test checklist.

2. Add frontend tests for GMCP handling
- Unit tests for parser + package mapping (`applyGmcpPackage`).
- Smoke tests for key render states (pre-login, connected, data-populated).
- Outcome: protects protocol-to-UI contract.

3. Wire frontend checks into CI
- Add a CI job (or step) to run in `web-v3/`:
  - install deps
  - lint
  - typecheck/build
- Outcome: catches frontend breakage before merge.

4. Improve dev workflow docs
- Document exact local commands and topology for running backend + v3 dev server together.
- Document how WS path `/ws` is reached during dev.

5. Expand GMCP package support in UI
- Phase 1: `Group.Info`.
- Phase 2: `Char.Achievements`.
- Phase 3: `Core.Ping` handling and optional connection telemetry.

6. Reduce bundle size (post-feature baseline)
- Introduce route/panel-level lazy loading where practical.
- Consider manual chunking for terminal-heavy dependencies.

## Candidate Work Queue

1. Feature: `Group.Info` compact party widget.
2. Feature: `Char.Achievements` progress panel.
3. Tests: GMCP package handler/unit test suite.
4. Infra: CI job for `web-v3` lint/build.
5. Docs: local dev + WS proxy workflow.
6. Performance: bundle-size reduction via chunking/lazy loading.

## Suggested Definition of Done for Any New v3 Feature

- GMCP package contract documented in code comments.
- UI behavior covered by at least one automated test.
- Manual acceptance steps written in PR notes.
- No regression in `/v3` serving or `/ws` protocol behavior.
