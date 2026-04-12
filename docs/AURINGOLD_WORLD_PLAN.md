# This document has been consolidated into [WORLD_YAML_SPEC.md](./WORLD_YAML_SPEC.md) and [DEPLOYMENT.md](./DEPLOYMENT.md)

Auringold shipped as the live world for AmbonMUD. This file was the original design plan for the replacement world — the planning content is historical and the shipped zones now live in the Cloudflare R2 "lore repo" at [auringold.ambon.dev](https://auringold.ambon.dev), not in this repo.

**Where to look now:**

- **Zone YAML schema for authoring new Auringold zones:** [`WORLD_YAML_SPEC.md`](./WORLD_YAML_SPEC.md)
- **How R2 zones reach the live demo instance:** [`DEPLOYMENT.md § Remote world & config overlay`](./DEPLOYMENT.md#remote-world--config-overlay-auringold)
- **Bundled starter zone (Auringold Academy, the only zone in this repo):** `src/main/resources/world/auringold_academy.yaml`
- **Dungeon template format:** [`DUNGEON_TEMPLATE_REFERENCE.md`](./DUNGEON_TEMPLATE_REFERENCE.md)
- **Per-zone environment themes:** [`ENVIRONMENT_THEMES.md`](./ENVIRONMENT_THEMES.md)

The full original design plan is preserved in git history:

```
git log --follow -- docs/AURINGOLD_WORLD_PLAN.md
```
