# AmbonMUD Design System: Surreal Gentle Magic

**Version:** surreal_softmagic_v1
**Last Updated:** February 26, 2026
**Scope:** Unified aesthetic for UI components, world rendering, and interactive experiences
**Implementation:** Hybrid (CSS design tokens + Canvas/SVG decorative elements)

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

This style is:
- ✨ **Enchanted, not explosive** — Magic feels ambient and inevitable, never aggressive
- 🌫 **Dreamlike, not chaotic** — Softness enables focus and contemplation
- 🌸 **Softly luminous, never harsh** — Light is a character, not a weapon
- 🌙 **Otherworldly, but emotionally safe** — Players feel welcomed, not threatened

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
| Lavender | `#D8C5E8` | Primary UI, backgrounds, magic aura | Cool |
| Pale Blue | `#B8D8E8` | Secondary UI, borders, depth | Cool |
| Dusty Rose | `#E8C5D8` | Accents, warmth, buttons (hover) | Warm |
| Moss Green | `#C5D8A8` | Navigation, success states, plants | Neutral |
| Soft Gold | `#E8D8A8` | Highlights, important elements, glow | Warm |

#### Neutrals (Reduced Saturation)
| Color | Hex | Use Case |
|-------|-----|----------|
| Deep Mist | `#6B6B7B` | Text, dark elements |
| Soft Fog | `#A8A8B8` | Disabled, secondary text |
| Cloud | `#E8E8F0` | Backgrounds, surfaces |

#### Rules
- ❌ No neon
- ❌ No saturated primaries (RGB 255, 0, 0)
- ❌ No black (#000000) — use Deep Mist instead
- ✅ Contrast should be moderate (WCAG AA minimum: 4.5:1)
- ✅ Cool undertones dominate, warm accents balance

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
/* Primary */
--color-primary-lavender: #D8C5E8;
--color-primary-pale-blue: #B8D8E8;
--color-primary-dusty-rose: #E8C5D8;
--color-primary-moss-green: #C5D8A8;
--color-primary-soft-gold: #E8D8A8;

/* Neutrals */
--color-neutral-deep-mist: #6B6B7B;
--color-neutral-soft-fog: #A8A8B8;
--color-neutral-cloud: #E8E8F0;

/* Semantic */
--color-success: var(--color-primary-moss-green);
--color-warning: var(--color-primary-dusty-rose);
--color-error: #C5A8A8; /* desaturated red */
--color-info: var(--color-primary-pale-blue);

/* Backgrounds */
--bg-primary: var(--color-neutral-cloud);
--bg-secondary: #F8F8FC;
--bg-elevated: #FFFFFF;
--bg-overlay: rgba(255, 255, 255, 0.85);

/* Text */
--text-primary: var(--color-neutral-deep-mist);
--text-secondary: var(--color-neutral-soft-fog);
--text-disabled: #C8C8D0;
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

#### Recommendations

| Context | Font | Weight | Size | Line Height | Use |
|---------|------|--------|------|-------------|-----|
| UI Headings | `Georgia` or `Crimson Text` | 600 | 20–32px | 1.2 | Titles, panel headers |
| UI Body | `Segoe UI` or `Inter` | 400 | 14px | 1.5 | Buttons, descriptions, chat |
| UI Small | `Segoe UI` or `Inter` | 400 | 12px | 1.4 | Labels, metadata |
| World Text | `Courier New` | 400 | 16–18px | 1.6 | Room descriptions, combat log |
| Emote/Special | `Georgia` | 400 | 14–16px | 1.5 | Player actions, NPC speech |

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

/* Bounce (for magic) */
--ease-bounce-soft: cubic-bezier(0.34, 1.56, 0.64, 1);

/* Smooth return */
--ease-in-out-smooth: cubic-bezier(0.4, 0, 0.2, 1);
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

#### Default
- Background: `--color-primary-lavender`
- Text: `--color-neutral-deep-mist`
- Box shadow: `0 2px 8px rgba(0, 0, 0, 0.08)`
- Border: 1px solid `--color-primary-pale-blue`

#### Hover
- Background: `--color-primary-dusty-rose`
- Text: `--color-neutral-deep-mist`
- Box shadow: `0 4px 12px rgba(216, 197, 232, 0.3)`
- Transition: 200ms `ease-out-soft`

#### Active (Pressed)
- Background: `--color-primary-pale-blue`
- Transform: `scale(0.98)`
- Transition: 100ms `ease-in-soft`

#### Disabled
- Background: `--color-neutral-cloud`
- Text: `--color-text-disabled`
- Opacity: 60%
- Cursor: not-allowed

#### Focus (Keyboard)
- Box shadow: `0 0 0 3px rgba(216, 197, 232, 0.5)` (soft glow)
- No color change

### Input Fields

#### Default
- Background: `--bg-elevated`
- Border: 1px solid `--color-primary-pale-blue`
- Text: `--color-neutral-deep-mist`
- Placeholder: `--color-text-disabled`

#### Focus
- Border: 1px solid `--color-primary-dusty-rose`
- Box shadow: `0 0 0 2px rgba(232, 197, 216, 0.2)`
- Background: Very subtle pale blue tint (1% opacity)

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

```
web-v3/src/
├── styles/
│   ├── design-tokens.css         # All CSS variables
│   ├── reset.css                 # Browser normalization
│   ├── typography.css            # Font hierarchy
│   ├── layout.css                # Grid, flex, spacing
│   ├── animations.css            # @keyframes, easing functions
│   └── theme.css                 # Light mode (dark mode future)
├── components/
│   ├── buttons/
│   │   ├── button.css
│   │   ├── Button.tsx             # React/Web component
│   │   └── button-stories.html   # Visual regression test
│   ├── inputs/
│   │   ├── text-input.css
│   │   ├── TextInput.tsx
│   │   └── text-input-stories.html
│   ├── layout/
│   │   ├── panel.css
│   │   ├── modal.css
│   │   ├── sidebar.css
│   │   └── ...
│   ├── indicators/
│   │   ├── badge.css
│   │   ├── progress-bar.css
│   │   ├── spinner.css
│   │   └── ...
│   └── decorative/
│       ├── particle-effect.js    # Canvas-based magic motes
│       ├── glow-aura.js          # SVG-based halos
│       ├── light-thread.js       # Connecting elements
│       └── ...
├── canvas/
│   ├── world-renderer.ts         # Main world canvas
│   ├── particle-system.js        # Ambient effects
│   ├── lighting.js               # Soft glow calculations
│   └── ...
└── main.tsx                        # Main entry point
```

### Component Checklist

**Phase 1: Foundations (CSS Design System)**
- [ ] Design tokens CSS file
- [ ] Reset/normalization
- [ ] Typography hierarchy
- [ ] Spacing scale
- [ ] Animations & easing

**Phase 2: Core Components**
- [ ] Button (all states)
- [ ] Text input
- [ ] Select dropdown
- [ ] Checkbox / toggle
- [ ] Panel / card

**Phase 3: Layout Components**
- [ ] Sidebar
- [ ] Modal
- [ ] Tabs
- [ ] Breadcrumb
- [ ] Status bar

**Phase 4: Indicators & Feedback**
- [ ] Badge
- [ ] Progress bar
- [ ] Spinner/loader
- [ ] Toast notification
- [ ] Tooltip

**Phase 5: Decorative & Canvas**
- [ ] Particle effect system (floating motes)
- [ ] Glow aura halos
- [ ] Light thread connections
- [ ] Ambient animation layer
- [ ] World renderer integration

---

## 🎨 Design Tokens

### Spacing Scale

```css
--spacing-xs: 4px;
--spacing-sm: 8px;
--spacing-md: 16px;
--spacing-lg: 24px;
--spacing-xl: 32px;
--spacing-2xl: 48px;
```

### Border Radius

```css
--radius-sm: 2px;      /* Subtle, UI elements */
--radius-md: 4px;      /* Standard buttons, inputs */
--radius-lg: 8px;      /* Cards, panels */
--radius-xl: 12px;     /* Large decorative */
--radius-round: 50%;   /* Circles */
```

### Shadows

```css
--shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.04);
--shadow-md: 0 2px 8px rgba(0, 0, 0, 0.08);
--shadow-lg: 0 4px 12px rgba(0, 0, 0, 0.12);
--shadow-xl: 0 8px 24px rgba(0, 0, 0, 0.16);

/* Magic glow (soft, diffused) */
--shadow-glow-sm: 0 0 8px rgba(216, 197, 232, 0.4);
--shadow-glow-md: 0 0 16px rgba(216, 197, 232, 0.3);
--shadow-glow-lg: 0 0 24px rgba(216, 197, 232, 0.2);
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

### Phase 1: Design System Foundation (Weeks 1–2)
**Goal:** Establish CSS tokens and component library structure

- [ ] Write `design-tokens.css` with all color, spacing, shadow, typography variables
- [ ] Create `animations.css` with all easing functions and @keyframes
- [ ] Set up component folder structure
- [ ] Document in `STYLE_GUIDE.md` (this file)
- [ ] Create visual regression test templates

**Deliverable:** Usable CSS foundation; no UI changes yet

### Phase 2: Admin Console Redesign (Weeks 3–5)
**Goal:** Retrofit admin dashboard to new aesthetic

- [ ] Audit current admin UI components
- [ ] Redesign panels, buttons, inputs using new tokens
- [ ] Add hover/active/focus states to all interactive elements
- [ ] Integrate ambient animations (background particle drift)
- [ ] Test on desktop (1920x1080, 1440x900)

**Deliverable:** Functional admin console with full style compliance

### Phase 3: Web Client Redesign (Weeks 6–8)
**Goal:** Redesign player-facing web client

- [ ] Audit current web UI components
- [ ] Redesign chat, inventory, spell bar, character sheet
- [ ] Add world-space rendering with Canvas (ambient motes, glows)
- [ ] Integrate decorative elements (light threads, halos)
- [ ] Test on desktop + mobile (375px, 768px, 1024px)

**Deliverable:** Full player-facing client with immersive aesthetic

### Phase 4: World Rendering Integration (Weeks 9–10)
**Goal:** Apply style to in-game room/mob/item visuals

- [ ] Extend world-space canvas renderer
- [ ] Integrate ambient particle system
- [ ] Apply lighting rules to mob/NPC renders
- [ ] Test room descriptions with Canvas backdrop

**Deliverable:** Cohesive world rendering experience

### Phase 5: Iteration & Polish (Week 11+)
**Goal:** Refine based on user feedback

- [ ] Collect player feedback on new aesthetic
- [ ] Refine easing/animation timings
- [ ] Create style variants (e.g., `surreal_softmagic_night_v1`)
- [ ] Performance optimization

**Deliverable:** Production-ready design system

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
- Use a color picker to verify hex values match tokens
- Consider dark mode implications early (future consideration)
- Get feedback from accessibility checkers (WebAIM, WAVE)

### For Developers
- Import `design-tokens.css` first in all stylesheets
- Never hardcode colors — always use CSS variables
- Use `calc()` for spacing combinations (e.g., `calc(var(--spacing-md) + var(--spacing-sm))`)
- Animate only `transform` and `opacity` for performance
- Test on both desktop and mobile breakpoints

### For Contributors
- Always reference this guide when creating new components
- Submit visual regression tests for new elements
- Ask questions in pull request reviews if the style is unclear
- Update this guide if you discover ambiguities or edge cases

---

**Last Updated:** February 26, 2026
**Next Review:** April 30, 2026

