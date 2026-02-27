# AmbonMUD Web Client v3

Standalone Vite + React + TypeScript frontend for the new client experience.

## Commands

```bash
bun install
bun run dev
bun run lint
bun run build
```

## Output contract

- `bun run build` writes production assets directly to:
  - `../src/main/resources/web-v3`
- Server static root:
  - `/` serves `web-v3` assets
- Compatibility redirect routes:
  - `/v3` and `/v3/` redirect to `/` (configured in `KtorWebSocketTransport.kt`)

The legacy web client assets remain in `src/main/resources/web`, but current runtime static serving targets `web-v3`.
