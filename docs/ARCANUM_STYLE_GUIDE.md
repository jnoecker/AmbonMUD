# AmbonMUD Design System: Ambon Arcanum Style

**Version:** arcanum_v1
**Last Updated:** March 7, 2026
**Scope:** Unified aesthetic for the Ambon Arcanum creator tool — server management, world building, zone editing, and configuration
**Product:** Ambon Arcanum (standalone creator tool, separate from the AmbonMUD game client)

---

## Table of Contents

1. [Core Philosophy](#core-philosophy)
2. [Visual Language](#visual-language)
3. [Color System](#color-system)
4. [Typography](#typography)
5. [Motion & Animation](#motion--animation)
6. [Component Design Language](#component-design-language)
7. [World Art Prompts](#world-art-prompts)
8. [Validation Checklist](#validation-checklist)
9. [Versioning Strategy](#versioning-strategy)

---

## Core Philosophy

The Arcanum is the Creator's own instrument — the same cosmic machinery used to bring Ambon into existence before the Creator departed. The person operating the Arcanum is not a player inside the world. They are above it, looking down with the same perspective the Creator once held. Every design decision should reinforce that position: vast in scale, precise in intent, ornate because creation is abundant, never sparse.

This style is:

- **Cosmological, not intimate** — Scale is always implied. The user is working at the level of worlds.
- **Baroque in detail, cosmic in composition** — Ornamentation is present and deliberate, but it serves the grandeur rather than decorating for decoration's sake.
- **Gold against the void** — Warm aurum light is the primary creative accent. It appears only where something is alive, active, or important. Against deep cosmic indigo, a single gold highlight carries enormous weight.
- **Unhurried** — The cosmos does not rush. Animations are slow, deliberate, and inevitable. Nothing snaps; everything unfurls.

**Key Principle:** This is not a modern SaaS dashboard dressed up with a dark theme. It is a window into the architecture of a world. Every panel, button, and indicator should feel like it was carved into existence by the same force that shaped the land.

---

## Visual Language

### Shape Vocabulary

**Baroque/Rococo Principles Applied to UI:**

The baroque tradition is defined by movement, abundance, and the refusal to let any surface rest passively. Applied cosmologically, this means:

- **C-curves and S-curves** — The primary unit of ornamentation. Borders, dividers, and decorative elements terminate in curls rather than hard stops.
- **Acanthus light scrollwork** — Panel borders and section dividers may incorporate subtle curling flourishes rendered as faint gold or violet lines, as if traced by the Creator's hand.
- **Spiral arms** — Active or highlighted elements trail gentle spiraling light threads, tightening toward a center as they dissipate.
- **Gradual dissolution** — Nothing abruptly ends. Decorative elements fade to transparency at their extremities. Hard termination is forbidden.

**Preferred:**
- Rounded terminals on all lines and dividers (ends curl or dissolve)
- Panel corners: large radius with optional subtle scrollwork overlay
- Connectors in tree views: gentle arcs, never right-angle bends
- Active/focus states expressed as an encircling glow, not a hard border change

**Forbidden:**
- Flat corporate geometry (perfect rectangles without treatment)
- Right-angle tree/graph connectors
- Neon or saturated primary colors
- Anything that reads as a modern SaaS product (no sharp shadows, no heavy card grids, no flat color fills)
- Pure black backgrounds — use the deep indigo palette

### Light Behavior

This is deep-space luminosity, not ambient diffusion.

**The two light modes:**

1. **Concentrated Aurum** — Warm gold-amber light emanating from active, important, or alive elements. It has a clear source. It pools and fades outward. The server is running; this is what that looks like.

2. **Nebula-violet ambient fill** — A cool blue-violet glow that fills the darkness without illuminating it. It gives the deep background depth and dimension without competing with the Aurum.

**Rules:**
- Gold light only appears where something demands attention or represents the active creative force.
- Blue-violet light is the atmosphere, the medium through which the tool exists.
- Stars and pinpoint lights (tiny, pure white) live in background art, not in UI chrome.
- Glow should bloom softly — a 20-40px feathered spread on important elements.
- No hard drop shadows. All shadows use the deep indigo base color and spread widely.

---

## Color System

### Palette

#### Deep Space (Backgrounds)

| Name | Hex | Use |
|------|-----|-----|
| Abyssal Navy | `#080c1c` | Deepest background, outermost shell |
| Cosmic Indigo | `#0f1428` | Primary panel base |
| Deep Nebula | `#161b38` | Elevated panel surfaces |
| Celestial Slate | `#1e2748` | Highest surfaces, focused panels |
| Void Edge | `#2a3460` | Borders, dividers, structural lines |

#### Cool Accents (Structure & Information)

| Name | Hex | Use |
|------|-----|-----|
| Nebula Violet | `#7a5fc0` | Secondary accent, tree connectors, structural chrome |
| Stellar Blue | `#4e7fd4` | Info states, links, selected items, cool highlights |
| Arcane Teal | `#3aa8b8` | Data/schema elements, optional third accent |

#### Warm Accents (The Creative Force)

| Name | Hex | Use |
|------|-----|-----|
| Aurum | `#c8972e` | Primary creative accent — active states, primary buttons, running server |
| Pale Aurum | `#e2bc6a` | Gold highlights, glow centers, hover states |
| Amber Thread | `#a07820` | Pressed/active button fill, gold border variant |

#### Text & Neutrals

| Name | Hex | Use |
|------|-----|-----|
| Stardust | `#c2cef0` | Primary text |
| Cosmic Haze | `#6a7aac` | Secondary text, captions, disabled labels |
| Faint Starlight | `#3a4880` | Placeholder text, very subtle separators |

#### Semantic States

| State | Color | Hex | Notes |
|-------|-------|-----|-------|
| Server Running | Aurum | `#c8972e` | Pulsing gold, rotation in background |
| Server Stopped | Cosmic Haze | `#6a7aac` | Still, cool, no animation |
| Server Error | Void Crimson | `#8a2a3c` | Deep desaturated red — the void has been disturbed |
| Success | Nebula Green | `#3a8a6a` | Muted teal-green |
| Warning | Pale Aurum | `#e2bc6a` | Gold-adjacent, urgent but not alarming |
| Info | Stellar Blue | `#4e7fd4` | Informational, structural |
| Destructive | Void Crimson | `#8a2a3c` | Delete, force-stop, irreversible |

### Design Tokens (CSS)

```css
/* Backgrounds */
--bg-abyss:         #080c1c;
--bg-primary:       #0f1428;
--bg-elevated:      #161b38;
--bg-surface:       #1e2748;

/* Accents */
--accent-aurum:     #c8972e;
--accent-aurum-pale:#e2bc6a;
--accent-aurum-deep:#a07820;
--accent-violet:    #7a5fc0;
--accent-blue:      #4e7fd4;
--accent-teal:      #3aa8b8;

/* Borders */
--border-void:      #2a3460;
--border-aurum:     rgba(200, 151, 46, 0.4);
--border-violet:    rgba(122, 95, 192, 0.35);

/* Text */
--text-primary:     #c2cef0;
--text-secondary:   #6a7aac;
--text-disabled:    #3a4880;

/* Glows */
--glow-aurum:       0 0 32px rgba(200, 151, 46, 0.35);
--glow-aurum-strong:0 0 48px rgba(226, 188, 106, 0.5);
--glow-violet:      0 0 28px rgba(122, 95, 192, 0.3);
--glow-blue:        0 0 24px rgba(78, 127, 212, 0.25);

/* Shadows */
--shadow-deep:      0 8px 32px rgba(4, 6, 18, 0.7);
--shadow-panel:     0 16px 56px rgba(4, 6, 18, 0.8);

/* Semantic */
--state-running:    #c8972e;
--state-stopped:    #6a7aac;
--state-error:      #8a2a3c;
--state-success:    #3a8a6a;
--state-warning:    #e2bc6a;
```

### Opacity Guidelines

- **100%** — Primary text, primary UI elements, server status indicators
- **80%** — Overlay backgrounds, hover state fills
- **50%** — Secondary text, inactive tabs
- **35%** — Glow borders, baroque scrollwork overlays, structural decorative lines
- **15%** — Barely-visible background texture, depth separation

---

## Typography

The typeface must evoke age, authority, and the weight of cosmological inscription — while remaining legible in a dense creator tool.

### Typefaces

| Context | Font | Weights | Size | Use |
|---------|------|---------|------|-----|
| Display / Titles | `Cinzel` | 400, 600, 700 | 20–36px | App title, section headings, zone names, major labels |
| UI Body | `Crimson Pro` | 400, 500, 600 | 14–16px | Panel text, descriptions, config labels, list items |
| UI Small | `Crimson Pro` | 400 | 12–13px | Captions, metadata, tree node labels |
| Code / YAML / Config | `JetBrains Mono` | 400, 500 | 13–15px | All config editing, YAML, command output, server logs |
| Fallbacks | `Palatino`, `serif` (titles); `Georgia`, `serif` (body); `Consolas`, `monospace` (code) | — | — | System fallbacks |

**Notes:**
- `Cinzel` is derived from Roman inscriptional lettering — it carries the weight of ancient authority without feeling decorative.
- `Crimson Pro` has an old-book warmth that complements Cinzel without competing. It is readable at small sizes.
- Never use a sans-serif for display or body text in this style. Sans-serif feels modern and corporate. This is neither.

### Typography Hierarchy

**App Title / Zone Name**
- Font: `Cinzel`
- Size: 28–36px
- Weight: 600
- Color: Pale Aurum (`#e2bc6a`)
- Letter spacing: 2px
- Usage: top-level titles only

**Section Heading**
- Font: `Cinzel`
- Size: 18–22px
- Weight: 400
- Color: Stardust (`#c2cef0`)
- Letter spacing: 1px

**Panel Heading**
- Font: `Cinzel`
- Size: 14–16px
- Weight: 400
- Color: Cosmic Haze (`#6a7aac`)
- Letter spacing: 0.5px
- Uppercase: yes

**Body Text**
- Font: `Crimson Pro`
- Size: 15px
- Weight: 400
- Color: Stardust (`#c2cef0`)
- Line height: 1.6

**Caption / Metadata**
- Font: `Crimson Pro`
- Size: 12–13px
- Weight: 400
- Color: Cosmic Haze (`#6a7aac`)
- Line height: 1.4

**Code / Config**
- Font: `JetBrains Mono`
- Size: 13px
- Weight: 400
- Color: Stardust (`#c2cef0`)
- Line height: 1.7

### Special Treatments

- **Emphasis:** Italic Crimson Pro, no bold for body
- **Key values / entity names:** Pale Aurum, normal weight
- **Error messages:** Void Crimson, italic
- **Links:** Stellar Blue, no underline by default, Pale Aurum on hover
- **Section dividers:** A thin centered horizontal rule with a small baroque diamond or circle glyph at the midpoint, rendered in Void Edge color (`#2a3460`), 35% opacity

---

## Motion & Animation

### Principles

Motion in the Arcanum is the motion of the cosmos: inevitable, unhurried, operating at scales that make haste irrelevant. Nothing snaps. Nothing bounces. Things come into being as if they were always going to be there.

- **Slow and gravitational** — Background elements rotate on cycles of 30–90 seconds. The user almost cannot perceive the motion; they feel it rather than see it.
- **Weaving and threading** — Light and energy move in flowing curved paths, tracing the baroque scrollwork forms. They do not travel in straight lines.
- **Unfurling** — Panels and elements enter by opening outward from a center point, or by fading in while gently expanding. They do not slide from edges.
- **No sudden starts** — All animations use easing that begins gently. Even fast interactions (hover: 200ms) feel considered.

### Easing Functions

```css
/* Standard entry — element comes into being */
--ease-unfurl:    cubic-bezier(0.16, 1, 0.3, 1);

/* Standard exit — element dissolves */
--ease-dissolve:  cubic-bezier(0.7, 0, 0.84, 0);

/* Smooth bidirectional — hover states, continuous transitions */
--ease-cosmic:    cubic-bezier(0.4, 0, 0.2, 1);

/* Cosmic rotation — ambient background elements */
/* Use: animation-timing-function: linear (constant angular velocity) */
```

### Duration Guidelines

| Category | Duration | Easing | Notes |
|----------|----------|--------|-------|
| Hover state | 180ms | `ease-cosmic` | Color and glow shift |
| Focus ring appear | 200ms | `ease-unfurl` | Gold ring unfurls around element |
| Button press | 120ms | `ease-cosmic` | Scale 0.97, darken |
| Panel open | 400ms | `ease-unfurl` | Fade + scale from 0.96 |
| Panel close | 280ms | `ease-dissolve` | Fade + scale to 0.96 |
| Modal appear | 500ms | `ease-unfurl` | Backdrop fade + content unfurl |
| Tree node expand | 300ms | `ease-unfurl` | Height expand + child fade in |
| Server status change | 800ms | `ease-cosmic` | Smooth state transition with glow shift |
| Tooltip | 200ms in, 120ms out | `ease-unfurl`/`ease-dissolve` | |
| Toast notification | 400ms in, 300ms out | `ease-unfurl`/`ease-dissolve` | |
| Gold pulse (running) | 3s loop | `ease-cosmic` | Breathing aurum glow |
| Background rotation | 60s loop | `linear` | Full 360° cosmic backdrop |
| Light thread drift | 12–20s loop | `ease-cosmic` | Slow baroque swirl in bg layers |

### Animation Categories

#### 1. Ambient (Always On — Cosmic Backdrop)

The background of the Arcanum is never fully still. At the lowest z-index, slow-moving elements imply the cosmos continuing its work:

- A very faint, extremely slow (60–90s) rotation of a large cosmic spiral or mandala shape — barely visible, 8–12% opacity.
- Subtle blue-violet nebula wisps drifting across deep backgrounds on 15–20s cycles.
- For the running server state: a slow gold pulse (3s breathe cycle) on the status indicator, and the background rotation gains a faint warm tint.

**Intensity:** Never distracting. If the user notices the background moving, it is too fast or too bright.

#### 2. Interaction Animations (User-Triggered)

- **Button hover:** Gold glow blooms (var(--glow-aurum)), text shifts to Pale Aurum, 180ms.
- **Button press:** Scale to 0.97, glow briefly intensifies, 120ms.
- **Panel focus:** A baroque-curved focus ring in Aurum unfurls around the focused element, 200ms.
- **Tree node expand:** Children unfurl downward with a 300ms height animation.
- **Tab switch:** Active tab gets Aurum underline, content cross-fades, 250ms.

#### 3. System State Animations (Server Lifecycle)

- **Running state entered:** Status indicator transitions from cool-blue to warm gold over 800ms. Background rotation becomes slightly visible. Aurum pulse begins.
- **Server stopped:** Gold fades, everything cools to Cosmic Haze. Background motion slows and fades. The interface becomes still.
- **Error state:** A slow, low-frequency crimson pulse (4s cycle) replaces the gold. The background rotation stops entirely. The stillness communicates wrongness.
- **Build/reload in progress:** A threading animation — a thin gold line traces a flowing path along the border of the active panel, completing its circuit every 2s.

#### 4. Data Change Animations

- **YAML saved / written to disk:** A brief gold ripple emanates from the save button, fading outward across the panel, 600ms.
- **Validation error:** Affected field border shifts to Void Crimson with a single 300ms pulse.
- **Zone reload:** Active zone panel's content cross-fades with a 500ms dissolve.

---

## Component Design Language

### Panels and Surfaces

**Primary panel:**
- Background: Deep Nebula (`#161b38`)
- Border: 1px solid Void Edge (`#2a3460`), with optional baroque corner accent at 35% opacity
- Border radius: 14–18px
- Box shadow: `var(--shadow-panel)`
- Optional: a very faint inner blue-violet glow (`var(--glow-violet)`) at 40% intensity on active/focused panels

**Elevated panel (modal, popover, flyout):**
- Background: Celestial Slate (`#1e2748`)
- Border: 1px solid, 50% Nebula Violet
- Box shadow: `var(--shadow-panel)`, `var(--glow-violet)` combined
- Backdrop blur: 12px (if supported) over underlying content

**Panel header:**
- Cinzel, 14px, uppercase, letter-spacing 0.5px, Cosmic Haze color
- Bottom border: 1px Void Edge, with a centered subtle flourish (thin horizontal rule with a small circle or diamond glyph at center)

### Buttons

**Primary (creative action — create zone, add mob, apply config):**
- Background: linear-gradient from Amber Thread (`#a07820`) to Aurum (`#c8972e`)
- Text: Pale Aurum (`#e2bc6a`), Cinzel, 13px, letter-spacing 0.5px
- Border: 1px solid Pale Aurum at 50% opacity
- Box shadow: `var(--glow-aurum)`
- Border radius: 8px
- Hover: gradient brightens toward Pale Aurum, glow intensifies, 180ms
- Press: scale 0.97, gradient darkens

**Secondary (structural actions — open editor, view log, navigate):**
- Background: Deep Nebula (`#161b38`)
- Text: Stardust (`#c2cef0`), Crimson Pro, 14px
- Border: 1px solid Nebula Violet at 50% opacity
- Box shadow: `var(--glow-violet)` at 50% intensity
- Hover: border brightens, subtle violet glow increase, text shifts to white

**Destructive (delete zone, force-kill server, irreversible ops):**
- Background: `#1a0d12` (very dark crimson-void)
- Text: `#e8a0aa` (desaturated rose-red)
- Border: 1px solid Void Crimson (`#8a2a3c`) at 60% opacity
- Hover: border and text brighten toward pure Void Crimson, slow pulse
- Never uses a gold accent

**Ghost / subtle (secondary navigation, inline actions):**
- Background: transparent
- Text: Cosmic Haze (`#6a7aac`)
- Border: none
- Hover: text shifts to Stardust, very faint aurum underline appears

### Input Fields

**Default:**
- Background: Cosmic Indigo (`#0f1428`)
- Border: 1px solid Void Edge (`#2a3460`)
- Text: Stardust (`#c2cef0`), Crimson Pro, 14px
- Placeholder: Faint Starlight (`#3a4880`)
- Border radius: 8px

**Focus:**
- Border: 1px solid Aurum at 60% opacity
- Box shadow: `0 0 0 2px rgba(200, 151, 46, 0.18)` (soft gold ring)
- Transition: 200ms `ease-unfurl`

**Error:**
- Border: 1px solid Void Crimson
- Box shadow: `0 0 0 2px rgba(138, 42, 60, 0.2)`

**YAML / Code Editor:**
- Background: Abyssal Navy (`#080c1c`)
- Font: JetBrains Mono, 13px
- Line numbers: Faint Starlight color
- Syntax highlighting palette (token colors):
  - Keys: Pale Aurum (`#e2bc6a`)
  - String values: Stardust (`#c2cef0`)
  - Numeric values: Stellar Blue (`#4e7fd4`)
  - Booleans: Nebula Violet (`#7a5fc0`)
  - Comments: Cosmic Haze (`#6a7aac`), italic
  - Errors/markers: Void Crimson (`#8a2a3c`)

### Tree Views (Zone Hierarchy, Config Tree)

- Connector lines: gentle arcs (SVG curves, not right-angle bends), Void Edge color, 50% opacity
- Expand/collapse icon: a small baroque-inspired chevron or spiral glyph in Cosmic Haze
- Node text: Crimson Pro, 14px, Stardust
- Selected node: Pale Aurum text, Aurum left-border accent (3px), very faint aurum background tint
- Hover: Stardust text, subtle background highlight
- Expand animation: 300ms `ease-unfurl` height reveal

### Data Tables (Mob Lists, Item Lists, Ability Tables)

- Header row: Cinzel, 12px, uppercase, letter-spacing 0.5px, Cosmic Haze
- Header bottom border: 1px Void Edge + a faint baroque line decoration at center
- Row background: alternating Cosmic Indigo / Deep Nebula (very subtle, 3% brightness delta)
- Row hover: Celestial Slate background, left border 2px Aurum
- Selected row: Deep Nebula background, left border 3px Aurum, Pale Aurum text
- Row dividers: 1px Void Edge at 40% opacity

### Server Status Indicator

The server status indicator is the most emotionally expressive component in the tool. It communicates the state of the world the Creator has built.

**Running:**
- Large gold orb or sigil with a breathing pulse animation (3s cycle, opacity 70%→100%)
- Text: "RUNNING", Cinzel, Pale Aurum
- Background area: faint warm tint, cosmic rotation visible in backdrop
- Subtle gold threading animation around the panel border (2s circuit)

**Stopped:**
- Cool blue-grey orb, no animation, still
- Text: "STOPPED", Cinzel, Cosmic Haze
- Background area: fully still, no ambient motion
- The stillness is the message

**Error:**
- Deep crimson orb with a slow, low-frequency pulse (4s, menacing rather than urgent)
- Text: "ERROR", Cinzel, Void Crimson
- Background area: still (motion has stopped), very faint crimson tint
- Error message below in Crimson Pro, italic, desaturated rose text

**Starting / Reloading:**
- Gold threading animation traces the panel border on loop (2s circuit)
- Orb fades between stopped-cool and running-warm
- Text: "STARTING..." or "RELOADING...", Cinzel, Pale Aurum at 70% opacity

### Tabs

- Inactive tab: Crimson Pro, 14px, Cosmic Haze, no border
- Active tab: Crimson Pro, 14px, Stardust, Aurum underline (2px, full tab width)
- Hover: Stardust text, Void Edge underline
- Transition: 180ms `ease-cosmic`

### Toggles / Checkboxes

- Track (off): Void Edge background, 1px Cosmic Haze border, border radius 999px
- Track (on): Aurum gradient, 1px Pale Aurum border, soft gold glow
- Thumb: Stardust circle, `var(--shadow-deep)` drop shadow
- Transition: 220ms `ease-cosmic`

### Tooltips

- Background: Celestial Slate, 95% opacity, backdrop blur 8px
- Border: 1px Void Edge
- Text: Crimson Pro, 13px, Stardust
- Box shadow: `var(--shadow-deep)`
- Arrow: matches background
- Border radius: 8px

---

## World Art Prompts

These prompts target **Flux Dev** and **Flux Schnell** for still images. Video prompts target **LTX 2.0** and **Hunyuan Mini**.

### Prompt System Preamble (include in all prompts)

Add this to any Arcanum art prompt as a style anchor:

```
Ambon Arcanum style (arcanum_v1): deep cosmic indigo and abyssal navy backgrounds,
baroque rococo light scrollwork rendered as glowing energy threads, warm aurum-gold
as the primary accent color against cool blue-violet atmospheric fill, sweeping
spiral arms of light, fractaline structures, slow cosmological scale, no runes or
text, no humanoid figures, no neon colors, no harsh edges
```

---

### Still Image Prompts (Flux Dev / Flux Schnell)

#### Main Background — The Creator's Observatory

Use as the primary backdrop for the Arcanum tool's main screen or loading screen.

```
Prompt:
Vast cosmic observatory floating in deep space, baroque architectural elements
rendered in flowing light — sweeping rococo scrollwork of glowing blue-violet and
gold energy forming grand archways and spiral colonnades, a colossal golden spiral
galaxy visible through open arched windows, deep indigo and abyssal navy void
beyond, fractaline structures branching into the distance like crystalline trees
made of light, warm aurum-amber luminescence pooling at architectural nodes, cool
nebula-violet atmospheric mist drifting between pillars, ultra-wide panoramic
composition, painterly oil technique, extremely detailed

Negative:
humanoid figures, faces, text, runes, glyphs, logos, neon colors, bright white
backgrounds, modern technology, computers, flat design, cartoon style, anime,
hard shadows, sharp edges, photorealism
```

#### Zone Map Background — The Cartography of Creation

For use behind world map and zone layout visualizations.

```
Prompt:
Celestial cartography from above, a glowing world map rendered in baroque light
threads on a deep cosmic indigo void, landmasses formed from swirling aurum-gold
energy lines that curl and flourish at coastlines and mountain ranges in rococo
scrollwork style, rivers traced as flowing silver-blue light, zone boundaries
marked by gentle violet-glowing arcs, concentric circles of faint stardust
suggesting scale and depth, fractal detail increasing toward the edges, bird's-eye
perspective, painterly, luminous

Negative:
modern map symbols, text labels, legends, compass roses, grid lines, flat colors,
cartoon style, humanoid figures, photorealism
```

#### Panel Ornament — Baroque Energy Border

For use as decorative border elements, panel header art, or section dividers.

```
Prompt:
Intricate baroque energy scrollwork border, symmetrical horizontal composition,
glowing aurum-gold rococo flourishes and acanthus-leaf spirals rendered as threads
of light against deep cosmic indigo, cool blue-violet glow fills the spaces between
curls, the scrollwork dissolves to transparency at both horizontal ends, extremely
detailed filigree of light, jewelry-like precision, wide aspect ratio banner format

Negative:
text, runes, faces, animals, modern design, flat colors, harsh edges, neon
```

#### Empty State — The Unwritten World

For use when no project or zone is loaded.

```
Prompt:
An empty cosmic void beginning to stir — deep abyssal navy space with the faint
suggestion of energy not yet shaped into form, a single point of warm aurum-gold
light at the center casting the first illumination into darkness, baroque energy
tendrils beginning to curl outward from that center point as if the act of creation
is just beginning, blue-violet nebula mist drifting at the periphery, vast and
serene, the moment before the world exists, painterly, luminous

Negative:
humanoid figures, text, runes, modern elements, harsh light, neon, flat design
```

#### Server Status — The Engine Running

For use as artwork in the server control panel when the server is running.

```
Prompt:
A cosmic engine in full operation — a grand mechanical orrery made entirely of
light, baroque golden rings and spiral armatures rotating slowly in deep indigo
space, warm aurum energy flowing along the curved spokes like luminous oil, smaller
fractal orreries visible in the distance like satellites, blue-violet atmospheric
fill between components, a sense of vast power operating at perfect equilibrium,
painterly, glowing, majestic

Negative:
text, runes, humanoid figures, modern machinery, computer hardware, neon, flat
design, photorealism, harsh shadows
```

#### Server Status — The Engine Stopped

For use when the server is stopped or not yet started.

```
Prompt:
A dormant cosmic mechanism in deep stillness — baroque golden rings and spiral
armatures at rest, faintly visible in cool blue-grey light, deep abyssal navy
void surrounds everything, a single cool blue-violet ember at the center barely
glowing, the scrollwork and ornamental curves still beautiful but inert, a sense
of vast potential waiting to be awakened, muted palette, painterly, quiet,
mysterious

Negative:
warm colors, gold glow, motion blur, text, runes, humanoid figures, neon,
harsh light, photorealism
```

---

### Video / Motion Prompts (LTX 2.0 / Hunyuan Mini)

These prompts describe looping ambient animations for backgrounds and loading screens.

#### Ambient Background Loop — Cosmic Rotation

Use as a looping background for the main Arcanum screen.

```
Prompt:
Slow rotation of a vast baroque cosmic structure in deep indigo space — ornate
rococo scrollwork of blue-violet and aurum-gold light turning at a stately,
gravitational pace, spiral arms curling outward then dissolving at their tips,
the entire composition completing one rotation every thirty seconds, cool nebula
mist drifting across the foreground in gentle cross-currents, tiny stars fixed
in the background, warm gold brightens and dims in a slow breathing rhythm,
no cuts, seamless loop, painterly

Negative:
fast motion, sudden cuts, camera movement, text, humanoid figures, neon colors,
modern technology
```

#### Loading Screen Loop — Creation Begins

For use on initial load / project open.

```
Prompt:
A single point of aurum-gold light at the center of deep cosmic void beginning
to expand outward — baroque energy threads curl and spiral away from the center
like a flower blooming in slow motion, each thread tracing rococo scrollwork
curves as it extends into the dark, blue-violet atmospheric mist parts where
the gold passes through it, the entire sequence takes eight seconds and then
gently reverses, soft and inevitable, no sharp edges, no sudden motion, seamless

Negative:
fast motion, explosions, flash effects, text, humanoid figures, neon, modern
design, photorealism
```

#### Server Running Loop — The Engine Breathes

A subtle looping animation for the server status area when the server is running.

```
Prompt:
Close view of a slowly rotating baroque golden orrery made of light —
warm aurum rings turning at different rates in layered depth, energy flowing
along the curved armatures in smooth continuous streams, blue-violet glow
fills the space between rings, the whole assembly breathing gently with a
three-second luminosity pulse, seamless loop, depth of field soft on distant
elements, stately and powerful

Negative:
fast motion, flickering, text, humanoid figures, neon, harsh light, modern
machinery, photorealism
```

---

### Negative Prompt (Universal — Add to All Generations)

```
text, words, letters, runes, glyphs, watermarks, logos, signatures, humanoid
figures, faces, bodies, animals, modern technology, computers, user interfaces,
neon colors, hot pink, electric blue, lime green, harsh shadows, hard edges,
flat design, cartoon, anime, photorealism, studio lighting, stock photo aesthetic,
horror elements, gore
```

---

## Validation Checklist

When reviewing any component, screen, or artwork against this style:

**Scale & Weight**
- [ ] Does this feel like it operates at cosmological scale?
- [ ] Is the gold accent used sparingly — only where something is alive or important?
- [ ] Does the deep indigo background give a sense of infinite depth?

**Ornamentation**
- [ ] Are curves baroque — do they terminate in scrollwork, dissolve, or curl?
- [ ] Are there any abrupt hard edges where there should be a flourish or fade?
- [ ] Is decorative detail present but not cluttered?

**Light Behavior**
- [ ] Does gold light have a clear source and bloom softly?
- [ ] Is the blue-violet ambient fill present without competing with the gold?
- [ ] Are glows feathered (no hard-edged halos)?

**Motion**
- [ ] Is all animation unhurried — does nothing snap or bounce?
- [ ] Does the server's running/stopped state feel emotionally distinct?
- [ ] Would the background motion be invisible if the user stopped looking for it?

**Typography**
- [ ] Does all display text use Cinzel?
- [ ] Is Crimson Pro used for body/UI — no sans-serif in those roles?
- [ ] Is code/YAML always in JetBrains Mono?

**Emotional Check**
- [ ] Does this feel like the Creator's instrument?
- [ ] Does the gold feel earned?
- [ ] Is this unmistakably different from a modern SaaS dashboard?
- [ ] Would someone opening this tool for the first time feel the weight of what they're about to do?

**If it feels like a modern productivity tool — it has drifted. Revise.**

---

## Versioning Strategy

- `arcanum_v1` — **Current** (deep cosmic, baroque gold, Cinzel/Crimson Pro)
- `arcanum_dawn_v1` — Lighter variant: rose-gold and pale violet at dawn, for onboarding/welcome screens
- `arcanum_void_v1` — Deeper, darker, more minimal: the Creator has been gone a long time
- `arcanum_active_v1` — Higher contrast, brighter gold: for power-user/high-density layouts

---

**Last Updated:** March 7, 2026
**Next Review:** June 1, 2026
