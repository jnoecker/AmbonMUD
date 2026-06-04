# Mob Dialogue Voice-Over Contract

Shared spec for NPC dialogue voice-overs across two repos:

- **Arcanum** (content tooling) — synthesizes each dialogue line via ElevenLabs and uploads
  the audio to R2 at the agreed path. Owns the API key, voice mapping, cost, and licensing.
- **AmbonMUD** (this repo) — forwards a fully-resolved clip URL to the web client over GMCP;
  the web client plays it through the existing audio engine.

The two repos build independently against the path + field shapes below. The only thing they
must agree on is the **R2 path template** (and, if used, the hash algorithm).

## Division of labor

| Side | Responsibility |
|---|---|
| Arcanum | Walk dialogue YAML → ElevenLabs → upload MP3 to R2 at the path template. Owns the `templateKey → ElevenLabs voiceId` map, the API key, regen-on-change, cost, and license compliance. |
| AmbonMUD engine | Resolve the clip URL (base + path) and send it in the `Dialogue.Node` GMCP package. No synthesis, no key, no network calls in the engine. |
| Web client | On `Dialogue.Node`, play `voiceUrl` through the audio engine; stop on `Dialogue.End`. Mute/skip toggle. |

Voice is a **web-client-only** feature, like canvas rendering. Telnet clients receive the same
text they do today and simply have no audio.

## R2 path shape

```
voices/<zone>/<templateKey>/<nodeId>.mp3
```

- `<zone>` — the zone segment of the mob's `RoomId` (`<zone>:<room>`). Disambiguates
  `templateKey` collisions across zones.
- `<templateKey>` — the mob template key (e.g. `headmaster_aldric`), present on `MobState`.
- `<nodeId>` — the dialogue node's map key (e.g. `root`, `quest_info`, `farewell`).
- `.mp3` — ElevenLabs' default output. Web Audio's `decodeAudioData` decodes MP3 natively,
  so no transcoding is required.

This tuple is the natural primary key for a line: human-debuggable in the R2 console, and
Arcanum has all three values while walking the YAML. Both sides construct the identical string
from the same three values.

### Edit-safety variant (recommended)

Append a short content hash of the line text:

```
voices/<zone>/<templateKey>/<nodeId>.<sha8>.mp3
```

where `<sha8>` is the first 8 hex chars of `SHA-256(text)` over the exact node text string.

- A text edit changes the hash → new filename → no stale audio is ever served; old clips
  become GC-able.
- The engine computes `<sha8>` from the same `node.text` it already sends, so the URL stays
  deterministic and the two repos stay in sync with no extra coordination beyond agreeing on
  "SHA-256, first 8 hex chars, of the raw text".

Without the hash, an edited line plays the previous clip until Arcanum overwrites it in place —
acceptable for a CDN, but it leaves a staleness window during content authoring.

## GMCP shape (AmbonMUD → web client)

One nullable field is added to the existing `Dialogue.Node` payload. Choices are **not** voiced.

```kotlin
// GmcpEmitter.kt — DialogueNodePayload
private data class DialogueNodePayload(
    val mobName: String,
    val text: String,
    val voiceUrl: String?, // fully-resolved clip URL, or null when no clip applies
    val choices: List<DialogueChoicePayload>,
)
```

- **`voiceUrl`** — the engine prepends the configured voices base and sends a **fully-resolved
  URL**, exactly as `image` is resolved today (`GmcpEmitter.kt` resolves
  `image = "$imagesBase${it.image}"`). The client consumes the URL verbatim and never
  concatenates paths.
- Named `voiceUrl`, not `voice`, to avoid confusion with the ElevenLabs **voiceId** — a purely
  Arcanum-side concept the client never sees.
- Nullable: unvoiced lines, missing clips, and non-web transports all degrade silently.

The client never receives `voiceId` or `templateKey` — only the resolved URL.

## Engine config

A `voices` block mirrors the existing `images` / `videos` config blocks:

```yaml
ambonmud:
  voices:
    enabled: false                          # opt-in; when false no voiceUrl is emitted
    baseUrl: "https://<r2-host>/voices/"    # local fallback: /voices/
```

`enabled` defaults to **false** so the engine emits `voiceUrl: null` until a real clip CDN is
wired up — no 404 noise in local/dev, and zero behavior change by default. `voicesBaseUrl` is
injected into `GmcpEmitter` the same way `imagesBaseUrl` is, with the same trailing-slash guard
(`voicesBase`). The engine emits `voiceUrl` only when `enabled` is true **and** the line has a
complete identity (non-blank zone, templateKey, and nodeId); otherwise it sends `null`.

> **Path version note:** the engine currently emits the **structural** path
> (`voices/<zone>/<templateKey>/<nodeId>.mp3`). The `.<sha8>` edit-safe variant is documented
> above but not yet implemented — adopting it is a one-line change to `dialogueVoiceUrl` in
> `GmcpEmitter.kt` plus the matching change in Arcanum's generator, and does not change the
> `voiceUrl` field shape.

## Engine change surface

Small — a state addition and a signature tweak, no new systems:

1. **Store `templateKey` + `zone`** on the dialogue conversation state at `startConversation`
   (the mob object is in hand there; `DialogueState` currently keeps only `mobId`/`mobName`).
2. **Thread `nodeId`** into `renderNode` (both call sites: `startConversation` has the
   `rootNodeId`, `selectChoice` has the resolved `nextNodeId`).
3. **Resolve `voiceUrl`** (`voicesBase` + path template) and pass it to `sendDialogueNode`.

## Web client change surface

1. Parse `voiceUrl` in the `Dialogue.Node` case of `applyGmcpPackage.ts`.
2. On `Dialogue.Node`, play `voiceUrl` through the existing audio engine
   (`web-v3/src/hooks/useAudioEngine.ts` already owns an `AudioContext` with music/ambient buses
   — add a voice bus).
3. On `Dialogue.End`, stop any in-flight voice playback.
4. Add a mute/skip toggle; honor it before playback.

## Summary

| Concern | Decision |
|---|---|
| GMCP field | `voiceUrl: String?` on `DialogueNodePayload`, fully-resolved URL |
| R2 path | `voices/<zone>/<templateKey>/<nodeId>.mp3` (`+.<sha8>` for edit-safety) |
| Hash (if used) | SHA-256 of raw node text, first 8 hex chars |
| Audio format | MP3 (ElevenLabs default, Web Audio-native) |
| Engine config | `ambonmud.voices.enabled` (opt-in) + `ambonmud.voices.baseUrl`, local `/voices/` fallback |
| Voiced content | NPC node text only; choices stay text |
| Identity sent to client | Resolved URL only — never `voiceId` / `templateKey` |
| Transport scope | Web client only; telnet unaffected |
