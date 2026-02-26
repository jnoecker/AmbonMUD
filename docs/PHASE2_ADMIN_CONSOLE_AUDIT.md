# Phase 2: Admin Console Redesign — Audit

**Status:** Starting
**Current Aesthetic:** Dark blue/cyberpunk
**Target Aesthetic:** Surreal Gentle Magic (surreal_softmagic_v1)

---

## 📋 Current Admin UI Inventory

The admin console is a web-based dashboard serving HTTP on a configurable port (default: 8080). It provides:

### Infrastructure
- Backend: `AdminHttpServer.kt` (Ktor)
- Frontend: HTML + CSS + vanilla JavaScript
- Current styling: `styles.css` (498 lines)
- Current layout: Flexbox with sidebar + main content

### Current Color Scheme

**Dark Blue/Cyberpunk Aesthetic:**
- Background layer 1: `#091322` (darkest)
- Background layer 2: `#10273f` (dark)
- Background layer 3: `#1a3a52` (lighter)
- Panels: `#0c1727ee` (semi-transparent)
- Lines/borders: `#314860` (muted blue)
- Text: `#d5e6f7` (light blue)
- Success/Health: `#78d8a7` (green)
- Error/Bad: `#ff8f8f` (red)

**Rationale for Change:**
- Current aesthetic is industrial/tech-focused
- Target is ethereal/magical
- Soft colors instead of high-contrast blues
- Gentle glows instead of sharp borders

---

## 🎨 Redesign Mapping

### Current → New Color Tokens

| Current | Purpose | New Token | New Color | Rationale |
|---------|---------|-----------|-----------|-----------|
| `#091322` | Dark bg | `--bg-primary` | `#E8E8F0` | Light, ethereal |
| `#10273f` | Secondary bg | `--bg-secondary` | `#F8F8FC` | Subtle lavender tint |
| `#1a3a52` | Tertiary bg | `--color-primary-lavender` | `#D8C5E8` | Primary tone |
| `#314860` | Border/line | `--color-primary-pale-blue` | `#B8D8E8` | Soft dividers |
| `#d5e6f7` | Text | `--text-primary` | `#6B6B7B` | Dark mist (readable) |
| `#78d8a7` | Success | `--color-success` | `#C5D8A8` | Moss green |
| `#ff8f8f` | Error | `--color-error` | `#C5A8A8` | Desaturated red |

### Current → New Component Styles

| Component | Current | Changes | Priority |
|-----------|---------|---------|----------|
| Panel | Dark bg, sharp border | Lavender bg, soft border + glow | ⏳ HIGH |
| Button | Dark blue, hard edge | Soft colors, rounded, hover glow | ⏳ HIGH |
| Table | Unstyled rows | Card-based layout with hover | 🟡 MEDIUM |
| Input | Dark input, blue border | Cloud bg, pale blue border, focus glow | ⏳ HIGH |
| Badge | Unstyled | Rounded pill, semantic colors | 🟡 MEDIUM |
| Progress bar | Solid color | Gradient, subtle glow | 🟡 MEDIUM |

---

## 📦 Components to Redesign (Phase 2)

### Priority 1: Core Panels (MUST HAVE)

1. **Topbar/Header**
   - Current: Dark panel with title and status
   - New: Lavender bg, soft gold accent, breathing animation
   - Contains: App title, connection status, controls

2. **Panel Containers**
   - Current: `.sidebar-panel` (dark bg, hard border)
   - New: Soft lavender bg, pale blue border, glow on hover
   - Includes: Panel title, content, footer area

3. **Buttons**
   - Current: `.btn` (dark blue, hard edge)
   - New: `.btn` (lavender, rounded, hover → dusty rose)
   - Variants: primary, secondary, success, warning, accent

4. **Status Indicators**
   - Current: `.status.connected` / `.status.disconnected`
   - New: Badges with gentle pulse animation
   - Connected: moss green glow
   - Disconnected: error red, no glow

### Priority 2: Forms & Inputs (SHOULD HAVE)

5. **Text Inputs**
   - Current: None in current admin UI
   - New: Cloud bg, pale blue border, dusty rose focus
   - Usage: Player search, filters

6. **Checkboxes/Toggles**
   - Current: None
   - New: Custom styled, animated transitions
   - Usage: Admin actions, filters

### Priority 3: Data Display (NICE TO HAVE)

7. **Progress Bars**
   - Current: `.bar-fill` (hard color transition)
   - New: Gradient fill, subtle glow
   - Usage: HP bars, XP bars

8. **Tables/Lists**
   - Current: `.player-item`, `.mob-item` (text rows)
   - New: Card-based layout, hover lift effect
   - Usage: Player list, mob list, inventory

9. **Badges**
   - Current: `.status` (inline, hard edge)
   - New: Semantic color tokens, rounded pills
   - Usage: Level badge, rank badge, status

---

## 🎯 Implementation Plan

### Step 1: Create New Admin-Specific CSS

File: `src/main/resources/web/styles/admin.css`

Content:
- Import design-tokens, typography, animations, components
- Admin-specific overrides (topbar styling, panels, etc.)
- Animations for admin dashboard (transitions, pulse effects)

### Step 2: Update styles.css

Decisions:
- Keep as-is for now (player client, separate concern)
- New admin dashboard will use new CSS
- Later: Migrate player UI to design system

### Step 3: Test Redesigned Admin Components

Create: `src/main/resources/web/admin-showcase.html`
- Mirror of components-showcase.html
- Shows admin-specific components
- Test in browser before production

### Step 4: Apply to Admin HTTP Server

Update: Admin template rendering
- Use new CSS classes
- Apply animations
- Test with real admin interface

---

## 📐 Component Details

### Panels (Current vs. New)

**Before:**
```css
.sidebar-panel {
    border: 1px solid var(--line);        /* hard blue border */
    border-radius: 0.6rem;
    background: var(--panel);             /* dark semi-transparent */
    padding: 0.65rem 0.8rem;
}
```

**After:**
```css
.sidebar-panel {
    border: 1px solid var(--color-primary-pale-blue);
    border-radius: var(--radius-lg);      /* 8px instead of 0.6rem */
    background: var(--bg-primary);        /* light lavender tint */
    padding: var(--spacing-lg);
    box-shadow: var(--shadow-md);
    transition: all var(--duration-default) var(--ease-out-soft);
}

.sidebar-panel:hover {
    box-shadow: var(--shadow-glow-md);
    border-color: var(--color-primary-dusty-rose);
}
```

### Buttons (Current vs. New)

**Before:**
```css
button {
    border: 1px solid var(--line);        /* hard border */
    border-radius: 0.45rem;
    background: #15293d;                  /* dark blue */
    color: var(--text);
    padding: 0.35rem 0.65rem;
    cursor: pointer;
}

button:hover {
    background: #1e3a58;                  /* slightly lighter blue */
}
```

**After:**
```css
.btn {
    border: 1px solid var(--color-primary-pale-blue);
    border-radius: var(--radius-md);      /* 4px */
    background: var(--color-primary-lavender);
    color: var(--text-primary);
    padding: var(--spacing-sm) var(--spacing-md);
    cursor: pointer;
    box-shadow: var(--shadow-md);
    transition: all var(--duration-default) var(--ease-out-soft);
}

.btn:hover {
    background: var(--color-primary-dusty-rose);
    box-shadow: 0 4px 12px rgba(216, 197, 232, 0.3);
    transform: translateY(-1px);
}

.btn:active {
    background: var(--color-primary-pale-blue);
    transform: scale(0.98);
}
```

### Status Badges (Current vs. New)

**Before:**
```css
.status {
    display: inline-block;
    border-radius: 999px;
    padding: 0.2rem 0.6rem;
    font-size: 0.8rem;
    font-weight: 700;
}

.status.connected {
    color: #072018;
    background: var(--ok);                /* hard green */
}

.status.disconnected {
    color: #2d0808;
    background: var(--bad);               /* hard red */
}
```

**After:**
```css
.status {
    display: inline-flex;
    align-items: center;
    border-radius: 12px;
    padding: var(--spacing-sm) var(--spacing-md);
    font-size: var(--font-size-xs);
    font-weight: 600;
    letter-spacing: 0.5px;
}

.status.connected {
    background: var(--color-primary-moss-green);
    color: var(--text-primary);
    box-shadow: var(--shadow-glow-sm);
    animation: pulseGlow 3s var(--ease-in-out-smooth) infinite;
}

.status.disconnected {
    background: var(--color-error);
    color: var(--text-primary);
    opacity: var(--opacity-disabled);
}
```

---

## 🧪 Acceptance Criteria (Phase 2 Complete)

- [ ] All admin panels restyled with new color palette
- [ ] Buttons have all states (default, hover, active, disabled, focus)
- [ ] Status badges animated with pulse effect
- [ ] Progress bars render with gradient and glow
- [ ] Text contrast meets WCAG AA (4.5:1)
- [ ] Hover effects feel smooth (200ms transitions)
- [ ] No color mismatches from design tokens
- [ ] Responsive: tested on 1920x1080, 1440x900, 1366x768
- [ ] Browser compatibility: Chrome, Firefox, Safari (latest)
- [ ] Admin showcase page displays all components correctly

---

## 🗂 Files to Create/Modify

**New Files:**
- `src/main/resources/web/styles/admin.css` — Admin-specific styling
- `src/main/resources/web/admin-showcase.html` — Component showcase
- `docs/PHASE2_ADMIN_CONSOLE_AUDIT.md` — This file

**Modified Files:**
- None immediately (admin rendering is templated in Kotlin)

---

## 📅 Timeline

**Phase 2: Weeks 3–5**
- Week 3: Create admin.css, test in showcase
- Week 4: Apply to actual admin server templates
- Week 5: Cross-browser testing, refinement

---

## 🎨 Visual Checklist

When reviewing redesigned components, verify:

- [ ] Colors match design tokens exactly
- [ ] Shadows are soft (no harsh contrast)
- [ ] Borders are gentle curves (not sharp)
- [ ] Animations use approved easing
- [ ] Hover states feel responsive
- [ ] Focus states have glow, not harsh border
- [ ] Disabled states are clearly visible but not jarring
- [ ] Typography matches hierarchy
- [ ] Spacing follows design tokens
- [ ] Emotional check: Does this feel safe and enchanted?

---

**Document Created:** February 26, 2026
**Phase Status:** ⏳ In Progress
**Next Steps:** Create admin.css with redesigned components
