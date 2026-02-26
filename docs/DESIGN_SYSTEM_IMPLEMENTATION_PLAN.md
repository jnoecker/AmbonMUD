# Design System Implementation Plan

**Project:** AmbonMUD Surreal Gentle Magic Design System
**Version:** surreal_softmagic_v1
**Timeline:** 11 weeks (phased approach)
**Status:** Planning

---

## 📋 Executive Summary

This document outlines the step-by-step plan to establish a cohesive design system across AmbonMUD's admin console and web client, with future world rendering integration.

**Key Goals:**
1. Create reusable, token-based component library
2. Establish visual consistency (admin + web client)
3. Enable rapid iteration and new feature design
4. Prepare infrastructure for world rendering aesthetics

**Tech Stack:** Hybrid CSS + Canvas/SVG
**Team:** Design + Frontend developers
**Dependencies:** None (greenfield implementation)

---

## 🎯 Phase Breakdown

### Phase 1: Design System Foundation (Weeks 1–2)

**Objective:** Build the CSS backbone that all components will reference.

#### Deliverables

**1.1 Create Design Tokens CSS File**
- File: `src/main/resources/web/styles/design-tokens.css`
- Content:
  - Color palette (primary, neutral, semantic)
  - Spacing scale (`--spacing-xs` through `--spacing-2xl`)
  - Border radius scale
  - Shadow scale (standard + glow variants)
  - Z-index scale
  - Typography scale
  - Animation easing functions

**Acceptance Criteria:**
- All 50+ tokens documented
- Tokens follow CSS custom property naming convention
- Variables can be imported into any stylesheet
- No hardcoded colors/shadows in other files

---

**1.2 Create Typography CSS**
- File: `src/main/resources/web/styles/typography.css`
- Content:
  - Font imports (Georgia, Crimson Text, Segoe UI, Inter, Courier New)
  - Font-face declarations with weights (400, 500, 600)
  - Heading/body/caption classes
  - Line-height scales
  - Letter-spacing rules

**Acceptance Criteria:**
- All fonts load without FOUT/FOIT issues
- Typography stack respects fallbacks
- Responsive sizing (optional: larger on desktop)

---

**1.3 Create Animations CSS**
- File: `src/main/resources/web/styles/animations.css`
- Content:
  - Easing function definitions (CSS variables)
  - @keyframes for common animations:
    - `fadeIn`, `fadeOut`
    - `slideIn`, `slideOut`
    - `pulse`, `breath`
    - `glow`, `shimmer`
    - `float` (for particles)
  - Animation class utilities (`.anim-fade-in`, `.anim-slide-up`, etc.)

**Acceptance Criteria:**
- All animations use defined easing functions (no linear)
- No hard-coded durations in @keyframes (use CSS vars where possible)
- Visual regression test: all animations render smoothly

---

**1.4 Create Reset/Normalization CSS**
- File: `src/main/resources/web/styles/reset.css`
- Content:
  - Browser default resets
  - Box-sizing rule
  - Font smoothing for Mac/Windows
  - Link underline removal
  - Button/input normalization
  - Ensure consistency across browsers

**Acceptance Criteria:**
- Buttons render identically in Chrome, Firefox, Safari
- Form inputs have consistent baseline height
- No unexpected margins/paddings

---

**1.5 Create Component Registry Document**
- File: `docs/COMPONENT_REGISTRY.md`
- Content:
  - List of all components to build (categorized)
  - Status: ✅ Done / 🔄 In Progress / ⏳ Pending
  - Links to component stories (visual regression tests)
  - Changelog tracking

**Example:**
```markdown
## Buttons
- [ ] Button (default, hover, active, disabled, focus)
- [ ] Button Group
- [ ] Icon Button
- [ ] Split Button

## Inputs
- [ ] Text Input
- [ ] Number Input
- [ ] Checkbox
- [ ] Radio
- [ ] Toggle
- [ ] Slider
```

---

**1.6 Setup Visual Regression Test Framework**
- Location: `src/main/resources/web/components/`
- Create template file: `component-stories.template.html`
- Template includes:
  - Component in all states (default, hover, active, disabled, focus)
  - Light + dark backgrounds for context
  - Pixel-perfect dimensions labeled
  - Color hex values in UI

**Acceptance Criteria:**
- Can take screenshot of every component variant
- Easy to spot visual drift between versions
- Can compare side-by-side with previous version

---

### Phase 2: Admin Console Redesign (Weeks 3–5)

**Objective:** Retrofit existing admin dashboard to new aesthetic while maintaining functionality.

#### 2.1 Audit Current Admin UI

**Task:** Inventory every UI element in admin console
- File: `docs/ADMIN_UI_AUDIT.md`
- Document:
  - Panels (player lookup, metrics, settings)
  - Buttons (kick, shutdown, spawn, goto)
  - Tables (player list, sessions)
  - Forms (input fields, dropdowns)
  - Modals (confirmation dialogs)
  - Status indicators (badges, colors)
  - Current color usage (create a "before" screenshot)

**Acceptance Criteria:**
- Every UI element accounted for
- Screenshots showing current state
- Marked for redesign priority (high/medium/low)

---

**2.2 Redesign Core Admin Components**

**Priority Order:**
1. **Panel container** (wrapper for all sections)
   - Rounded corners (8px)
   - Lavender background
   - Pale blue border (1px)
   - Soft shadow
   - 16px padding

2. **Button set** (primary action buttons)
   - Kick, Shutdown, Spawn, Goto
   - Use dusty rose on hover
   - Icon + text
   - Consistent height (40px)

3. **Table headers** (player list, metrics)
   - Slightly darker background (pale blue tint)
   - Moss green text for column labels
   - Soft gold for sort indicator

4. **Input fields** (search, filter)
   - Match design tokens
   - Focus state with dusty rose border
   - Placeholder text in soft fog

5. **Status badges** (online/offline, rank)
   - Green = online
   - Gray = offline
   - Gold = admin/staff
   - Rounded pill (24px height)

**Acceptance Criteria:**
- All existing functionality preserved
- New styling matches design tokens exactly
- All interactive states work (hover, focus, active, disabled)
- No browser inconsistencies

---

**2.3 Integrate Ambient Animations**

**Task:** Add subtle background animations to admin console
- Floating particles in panel backgrounds (2–3% opacity)
- Soft pulse on active/hovered elements (±5% brightness)
- Gentle breathing animation on focus (200ms in/out)

**File:** `src/main/resources/web/components/decorative/particle-effect.js`

**Acceptance Criteria:**
- Animations don't interfere with content readability
- GPU-friendly (use `transform` and `opacity` only)
- Performance: >60fps on modern desktop

---

**2.4 Test Admin Console Redesign**

**Browsers:** Chrome, Firefox, Safari (latest versions)
**Screen sizes:** 1920x1080, 1440x900, 1366x768

**Checklist:**
- [ ] All panels render correctly
- [ ] Buttons respond to hover/active/focus
- [ ] Tables scroll smoothly
- [ ] Modals display properly
- [ ] Forms accept input without visual glitches
- [ ] Color contrast meets WCAG AA (4.5:1 for text)

---

### Phase 3: Web Client Redesign (Weeks 6–8)

**Objective:** Redesign player-facing web client with immersive aesthetic.

#### 3.1 Audit Current Web Client

**File:** `docs/WEB_CLIENT_UI_AUDIT.md`
- Document all player-facing UI sections:
  - Chat area
  - Inventory panel
  - Spell bar
  - Character sheet
  - Status effects display
  - Command input
  - Who list
  - Map (if present)

---

**3.2 Redesign Web Client Components**

**Priority Order:**

1. **Chat Panel** (primary content area)
   - Background: cloud white with slight lavender tint
   - Text: deep mist
   - System messages: pale blue
   - Player speech: dusty rose
   - Scrollbar: soft gold track, pale blue thumb

2. **Inventory Panel** (items list)
   - Card layout for each item
   - Item name: dark text
   - Item description: soft fog text (12px)
   - Quantity badge: moss green (16px pill)
   - Equipped indicator: soft gold glow

3. **Spell/Ability Bar** (action buttons)
   - Grid layout (4–6 columns)
   - Icon + name + cooldown timer
   - Active state: dusty rose background
   - On cooldown: grayed out (60% opacity)
   - Hover: lavender background

4. **Character Sheet** (stats display)
   - Tabs: Level, Skills, Equipment, Achievements
   - Stat rows: Name | Value (right-aligned)
   - Health/Mana bars: moss green / pale blue
   - Level-up indicator: soft gold glow + animation

5. **Status Effects Display** (buffs/debuffs)
   - Icon grid (2–3 columns)
   - Rounded squares (40px)
   - Hover tooltip shows: effect name, duration, stacks
   - Active effects: dusty rose border
   - Negative effects: subtle red-tint background

6. **Command Input** (where player types)
   - Full width, clean
   - Focus state: lavender border + glow
   - Placeholder: soft fog
   - Auto-complete suggestions: lavender background

**Acceptance Criteria:**
- All existing commands still work
- Visual design consistent with admin console
- Responsive: 375px (mobile) to 1920px (desktop)
- Touch-friendly on mobile (48px min target size)

---

**3.3 Implement Canvas World Rendering**

**File:** `src/main/resources/web/canvas/world-renderer.js`

**Features:**
- Render room descriptions with Canvas backdrop
- Particle system for ambient motes (Canvas-based)
- Light threads connecting magical objects
- Character/NPC halos (SVG or Canvas)
- Smooth transitions between rooms

**Acceptance Criteria:**
- World renders at 60fps
- Particles don't slow down main UI
- Room transitions feel smooth (300ms)

---

**3.4 Test Web Client Redesign**

**Browsers:** Chrome, Firefox, Safari, Edge
**Screen sizes:** 375px, 768px, 1024px, 1366px, 1920px

**Devices (if possible):** iPhone 12, iPad Air, Android phone

**Checklist:**
- [ ] All panels render without overlap
- [ ] Chat scrolls smoothly
- [ ] Inventory items load quickly
- [ ] Spell bar responds instantly to clicks
- [ ] Character sheet displays correctly
- [ ] Status effects icons are recognizable
- [ ] Canvas world renders without freezing
- [ ] Touch interactions work on mobile
- [ ] Color contrast meets WCAG AA

---

### Phase 4: World Rendering Integration (Weeks 9–10)

**Objective:** Extend canvas rendering to include ambient world effects.

#### 4.1 Ambient Particle System

**File:** `src/main/resources/web/canvas/particle-system.js`

**Features:**
- Floating motes (pollen, dust, light particles)
- Configurable emission rate, lifetime, speed
- Soft glow rendering (using canvas blur)
- Multiple particle layers (background, midground, foreground)

**Example Integration:**
```javascript
const ambientParticles = new ParticleSystem({
  particleCount: 20,
  emissionRate: 2,    // new particles per frame
  speed: { min: 0.5, max: 1.5 },
  lifetime: { min: 3000, max: 8000 },
  color: '#D8C5E8',   // lavender
  opacity: 0.3,
  glowRadius: 4,
});

// Render in game loop
ambientParticles.update(deltaTime);
ambientParticles.render(canvasContext);
```

**Acceptance Criteria:**
- Particles render smoothly without stutter
- Opacity and motion feel natural
- No performance degradation on lower-end devices

---

**4.2 Lighting System**

**File:** `src/main/resources/web/canvas/lighting.js`

**Features:**
- Soft glow around magical objects
- Ambient light level (affects overall brightness)
- Light threads connecting related objects
- Bloom effect (subtle, <5px radius)

**Acceptance Criteria:**
- Glows feel soft (not harsh)
- Light threads are visible but not distracting
- Bloom doesn't wash out content

---

**4.3 Room-Specific Ambient Rendering**

**Integration:**
- When entering a room, pass room data to world renderer
- Renderer generates particle system config based on room mood/theme
- Example:
  - "Enchanted Forest" → green particles, tall trees
  - "Underground Cavern" → fewer particles, bioluminescent moss
  - "Ancient Temple" → golden particles, ethereal atmosphere

**Acceptance Criteria:**
- Room descriptions feel immersive
- Ambient effects enhance mood without interfering with text
- Transitions between rooms feel seamless

---

### Phase 5: Iteration & Polish (Week 11+)

**Objective:** Refine based on user feedback and performance data.

#### 5.1 Collect User Feedback

- Post screenshots to community
- Ask: "How does this feel?"
- Metrics to track:
  - Time spent in web client (engagement)
  - No. of players using new UI
  - Accessibility complaints

---

**5.2 Performance Optimization**

- Profile Canvas rendering (60fps target)
- Optimize particle system for lower-end devices
- Cache rendered components where possible
- Minimize reflows/repaints

---

**5.3 Create Style Variants**

Document additional variants:
- `surreal_softmagic_night_v1` — Moonlit (deeper blues, softer glow)
- `surreal_softmagic_feycourt_v1` — Regal (golds, jewel tones)

---

## 📊 Component Checklist (Master List)

### Phase 1: Foundations ✅
- [x] Design tokens CSS
- [x] Typography CSS
- [x] Animations CSS
- [x] Reset/normalization
- [x] Component registry
- [x] Visual regression test template

### Phase 2: Admin Console 🔄
- [ ] Audit current admin UI
- [ ] Redesign panels
- [ ] Redesign buttons
- [ ] Redesign tables
- [ ] Redesign forms
- [ ] Redesign modals
- [ ] Integrate ambient animations
- [ ] Test (browsers + screen sizes)
- [ ] Performance profile

### Phase 3: Web Client ⏳
- [ ] Audit current web client
- [ ] Redesign chat panel
- [ ] Redesign inventory panel
- [ ] Redesign spell bar
- [ ] Redesign character sheet
- [ ] Redesign status effects display
- [ ] Redesign command input
- [ ] Implement canvas world rendering
- [ ] Test (browsers + screen sizes + devices)
- [ ] Performance profile

### Phase 4: World Rendering ⏳
- [ ] Ambient particle system
- [ ] Lighting system
- [ ] Light threads
- [ ] Room-specific ambient effects
- [ ] Integration testing

### Phase 5: Polish ⏳
- [ ] Collect user feedback
- [ ] Performance optimization
- [ ] Create style variants
- [ ] Documentation updates

---

## 🚀 How to Execute

### Week 1: Foundation Setup

**Monday–Wednesday:**
- Create all Phase 1 CSS files
- Import into main stylesheets
- Verify no conflicts

**Thursday–Friday:**
- Create visual regression test template
- Document component registry
- Create audit documents

### Week 2: Foundation Refinement

- Test tokens across browsers
- Adjust easing functions based on real-world animations
- Begin Phase 2 preparation (audit current admin UI)

### Weeks 3–11: Phased Rollout

- Follow phase breakdown above
- Weekly sync: review progress, identify blockers
- Maintain visual regression tests throughout

---

## 📈 Success Metrics

**Admin Console:**
- ✅ All UI elements styled per design system
- ✅ No color mismatches from design tokens
- ✅ Hover/active/focus states work on all elements
- ✅ WCAG AA contrast compliance
- ✅ >60fps animation performance

**Web Client:**
- ✅ All panels styled consistently
- ✅ Responsive on mobile (375px+)
- ✅ Touch-friendly interaction targets (48px+)
- ✅ Canvas rendering at 60fps
- ✅ Player feedback: "feels cohesive and magical"

**Design System (Overall):**
- ✅ >90% component reusability across console and client
- ✅ New features can be designed in <30 mins
- ✅ Zero color/spacing inconsistencies
- ✅ CSS tokens reduce maintenance burden by >40%

---

## 🔗 Dependencies & Risks

### Dependencies
- None on other projects (greenfield)
- Depends on design system finalization (Phase 1)

### Risks
- **Color perception drift:** Different monitors display lavender/pale blue differently
  - *Mitigation:* Use hex values consistently; test on calibrated monitors

- **Canvas performance on older devices**
  - *Mitigation:* Disable canvas effects for low-spec browsers; graceful degradation

- **Animation choppiness on slow networks**
  - *Mitigation:* Use CSS animations (GPU-accelerated) over JavaScript

- **Team unfamiliar with Canvas rendering**
  - *Mitigation:* Early spike on Canvas particle systems; documentation + examples

---

## 📝 Handoff & Maintenance

### At End of Phase 5
- Deliver: Complete design system documentation
- Deliver: CSS component library (reusable across projects)
- Deliver: Canvas rendering framework (for future world rendering)
- Deliver: Visual regression test suite
- Deliver: Component stories (design reference)

### Ongoing Maintenance
- **Weekly:** Monitor visual regression tests
- **Monthly:** Collect user feedback; iterate on animations
- **Quarterly:** Review color palette; consider seasonal variants

---

## 📚 Additional Resources

- **Design System Basics:** [Design Tokens in CSS](https://www.smashingmagazine.com/2022/09/inline-svg-fallback-modern-css/)
- **Canvas Optimization:** [MDN Canvas Performance](https://developer.mozilla.org/en-US/docs/Web/API/Canvas_API/Tutorial/Optimizing_canvas)
- **Color Theory:** [WCAG Color Contrast](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html)
- **Typography:** [Web Typography Best Practices](https://www.smashingmagazine.com/2021/05/typographic-hierarchy-tips/)

---

**Document Version:** 1.0
**Last Updated:** February 26, 2026
**Next Review:** March 15, 2026 (after Phase 1 completion)
