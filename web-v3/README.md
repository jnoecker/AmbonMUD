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
- Server route:
  - `/v3` (configured in `KtorWebSocketTransport.kt`)

This keeps the old client intact while serving v3 in parallel.
