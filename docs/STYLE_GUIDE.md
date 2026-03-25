# AmbonMUD Design System: Surreal Gentle Magic

**Version:** surreal_softmagic_v1
**Last Updated:** March 25, 2026
**Scope:** Unified aesthetic for UI components, world rendering, and interactive experiences
**Implementation:** Dark-mode theme implemented in `web-v3/src/styles.css` (design tokens + component styles). PixiJS canvas scenes (world, battle, particles) are live. Light mode is planned.
**Design Context:** See [`.impeccable.md`](../.impeccable.md) for brand personality, user audiences, and high-level design principles.

---

## 📖 Table of Contents

1. [Core Philosophy](#core-philosophy)
2. [Visual Language](#visual-language)
3. [Color System](#color-system)
4. [Typography](#typography)
5. [Motion & Animation](#motion--animation)
6. [Interactive States](#interactive-states)
7. [Component Library Architecture](#component-library-architecture)
8. [Design Tokens](#design-tokens)
9. [Implementation Roadmap](#implementation-roadmap)
10. [Validation Checklist](#validation-checklist)

---

## 🌿 Core Philosophy

**Brand Personality:** Surreal. Magical. Adventure.

**Emotional goals:** Evoke the feeling of Stardew Valley or Disney Dreamlight Valley — cozy comfort with an ever-present magical undertone. Players should feel safe to explore, curious about what's around the next corner, and delighted by small details.

This style is:
- ✨ **Enchanted, not explosive** — Magic feels ambient and inevitable, never aggressive
- 🌫 **Dreamlike, not chaotic** — Softness enables focus and contemplation
- 🌸 **Softly luminous, never harsh** — Light is a character, not a weapon
- 🌙 **Otherworldly, but emotionally safe** — Players feel welcomed, not threatened

**Design Principles:**
1. **Cozy first, cool second.** Rounded corners, soft shadows, gentle gradients. If it feels harsh or intimidating, soften it.
2. **Magic in the details.** Subtle sparkles, gentle pulses, smooth transitions — ambient, not flashy.
3. **Readable above all.** WCAG AA contrast minimum for all text; AAA where practical.
4. **Layered depth, not clutter.** Glass-morphism with breathing room. Let the magical background show through.
5. **Accessible by nature.** Support `prefers-reduced-motion`, color-blind-safe palettes, keyboard navigation everywhere.

**Anti-references:** Hardcore dark MMO UIs (sharp edges, aggressive reds). Generic Bootstrap/Material admin dashboards. Anything cold, clinical, or intimidating.

**Key Principle:** Nothing feels industrial. Nothing feels sharp unless narratively intentional.

---

## 🎨 Visual Language

### Shapes

**Preferred:**
- Slight vertical elongation (trees, UI elements, characters)
- Gentle curves over hard angles
- Organic, lived-in quality
- Micro-warping allowed (nothing perfectly straight)

**Forbidden:**
- Harsh geometric symmetry
- Perfect 90° realism
- Brutalist silhouettes
- Mechanical rigidity

### Color Palette

#### Primary Tones
| Color | Hex | Use Case | Undertone |
|-------|-----|----------|-----------|
| Lavender | `#a897d2` | Accents, magic aura, highlights | Cool |
| Pale Blue | `#8caec9` | Borders, depth, info states | Cool |
| Dusty Rose | `#b88faa` | Accents, warmth, warning states | Warm |
| Moss Green | `#8da97b` | Navigation, success states | Neutral |
| Soft Gold | `#bea873` | Highlights, important elements, glow | Warm |

#### Neutrals (Dark Theme)
| Color | Hex | Use Case |
|-------|-----|----------|
| Deep Mist | `#22293c` | Darkest backgrounds, base color |
| Soft Fog | `#6f7da1` | Secondary text, subtle UI |
| Cloud | `#d8def1` | Light text, UI highlights |

#### Rules
- ❌ No neon
- ❌ No saturated primaries (RGB 255, 0, 0)
- ❌ No pure black (#000000) — use Deep Mist (`#22293c`) or a dark gradient instead
- ✅ Contrast should be moderate (WCAG AA minimum: 4.5:1 for `--text-primary` on panel surfaces)
- ✅ Cool undertones dominate, warm accents balance
- ✅ Theme is dark mode — light text on dark surfaces throughout

### Light Behavior

Light sources should feel:
- **Ambient** — No clear source point
- **Diffused** — Edges fade softly
- **Source-ambiguous** — Player unsure where glow originates

#### Treatments
- Ground-level glow (magical plants, glowing moss)
- Halos around magical beings and important UI elements
- Soft bloom around windows and light sources
- Light threads connecting magical objects
- Atmospheric diffusion creating depth

#### Forbidden
- ❌ Sharp rim lights
- ❌ Hard shadows (use soft shadows only)
- ❌ Spotlight effect
- ❌ High-contrast chiaroscuro

---

## 🌈 Color System

### Design Tokens

```css
/* Primary — dark-mode palette */
--color-primary-lavender: #a897d2;
--color-primary-pale-blue: #8caec9;
--color-primary-dusty-rose: #b88faa;
--color-primary-moss-green: #8da97b;
--color-primary-soft-gold: #bea873;

/* Neutrals */
--color-neutral-deep-mist: #22293c;
--color-neutral-soft-fog: #6f7da1;
--color-neutral-cloud: #d8def1;

/* Semantic */
--color-success: var(--color-primary-moss-green);
--color-warning: var(--color-primary-dusty-rose);
--color-error: #C5A8A8; /* desaturated red */
--color-info: var(--color-primary-pale-blue);

/* Backgrounds (dark) */
--bg-primary: #2a3149;
--bg-secondary: #262f47;
--bg-elevated: #313a56;

/* Surfaces (glassmorphism panels) */
--surface-panel-a: rgb(54 63 90 / 95%);
--surface-panel-b: rgb(43 53 79 / 92%);
--surface-subpanel: rgb(50 60 88 / 94%);
--surface-chip: rgb(62 74 108 / 90%);
--surface-input: rgb(45 56 84 / 96%);

/* Lines & edges */
--line-soft: rgb(151 166 204 / 36%);
--line-faint: rgb(140 154 193 / 24%);
--glass-edge: rgb(236 242 255 / 18%);

/* Text */
--text-primary: #dbe3f8;
--text-secondary: #aebada;
--text-disabled: #7f89a8;
```

### Opacity Guidelines

- **100%** — Primary text, primary UI elements
- **85%** — Overlay backgrounds, hover states
- **60%** — Secondary text, disabled elements
- **30%** — Subtle separation lines, light decorative elements
- **15%** — Barely visible texture or depth cues

---

## 🔤 Typography

### Font Strategy

The typeface should evoke:
- Elegance without formality
- Organic warmth
- Readability at small sizes (UI) and large sizes (world rendering)

#### Typefaces (loaded via Google Fonts)

| Context | Font | Weight | Size | Line Height | Use |
|---------|------|--------|------|-------------|-----|
| Display / Titles | `Cormorant Garamond` | 500–700 | clamp(1.8rem, 2.45vw, 2.45rem) | 1.2 | App banner, section headings |
| UI Body | `Nunito Sans` | 400–800 | 14px | 1.5 | Buttons, descriptions, labels, chat |
| UI Small | `Nunito Sans` | 400 | 12px | 1.4 | Metadata, captions |
| Terminal / Code | `JetBrains Mono` | 400–600 | 16–18px | 1.6 | MUD text output, combat log |
| Fallbacks | `Georgia` (titles), `Segoe UI` / `sans-serif` (UI) | — | — | — | System fallbacks when Google Fonts unavailable |

### Hierarchy

**Display (Large Titles)**
- Size: 32px
- Weight: 600
- Color: `--color-neutral-deep-mist`
- Letter spacing: 0.5px
- All caps: optional, rarely

**Heading (Section)**
- Size: 24px
- Weight: 600
- Color: `--color-neutral-deep-mist`

**Subheading**
- Size: 18px
- Weight: 500
- Color: `--color-neutral-deep-mist`

**Body**
- Size: 14px
- Weight: 400
- Color: `--color-neutral-deep-mist`
- Line height: 1.5

**Caption**
- Size: 12px
- Weight: 400
- Color: `--color-neutral-soft-fog`

### Special Treatments

- **Emphasis:** Italics (never bold body text for emphasis)
- **Code/System:** `Courier New`, size 12px, background `--bg-secondary`
- **Quotes:** Italic, pale blue accent border left
- **Links:** `--color-primary-dusty-rose`, underline on hover

---

## ✨ Motion & Animation

### Principles

Motion should feel:
- **Gentle** — Curves over linear
- **Inevitable** — Not sudden
- **Breathing** — Rhythm suggests life
- **Responsive** — User action always gets immediate visual feedback

### Easing Functions

```css
/* Gentle entry */
--ease-in-soft: cubic-bezier(0.25, 0.46, 0.45, 0.94);

/* Gentle exit */
--ease-out-soft: cubic-bezier(0.33, 0.66, 0.66, 1);

/* Smooth return */
--ease-in-out-smooth: cubic-bezier(0.4, 0, 0.2, 1);

/* Bounce (for magic / spell cast) — planned, not yet defined in styles.css */
/* --ease-bounce-soft: cubic-bezier(0.34, 1.56, 0.64, 1); */
```

### Duration Guidelines

| Interaction | Duration | Easing | Purpose |
|-------------|----------|--------|---------|
| Hover state | 200ms | `ease-out-soft` | Button/UI element highlight |
| Menu slide | 300ms | `ease-out-soft` | Panel entrance |
| Fade transition | 300ms | `ease-in-out-smooth` | Screen transitions |
| Particle drift | 3–8s | `ease-out-soft` | Ambient magic motes |
| Pulse (magical glow) | 2–4s | `ease-in-out-smooth` | Breathing highlights |
| Text appear | 600ms | `ease-out-soft` | NPC dialogue, quest text |
| Spell cast | 1–2s | `ease-bounce-soft` | Ability activation visual |

### Animation Categories

#### 1. **Ambient Animations** (Always On)
- Floating motes in backgrounds
- Subtle plant sway
- Slow color pulse on magical elements
- Breathing glow on UI focus indicators

**Intensity:** 2–3% scale shift, ±10% opacity

#### 2. **Interaction Animations** (User-Triggered)
- Button hover: 10% scale growth, color shift to dusty rose
- Menu slide: Enter from edge, 300ms
- Checkbox toggle: 200ms smooth transition
- Slider handle: Follow cursor smoothly

#### 3. **Feedback Animations** (System Response)
- Success toast: Slide in, dwell 4s, fade out
- Error flash: Red tint overlay, 600ms pulse
- Level up sparkle: Particle burst upward, 1.5s
- Spell cast: Character aura intensifies, then fades

#### 4. **Transition Animations** (Page Changes)
- Fade in/out: 300ms
- Slide left/right: 400ms (for page nav)
- Dissolve: 500ms (for modal open)

---

## 🎯 Interactive States

### Button States

Primary button class: `.soft-button`.

#### Default
- Background: dark blue-violet gradient (blue-slate tones)
- Text: `--text-primary`
- Box shadow: `--shadow-sm`
- Border: 1px solid `--line-soft`

#### Hover
- Background: shift toward purple-rose (lavender/dusty-rose blend)
- Transform: `translateY(-1px)`
- Box shadow: `--shadow-md, --shadow-glow`
- Border color: lightened `--line-soft`
- Transition: 180ms `ease-out-soft`

#### Active (Pressed)
- Background: shift toward blue-slate
- Transform: `translateY(0) scale(0.98)`

#### Disabled
- Background: muted dark gradient
- Opacity: 62%
- Box shadow: none
- Cursor: not-allowed

#### Focus (Keyboard)
- Box shadow: `0 0 0 3px rgba(168, 151, 210, 0.4)` (lavender soft glow)
- No color change

### Input Fields

#### Default
- Background: `--surface-input`
- Border: 1px solid `--line-soft`
- Text: `--text-primary`
- Placeholder: `--text-disabled`

#### Focus
- Border: 1px solid `--color-primary-dusty-rose`
- Box shadow: `0 0 0 2px rgba(184, 143, 170, 0.2)`

#### Error
- Border: 1px solid `#C5A8A8`
- Box shadow: `0 0 0 2px rgba(197, 168, 168, 0.15)`
- Helper text: Color `#C5A8A8`

### Checkbox / Toggle

#### Default
- Background: `--bg-elevated`
- Border: 1px solid `--color-primary-pale-blue`
- Size: 18x18px
- Rounded: 4px

#### Checked
- Background: `--color-primary-moss-green`
- Checkmark: `--color-neutral-cloud` (white)
- Animation: 200ms `ease-out-soft` scale + fade

#### Hover (Unchecked)
- Background: lighten by 5%
- Border: `--color-primary-dusty-rose`

### Links

#### Default
- Color: `--color-primary-dusty-rose`
- Text decoration: none

#### Hover
- Color: Darken by 10%
- Text decoration: underline
- Animation: 150ms

#### Visited
- Color: `--color-primary-pale-blue`

#### Disabled
- Color: `--color-text-disabled`
- Cursor: not-allowed

---

## 🏗 Component Library Architecture

### Folder Structure

The client is a React + TypeScript + Vite application built with Bun. All design tokens and component styles live in a single CSS file. The PixiJS canvas provides the primary game view.

```
web-v3/
├── src/
│   ├── styles.css               # All design tokens + component styles (~5K lines)
│   ├── App.tsx                  # Composition root; owns all app state
│   ├── main.tsx                 # Vite entry point
│   ├── canvas/                  # PixiJS code
│   │   ├── GameStateBridge.ts   # Shared ref for React → PixiJS state
│   │   ├── CanvasEventBus.ts    # Push events (combat hits, gains)
│   │   ├── PixiCanvas.tsx       # React component wrapping PixiJS Application
│   │   ├── LoginModal.tsx       # Modal login form
│   │   ├── SceneManager.ts      # Scene state machine (world ↔ battle)
│   │   ├── scenes/
│   │   │   ├── WorldScene.ts    # Room view, player/NPC sprites, exits
│   │   │   └── BattleScene.ts   # JRPG battle view
│   │   └── systems/
│   │       ├── CombatAnimator.ts     # Combat event → sprite animations
│   │       ├── GainPopup.ts          # Floating XP/gold/level-up numbers
│   │       ├── StatusEffectDisplay.ts # Buff/debuff icons
│   │       ├── Minimap.ts            # Canvas-based minimap
│   │       ├── DialogueOverlay.ts    # NPC dialogue on canvas
│   │       └── EntityPopout.ts       # Click-to-interact on sprites
│   ├── components/
│   │   ├── panels/
│   │   │   ├── PlayPanel.tsx    # Canvas + input bar
│   │   │   ├── WorldPanel.tsx   # Room info, mobs, skills combat panel
│   │   │   ├── ChatPanel.tsx    # Social channels, who list
│   │   │   └── CharacterPanel.tsx # Stats, inventory, equipment
│   │   ├── PopoutLayer.tsx      # Floating overlay panels
│   │   ├── SpellbookPanel.tsx   # Ability grid with target type filtering
│   │   └── ...
│   ├── hooks/
│   │   ├── useMudSocket.ts      # WebSocket lifecycle
│   │   ├── useCommandHistory.ts # Command history & tab completion
│   │   ├── useMiniMap.ts        # Visited-room graph
│   │   └── useQuickbar.ts       # 9-slot quickbar (localStorage persisted)
│   └── gmcp/
│       └── applyGmcpPackage.ts  # GMCP package handler map
└── bun.lock                     # Dependency lockfile (Bun)
```

### Component Checklist

**Phase 1: Foundations (CSS Design System)** ✅
- [x] Design tokens CSS file
- [x] Reset/normalization
- [x] Typography hierarchy
- [x] Spacing scale
- [x] Animations & easing

**Phase 2: Core Components** ✅
- [x] Button (all states — `.soft-button`, `.chip-button`, `.panel-action-button`, `.action-bar-btn`)
- [x] Text input (command input, chat input, login form)
- [x] Select dropdown (race/class pickers in login)
- [x] Checkbox / toggle
- [x] Panel / card (`.panel` base class with header/body structure)

**Phase 3: Layout Components** ✅
- [x] Sidebar (WorldPanel, ChatPanel, CharacterPanel)
- [x] Modal (LoginModal, popout panels)
- [x] Tabs (chat channel tabs, character detail tabs, social tabs)
- [ ] Breadcrumb
- [x] Status bar (action bar with HP/MP/XP vitals)

**Phase 4: Indicators & Feedback** ✅
- [x] Badge (quest markers, shop icons on NPC sprites)
- [x] Progress bar (HP, Mana, XP, ability cooldowns)
- [x] Spinner/loader (reconnect pulse)
- [x] Toast notification (4s lifecycle animation)
- [ ] Tooltip

**Phase 5: Decorative & Canvas** ✅ (Largely complete)
- [x] Particle effect system (compass sparkles, gain popups)
- [x] Glow aura halos (ability sprites, compass markers)
- [ ] Light thread connections
- [x] Ambient animation layer (background orbs, rune pulses)
- [x] World renderer integration (PixiJS WorldScene + BattleScene)

---

## 🎨 Design Tokens

### Spacing Scale

```css
--space-1: 4px;   /* xs */
--space-2: 8px;   /* sm */
--space-3: 12px;
--space-4: 16px;  /* md */
--space-5: 24px;  /* lg */
--space-6: 32px;  /* xl */
```

### Border Radius

```css
--radius-md: 10px;    /* Standard buttons, inputs */
--radius-lg: 16px;    /* Cards, panels */
--radius-xl: 24px;    /* Large decorative, app banner */
/* Pills (connection pills, some buttons): border-radius: 999px (inline) */
```

### Shadows

```css
/* Drop shadows (dark theme — stronger than light-mode equivalents) */
--shadow-sm: 0 2px 7px rgb(8 10 18 / 30%);
--shadow-md: 0 10px 26px rgb(8 10 18 / 36%);
--shadow-lg: 0 16px 40px rgb(8 10 18 / 44%);

/* Magic glow (soft, diffused — blue-violet ambient) */
--shadow-glow: 0 0 22px rgb(103 116 170 / 28%);
```

### Z-Index Scale

```css
--z-hidden: -1;
--z-base: 0;
--z-dropdown: 10;
--z-sticky: 20;
--z-fixed: 30;
--z-modal-backdrop: 40;
--z-modal: 50;
--z-tooltip: 60;
--z-notification: 70;
```

---

## 🗺 Implementation Roadmap

### Phase 1: Design System Foundation ✅
- [x] Design tokens, spacing, shadows, easing — all in `web-v3/src/styles.css`
- [x] Easing functions and @keyframes in `styles.css`
- [x] Component folder structure under `web-v3/src/`
- [x] Documented in `STYLE_GUIDE.md` (this file) and `.impeccable.md`
- [ ] Visual regression test templates

### Phase 2: Admin Console Redesign
- [ ] Audit current admin UI (served by `AdminHttpServer.kt`)
- [ ] Redesign using design tokens
- [ ] Add hover/active/focus states
- [ ] Integrate ambient animations

### Phase 3: Web Client ✅
- [x] All React panels built (Play, World, Chat, Character, Admin)
- [x] Ambient orbs and background radial gradients
- [x] Dark-mode Surreal Gentle Magic theme throughout
- [x] Responsive layout (desktop + mobile)
- [x] Spellbook panel with ability sprites and target filtering
- [x] Customizable 9-slot quickbar with drag-and-drop

### Phase 4: PixiJS Canvas Game View ✅
- [x] WorldScene with room rendering, player/NPC sprites, exit indicators
- [x] BattleScene with JRPG-style combat animations
- [x] Combat event animation queue (damage numbers, spell effects)
- [x] Compass direction indicators with sparkle particles
- [x] Minimap with server-side coordinate system
- [x] Click-to-interact on sprites (EntityPopout)
- [x] Spell targeting system (auto-target, targeting mode, Escape cancel)
- [x] Login modal with race/class selection

### Phase 5: Light Mode & Polish
- [ ] Light mode theme (preserve magical warmth — don't just invert)
- [ ] Collect player feedback on aesthetic
- [ ] Refine easing/animation timings
- [ ] Create style variants (e.g., `surreal_softmagic_night_v1`)
- [ ] Performance optimization (lazy-loading, bundle splitting)

---

## ✅ Validation Checklist

### For Every Component

When reviewing a new component or render, verify:

**Visual Harmony**
- [ ] Uses only approved colors (no neon, no pure primaries)
- [ ] Soft shadows (not hard/sharp)
- [ ] Gentle curves where possible
- [ ] Consistent spacing from design tokens
- [ ] Typography follows hierarchy

**Light & Glow**
- [ ] Magical elements have ambient glow
- [ ] Light sources feel diffused (not spotlit)
- [ ] Bloom/halo around important UI (subtle, <5px)
- [ ] No harsh rim lights or chiaroscuro

**Motion**
- [ ] Animations use approved easing functions
- [ ] Duration matches category guidelines
- [ ] No jarring transitions
- [ ] Transitions feel inevitable, not sudden

**Emotional Check**
- [ ] Does this feel gentle?
- [ ] Does this feel slow / breathable?
- [ ] Does this feel enchanted but safe?
- [ ] Would a new player feel welcomed?

**If it feels intense, loud, or sharp — it's drifting. Revise.**

### For Rendered Assets (World, NPCs, Items)

Add to every generative prompt:

```
Rendered in the Surreal Gentle Magic style (surreal_softmagic_v1), featuring:
- Soft lavender and pale blue undertones
- Ambient diffused lighting (no harsh shadows, no spotlighting)
- Gentle atmospheric haze with floating motes of light
- Subtle magical glow integrated naturally into the environment
- Slightly elongated organic forms (trees, towers, figures)
- NO neon colors, NO high contrast, NO harsh edges
- Dreamy, breathable, emotionally safe aesthetic
```

---

## 🔮 Versioning Strategy

You may eventually evolve this. Treat like shader versioning:

- `surreal_softmagic_v1` — **Balanced** (current)
- `surreal_softmagic_v2` — Slightly brighter, more vibrant
- `surreal_softmagic_night_v1` — Moonlit variant (deeper blues, softer purples)
- `surreal_softmagic_feycourt_v1` — More regal (golds, jewel tones, sharper accents)
- `surreal_softmagic_ritual_v1` — Ritual/ceremony feel (more geometric, crystalline elements)

When creating new variants, document the differences from v1 and update this section.

---

## 📝 Notes for Designers & Developers

### For Designers
- Test all components at 100% zoom and 120% zoom
- Use a color picker to verify hex values match tokens in `web-v3/src/styles.css`
- The theme is dark mode — verify contrast ratios for light text on dark surfaces (WCAG AA: 4.5:1)
- Get feedback from accessibility checkers (WebAIM, WAVE)

### For Developers
- All design tokens live in `web-v3/src/styles.css` — edit that file for token changes; it is the single source of truth
- Never hardcode colors — always use CSS variables
- Use `calc()` for spacing combinations (e.g., `calc(var(--space-4) + var(--space-2))`)
- Animate only `transform` and `opacity` for performance
- Test on both desktop and mobile breakpoints

### For Contributors
- Always reference this guide when creating new components
- Submit visual regression tests for new elements
- Ask questions in pull request reviews if the style is unclear
- Update this guide if you discover ambiguities or edge cases

---

**Last Updated:** March 25, 2026
**Next Review:** June 30, 2026
