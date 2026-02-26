# Phase 3: Web Client Redesign — Audit

**Status:** Starting
**Current Aesthetic:** Dark blue/cyberpunk (terminal-like)
**Target Aesthetic:** Surreal Gentle Magic (surreal_softmagic_v1)
**Timeline:** Weeks 6–8

---

## 📋 Current Web Client Inventory

### Architecture

**HTML (index.html):**
- Single-page application
- xterm.js terminal (30 rows, monospace font)
- Responsive flex layout (main terminal + sidebar)
- 8 sidebar panels with player state information

**JavaScript (app.js):**
- ~2600 lines handling WebSocket, rendering, input
- Terminal management via xterm.js
- Sidebar panel updates (dynamic HTML)
- Command history and tab completion
- GMCP protocol handler
- Map canvas rendering (basic)

**Styling (styles.css):**
- ~500 lines of CSS
- Dark blue/cyberpunk color scheme
- Hard borders and sharp shadows
- Terminal-focused typography (monospace)
- Basic responsive design

### Current Color Scheme

**Dark Blue/Cyberpunk:**
- Background 1: `#091322` (darkest)
- Background 2: `#10273f` (dark)
- Background 3: `#1a3a52` (lighter)
- Text: `#d5e6f7` (light blue)
- HP bar: `#4caf87` (green)
- Mana bar: `#4a7cc7` (blue)
- XP bar: `#c7a44a` (gold)
- Mob HP: `#c75a4a` (red)

---

## 🎨 UI Components Inventory

### 1. Topbar/Header
- **Current:** Dark panel with title and status
- **Elements:** Title, status badge, reconnect button
- **Needs redesign:** Color, shadows, hover states

### 2. Terminal Column
- **Current:** xterm.js terminal + navigation bar
- **Terminal settings:** 30 rows, monospace, dark blue theme
- **Navigation bar:** Shows room exits as buttons
- **Needs redesign:** Terminal theming, exit button styling

### 3. Sidebar Panels (8 total)

#### Panel 1: Character
- **Shows:** Name, class/race, HP, Mana, XP bars, Level, Gold
- **Elements:** Title, stat rows, progress bars, text labels
- **Needs redesign:** Bar gradients, stat layout, typography

#### Panel 2: Map
- **Shows:** Canvas-based room map
- **Size:** 210x160px
- **Current:** Basic grid rendering
- **Needs redesign:** Canvas styling, integration with world rendering

#### Panel 3: Room
- **Shows:** Room title, description, exits
- **Elements:** Text content, exit buttons
- **Needs redesign:** Description text color, exit button styling

#### Panel 4: Players Here
- **Shows:** List of players in current room
- **Elements:** Player names, levels, class/race
- **Format:** Simple text list
- **Needs redesign:** Card-like layout, hover effects

#### Panel 5: Mobs Here
- **Shows:** Mob list with HP bars
- **Elements:** Mob names, HP bars, levels
- **Format:** Name + compact health bar
- **Needs redesign:** Mob card layout, bar styling

#### Panel 6: Effects
- **Shows:** Active status effects
- **Elements:** Effect name, type badge, duration
- **Format:** List with type indicators
- **Needs redesign:** Effect icons/colors, badge styling

#### Panel 7: Inventory
- **Shows:** Items in backpack
- **Elements:** Item names, quantities
- **Format:** Simple text list
- **Needs redesign:** Card layout, rarity colors

#### Panel 8: Equipment
- **Shows:** Equipped items
- **Elements:** Slot names, item names
- **Format:** Slot-based list
- **Needs redesign:** Slot layout, item highlighting

---

## 🔄 Redesign Mapping

### Current → New Color Tokens

| Current | Purpose | New Token | New Color | Rationale |
|---------|---------|-----------|-----------|-----------|
| `#091322` | Dark bg | `--bg-primary` | `#E8E8F0` | Light, ethereal |
| `#10273f` | Secondary bg | `--bg-secondary` | `#F8F8FC` | Subtle lavender |
| `#1a3a52` | Tertiary bg | `--color-primary-lavender` | `#D8C5E8` | Primary tone |
| `#d5e6f7` | Text | `--text-primary` | `#6B6B7B` | Dark mist |
| `#4caf87` | HP | `--color-primary-moss-green` | `#C5D8A8` | Moss green |
| `#4a7cc7` | Mana | `--color-primary-pale-blue` | `#B8D8E8` | Pale blue |
| `#c7a44a` | XP | `--color-primary-soft-gold` | `#E8D8A8` | Soft gold |
| `#c75a4a` | Mob HP | `--color-error` | `#C5A8A8` | Desaturated red |

### Component Redesign Priority

**Priority 1: Core Panels (MUST HAVE)**

1. **Topbar**
   - New: Soft gradient background, pale blue border
   - Add: Breathing glow animation on connected status
   - Status badge: Moss green pulse when connected

2. **Character Panel**
   - New: Gradient progress bars with glow
   - Improve: Stat typography and spacing
   - Add: Hover tooltips for details

3. **Progress Bars (HP/Mana/XP)**
   - Current: Flat colors with hard edges
   - New: Gradients with soft glow
   - Moss green (HP) → Pale blue (Mana) → Soft gold (XP)
   - Box shadow with color-matched glow

4. **Sidebar Panels**
   - Current: Dark bg, hard borders, monospace
   - New: Lavender bg, soft pale blue borders
   - Add: Hover lift effect (+2px)
   - Typography: Better hierarchy, proportional fonts

5. **Exit Buttons (Nav Bar)**
   - Current: Small dark blue buttons
   - New: Lavender buttons, dusty rose on hover
   - Add: Smooth transitions and scale effects

**Priority 2: Data Display (SHOULD HAVE)**

6. **Lists (Players, Mobs, Items)**
   - Current: Plain text with dividers
   - New: Card-like rows with hover effects
   - Mobs: Health bar styling with gentle red gradient

7. **Effect Badges**
   - Current: Colored tags
   - New: Semantic colors from design system
   - Icons or visual indicators for effect types

8. **Equipment Slot Display**
   - Current: Slot name + item name
   - New: Slot badges, item card layout
   - Empty slots: Soft gray placeholder

**Priority 3: Interactive Elements (NICE TO HAVE)**

9. **Terminal Theme**
   - Current: Dark blue terminal colors
   - Option: Keep terminal as-is, or light theme variant
   - Consider: xterm.js theme customization

10. **Command Input**
    - Current: Part of terminal
    - Enhanced: Visual feedback, autocomplete suggestions
    - Add: Soft glow on focus

11. **Canvas Integration**
    - Current: Basic grid map
    - New: World rendering with particles
    - Ambient effects layer

---

## 📐 Implementation Strategy

### Phase 3a: CSS Overhaul (Weeks 6–7)

**Step 1: Update styles.css**
- Replace hardcoded colors with design tokens
- Import design system CSS (design-tokens, typography, animations)
- Rewrite panel styling with soft shadows and glows
- Update typography for better hierarchy

**Step 2: Create web-client.css**
- Specific overrides for web client UI
- Sidebar panel styling with Surreal Gentle Magic aesthetic
- Terminal container theming
- Animation definitions for interactive elements

**Step 3: Update app.js (minimal changes)**
- No functional changes needed
- Optional: Add CSS class animations
- Optional: GMCP-based theme switching

### Phase 3b: Testing & Refinement (Week 8)

**Step 4: Visual Testing**
- Desktop: 1920x1080, 1440x900, 1366x768
- Tablet: 768px, 1024px
- Mobile: 375px, 480px, 600px
- Browsers: Chrome, Firefox, Safari

**Step 5: Component Validation**
- All panels render correctly with new colors
- Progress bars animate smoothly
- Hover effects feel responsive
- Colors maintain proper contrast (WCAG AA)

**Step 6: Polish**
- Adjust animations for feel
- Fine-tune spacing and typography
- Add transitions where appropriate
- Performance check (60fps)

---

## 🎨 Visual Specifications

### Sidebar Panel Base Style

```css
.sidebar-panel {
  background: var(--bg-primary);           /* Light lavender-tinted */
  border: 1px solid var(--pale-blue);      /* Soft pale blue */
  border-radius: 8px;                      /* Gentle curves */
  padding: 16px;                           /* Design token spacing */
  box-shadow: 0 1px 2px rgba(0,0,0,0.04); /* Subtle shadow */
  transition: all 200ms ease-out-soft;     /* Smooth animation */
}

.sidebar-panel:hover {
  box-shadow: 0 0 16px rgba(216,197,232,0.3); /* Glow on hover */
  border-color: var(--dusty-rose);            /* Accent color shift */
  transform: translateY(-2px);                 /* Lift effect */
}
```

### Progress Bar Styling

```css
.bar-fill {
  border-radius: 5px;
  transition: width 300ms ease-out-soft;
}

.bar-fill.hp {
  background: linear-gradient(90deg,
    var(--moss-green),
    rgba(197,216,168,0.8));
  box-shadow: 0 0 8px rgba(197,216,168,0.4);
}

.bar-fill.mana {
  background: linear-gradient(90deg,
    var(--pale-blue),
    rgba(184,216,232,0.8));
  box-shadow: 0 0 8px rgba(184,216,232,0.4);
}

.bar-fill.xp {
  background: linear-gradient(90deg,
    var(--soft-gold),
    rgba(232,216,168,0.8));
  box-shadow: 0 0 8px rgba(232,216,168,0.4);
}
```

### List Item Styling

```css
.inv-item,
.mob-item,
.player-item {
  padding: 8px 0;
  border-bottom: 1px solid rgba(184,216,232,0.2);
  transition: background-color 200ms ease-out-soft;
}

.inv-item:hover,
.mob-item:hover,
.player-item:hover {
  background-color: rgba(216,197,232,0.1);
}
```

---

## 📊 Acceptance Criteria (Phase 3 Complete)

- [ ] All web client panels styled with new color palette
- [ ] Progress bars have gradients and soft glows
- [ ] Sidebar panels have hover lift effect (+2px)
- [ ] Exit buttons styled with new design tokens
- [ ] Status badges animate (pulse when connected)
- [ ] Text contrast meets WCAG AA (4.5:1)
- [ ] All animations use soft easing (200-300ms)
- [ ] Responsive on mobile (375px+) to desktop (1920px)
- [ ] Browser compatibility: Chrome, Firefox, Safari (latest)
- [ ] Terminal theme optional (light or dark)
- [ ] Canvas ready for world rendering integration
- [ ] No functional regressions (all gameplay works)

---

## 🗂 Files to Create/Modify

**New Files:**
- `docs/PHASE3_WEB_CLIENT_AUDIT.md` — This document
- `src/main/resources/web/styles/web-client.css` — Web-specific styling
- `src/main/resources/web/web-client-showcase.html` — Component showcase

**Modified Files:**
- `src/main/resources/web/styles.css` — Update with design tokens
- `src/main/resources/web/index.html` — Link new CSS files (optional)
- `src/main/resources/web/app.js` — Add CSS animations (optional)

---

## 📱 Responsive Breakpoints

- **Mobile:** 375px–599px
- **Tablet:** 600px–1023px
- **Desktop:** 1024px+

### Layout Behavior

- **Mobile:** Single column, stack all panels vertically
- **Tablet:** Flex wrap, panels in 2-3 columns
- **Desktop:** Terminal main + sidebar fixed, current layout

---

## 🎯 Canvas Integration Prep

The map canvas is already present. To prepare for Phase 4 (world rendering):

1. **Styling:** Give canvas soft border, subtle background
2. **Size:** Currently 210x160px, may want responsive sizing
3. **Layer:** Plan for particle overlay (separate canvas or CSS)
4. **Integration:** Coordinate with GMCP room data flows

---

## 📅 Timeline

**Week 6:** CSS overhaul, panel styling, progress bars
**Week 7:** List items, effects badges, terminal theme, testing
**Week 8:** Cross-browser compatibility, refinement, polish

---

## 🧪 Testing Checklist

### Visual Regression
- [ ] All panels render without layout breaks
- [ ] Colors match design tokens exactly
- [ ] Shadows and glows appear as designed
- [ ] Animations are smooth (no stutter)

### Responsiveness
- [ ] Mobile (375px): Single column, readable text
- [ ] Tablet (768px): 2-column layout, touch-friendly
- [ ] Desktop (1920px): Full sidebar, terminal focus

### Accessibility
- [ ] Color contrast passes WCAG AA
- [ ] Focus states visible (no invisible elements)
- [ ] Animations respect prefers-reduced-motion
- [ ] Terminal remains accessible

### Compatibility
- [ ] Chrome (latest)
- [ ] Firefox (latest)
- [ ] Safari (latest)
- [ ] Edge (if applicable)

---

**Document Created:** February 26, 2026
**Phase Status:** ⏳ In Progress
**Next Steps:** Create web-client.css with redesigned components
