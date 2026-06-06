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

This is the canonical path. **Both repos must build it identically** — the engine resolves it
into `voiceUrl`, and Arcanum must upload to exactly this key or the client 404s.

```
voices/<zone>/<templateKey>/<nodeId>.<sha8>.mp3
```

- `<zone>` — the zone segment of the mob's `RoomId` (`<zone>:<room>`). Disambiguates
  `templateKey` collisions across zones.
- `<templateKey>` — the mob template key (e.g. `headmaster_aldric`), present on `MobState`.
- `<nodeId>` — the dialogue node's map key (e.g. `root`, `quest_info`, `farewell`).
- `<sha8>` — the **first 8 lowercase-hex chars of `SHA-256` over the raw node text**, UTF-8
  encoded (see "Hash spec" below). Makes the path edit-safe: changing a line yields a new
  filename, so stale audio is never served and old clips become GC-able.
- `.mp3` — ElevenLabs' default output. Web Audio's `decodeAudioData` decodes MP3 natively,
  so no transcoding is required.

The engine computes `<sha8>` from the same `node.text` it already sends, so the URL stays
deterministic with no extra coordination beyond agreeing on the hash spec.

### Hash spec

`<sha8>` must be computed identically on both sides:

1. Take the **raw node text** string — exactly as the YAML parser materializes it, no trimming,
   normalization, or interpolation.
2. Encode it as **UTF-8** bytes.
3. `SHA-256` those bytes.
4. Render the digest as **lowercase hex** and take the **first 8 characters**.

Reference vectors (verify your generator against these):

| YAML | parsed text | sha8 |
|---|---|---|
| `text: Hello!` | `Hello!` | `334d016f` |
| `text: Hello there!` | `Hello there!` | `89b8b8e4` |
| `text: \|` + `Hello there!` / `Stay a while.` | `Hello there!\nStay a while.\n` | `df658e4d` |

Equivalent shell check: `printf 'Hello!' | sha256sum` → starts with `334d016f`.
The engine implementation lives in `GmcpEmitter.sha8(...)`.

#### Block-scalar parity (the one real desync risk)

Plain scalars are unambiguous across parsers. **Block scalars are not** — the trailing-newline
outcome depends on the chomping indicator, and that newline is hash-significant:

- `|` (clip, the default) → interior newlines preserved, **exactly one** trailing newline.
  `df658e4d` above is this case.
- `|-` (strip) → **no** trailing newline (`Hello there!\nStay a while.` → a different hash).
- `>` (fold) → newlines folded to spaces.

The engine follows standard YAML 1.1 chomping via Jackson's `YAMLFactory` (SnakeYAML). Arcanum's
parser must agree byte-for-byte. The third vector above is the end-to-end check for this: author
that literal `|` block in real YAML, parse it on the Arcanum side, and confirm you get
`Hello there!\nStay a while.\n` → `df658e4d`. The engine side is pinned by
`DialogueVoiceHashVectorTest` (`src/test/.../domain/world/load/`), which loads these through the
real `WorldLoader` mapper config.

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

The engine emits the hashed path (`voices/<zone>/<templateKey>/<nodeId>.<sha8>.mp3`) — see
"Hash spec" above. Arcanum must upload to the same hashed key.

## Engine change surface

Small — a state addition and a signature tweak, no new systems:

1. **Store `templateKey` + `zone`** on the dialogue conversation state at `startConversation`
   (the mob object is in hand there; `DialogueState` currently keeps only `mobId`/`mobName`).
2. **Thread `nodeId`** into `renderNode` (both call sites: `startConversation` has the
   `rootNodeId`, `selectChoice` has the resolved `nextNodeId`).
3. **Resolve `voiceUrl`** (`voicesBase` + path template) and pass it to `sendDialogueNode`.

## Web client change surface

All implemented in this PR:

1. `voiceUrl` parsed in the `Dialogue.Node` case of `applyGmcpPackage.ts` and carried on
   `DialogueState`.
2. `useAudioEngine` plays the clip as a one-shot (its own gain bus) via `playVoice`; `App.tsx`
   drives it off `state.dialogue?.voiceUrl`, mirroring the room music/ambient effects.
3. On `Dialogue.End` (dialogue cleared), in-flight voice playback is stopped.
4. Independent **Voice** volume slider in `AudioControls` (alongside Music and Ambient), backed
   by a persisted `voiceVolume` pref. The master audio toggle still mutes everything; the Voice
   slider at 0 mutes voice only and skips the fetch. Default voice volume `0.7`.

## Summary

| Concern | Decision |
|---|---|
| GMCP field | `voiceUrl: String?` on `DialogueNodePayload`, fully-resolved URL |
| R2 path | `voices/<zone>/<templateKey>/<nodeId>.<sha8>.mp3` |
| Hash | SHA-256 of raw UTF-8 node text, first 8 lowercase-hex chars |
| Audio format | MP3 (ElevenLabs default, Web Audio-native) |
| Engine config | `ambonmud.voices.enabled` (opt-in) + `ambonmud.voices.baseUrl`, local `/voices/` fallback |
| Voiced content | NPC node text only; choices stay text |
| Identity sent to client | Resolved URL only — never `voiceId` / `templateKey` |
| Transport scope | Web client only; telnet unaffected |
