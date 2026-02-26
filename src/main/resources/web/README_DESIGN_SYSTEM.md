# AmbonMUD Design System — Surreal Gentle Magic

**Version:** surreal_softmagic_v1
**Status:** Foundation Phase (Phase 1 Complete)

---

## 📖 Overview

This folder contains the complete CSS foundation for the AmbonMUD design system, implementing the "Surreal Gentle Magic" aesthetic across all UI components.

### What's Included

- **design-tokens.css** — CSS custom properties for colors, spacing, shadows, typography, and animations
- **typography.css** — Complete font hierarchy (display, headings, body, captions)
- **animations.css** — Keyframe definitions and animation utility classes
- **components.css** — Styles for buttons, inputs, panels, cards, badges, modals, etc.
- **components-showcase.html** — Interactive demo showcasing all components and design tokens

---

## 🚀 Quick Start

### 1. View the Component Showcase

Open `components-showcase.html` in your browser to see:
- Complete color palette with hex values
- Typography hierarchy (all sizes and weights)
- Button variants and states
- Form elements (inputs, checkboxes, toggles)
- Indicators (badges, progress bars, spinners)
- Animation demonstrations
- Panels and cards
- Interactive examples

### 2. Use Design Tokens in Your Styles

Import the design tokens CSS in your stylesheet:

```html
<link rel="stylesheet" href="styles/design-tokens.css">
<link rel="stylesheet" href="styles/typography.css">
<link rel="stylesheet" href="styles/animations.css">
<link rel="stylesheet" href="styles/components.css">
```

Or in CSS:

```css
@import 'design-tokens.css';

.my-component {
  color: var(--text-primary);
  background: var(--color-primary-lavender);
  padding: var(--spacing-lg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  transition: all var(--duration-default) var(--ease-out-soft);
}

.my-component:hover {
  background: var(--color-primary-dusty-rose);
  box-shadow: var(--shadow-glow-md);
}
```

---

## 🎨 Color Tokens

All colors follow the Surreal Gentle Magic aesthetic:

### Primary Tones

| Color | Token | Hex | Use |
|-------|-------|-----|-----|
| Lavender | `--color-primary-lavender` | #D8C5E8 | Primary UI, buttons, panels |
| Pale Blue | `--color-primary-pale-blue` | #B8D8E8 | Secondary UI, borders |
| Dusty Rose | `--color-primary-dusty-rose` | #E8C5D8 | Accents, hover states |
| Moss Green | `--color-primary-moss-green` | #C5D8A8 | Success, navigation |
| Soft Gold | `--color-primary-soft-gold` | #E8D8A8 | Highlights, important elements |

### Neutral Palette

- `--color-neutral-deep-mist: #6B6B7B` — Text, dark elements
- `--color-neutral-soft-fog: #A8A8B8` — Secondary text, disabled
- `--color-neutral-cloud: #E8E8F0` — Backgrounds, surfaces

### Semantic Colors

- `--color-success: #C5D8A8` — Success states, checkmarks
- `--color-warning: #E8C5D8` — Warnings, alerts
- `--color-error: #C5A8A8` — Errors, deletions
- `--color-info: #B8D8E8` — Information, hints

---

## 📏 Spacing Scale

```css
--spacing-xs: 4px;     /* Minimal gap */
--spacing-sm: 8px;     /* Small padding */
--spacing-md: 16px;    /* Standard padding */
--spacing-lg: 24px;    /* Large padding */
--spacing-xl: 32px;    /* Extra large */
--spacing-2xl: 48px;   /* Huge spacing */
```

**Usage:**
```css
padding: var(--spacing-md);
margin-bottom: var(--spacing-lg);
gap: var(--spacing-sm);
```

---

## 🎭 Component Examples

### Button

```html
<button class="btn btn-primary">Click Me</button>
<button class="btn btn-secondary">Secondary</button>
<button class="btn btn-success">Success</button>
<button class="btn btn-lg">Large Button</button>
<button class="btn btn-sm">Small Button</button>
<button class="btn btn-icon btn-primary">✨</button>
```

### Input

```html
<input class="input" type="text" placeholder="Enter text...">
<input class="input error" type="text" value="Error state">
<textarea class="input" placeholder="Longer text..."></textarea>
```

### Checkbox

```html
<div class="checkbox">
  <input type="checkbox" id="check1">
  <label for="check1">Agree to terms</label>
</div>
```

### Toggle

```html
<button type="button" class="toggle"></button>
```

### Badge

```html
<span class="badge badge-primary">Primary</span>
<span class="badge badge-success">Success</span>
<span class="badge badge-warning">Warning</span>
<span class="badge badge-error">Error</span>
```

### Panel

```html
<div class="panel">
  <div class="panel-header">
    <h3 class="panel-title">Panel Title</h3>
  </div>
  <div class="panel-body">
    Panel content here
  </div>
  <div class="panel-footer">
    <button class="btn btn-sm">Action</button>
  </div>
</div>
```

### Progress Bar

```html
<div class="progress">
  <div class="progress-bar" style="width: 65%;"></div>
</div>
```

---

## ✨ Animation Utilities

All animations use soft easing and gentle curves:

```html
<!-- Fade in -->
<div class="animate-fade-in">Content appears gently</div>

<!-- Slide up -->
<div class="animate-slide-up">Slides up on load</div>

<!-- Pulse animation (infinite) -->
<div class="animate-pulse">Breathing effect</div>

<!-- Scale in -->
<div class="animate-scale-in">Bouncy entrance</div>

<!-- With delay -->
<div class="animate-fade-in animate-delay-200">Delayed by 200ms</div>
```

### Custom Animation Duration

```css
.my-animation {
  animation: fadeIn var(--duration-slow) var(--ease-out-soft);
}

/* Or use utility classes */
.animate-fast { animation-duration: 100ms; }
.animate-default { animation-duration: 200ms; }
.animate-slow { animation-duration: 300ms; }
```

---

## 🔧 Easing Functions

Soft, gentle easing for all animations:

- `--ease-in-soft` — Smooth entrance
- `--ease-out-soft` — Smooth exit
- `--ease-bounce-soft` — Gentle bounce
- `--ease-in-out-smooth` — Balanced motion

---

## 📱 Responsive Design

The design system uses responsive utilities:

```html
<!-- Grid layouts -->
<div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--spacing-lg);">
  <div class="card">Card 1</div>
  <div class="card">Card 2</div>
  <div class="card">Card 3</div>
</div>

<!-- Flexbox utilities -->
<div class="flex flex-between mb-lg">
  <span>Left</span>
  <span>Right</span>
</div>
```

---

## 🎯 Best Practices

### ✅ Do

- Import design tokens first in your stylesheets
- Use CSS custom properties for all colors, spacing, shadows
- Leverage animation utility classes for consistency
- Use semantic color variables (`--color-success`, etc.)
- Test components across different zoom levels (100%, 120%)

### ❌ Don't

- Hardcode colors (use variables instead)
- Create new shadows (use predefined `--shadow-*` variables)
- Use arbitrary durations for animations (use `--duration-*` variables)
- Mix light and dark colors for readability (WCAG AA: 4.5:1 ratio)
- Use neon colors or pure primaries

---

## 📚 Documentation

For complete design guidelines, see:

- **docs/STYLE_GUIDE.md** — Complete design system specification
- **docs/DESIGN_SYSTEM_IMPLEMENTATION_PLAN.md** — 11-week implementation roadmap

---

## 🔮 Next Steps

### Phase 2: Admin Console Redesign
- Apply design tokens to admin dashboard panels
- Redesign buttons, tables, forms, modals
- Integrate ambient animations

### Phase 3: Web Client Redesign
- Redesign chat, inventory, spell bar
- Implement Canvas world rendering with particles
- Mobile-responsive testing

### Phase 4: World Rendering
- Ambient particle system (Canvas-based)
- Lighting and light threads
- Room-specific magical effects

---

## 🐛 Troubleshooting

**Colors look different on my monitor:**
- Use hex values directly from this document
- Verify color space (sRGB recommended)
- Test on multiple monitors for consistency

**Animations feel jerky:**
- Ensure GPU acceleration is enabled
- Only animate `transform` and `opacity`
- Test in Chrome DevTools Performance tab

**Components don't align:**
- Use spacing tokens for all padding/margins
- Never mix `px` and CSS variables in calculations
- Use `box-sizing: border-box` globally (included in components.css)

---

## 📞 Support

For questions about the design system:
1. Check **components-showcase.html** for examples
2. Review **docs/STYLE_GUIDE.md** for specifications
3. Open an issue on GitHub with your question

---

**Version:** 1.0
**Last Updated:** February 26, 2026
**Design System:** surreal_softmagic_v1
