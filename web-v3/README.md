# AmbonMUD Web Client v3

Standalone Vite + React + TypeScript frontend for the current browser client.

## Commands

```bash
bun install
bun run dev
bun run lint
bun run build
```

## Output contract

`bun run build` writes production assets directly to:
- `../src/main/resources/web-v3`

Server behavior:
- `/` serves `web-v3` assets
- `/v3` and `/v3/` redirect to `/`
- gameplay socket endpoint is `/ws`

## See also

- Consolidated v3 architecture/status doc: [../docs/WEB_V3.md](../docs/WEB_V3.md)
- Project overview: [../README.md](../README.md)
