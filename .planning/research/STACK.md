# Technology Stack

**Project:** AmbonMUD Mobile-First Redesign
**Researched:** 2026-04-05

## Existing Stack (No Changes)

These are already in place and remain unchanged. Listed for reference only.

| Technology | Version | Purpose |
|------------|---------|---------|
| React | 19.2.0 | UI component framework |
| PixiJS | 8.16.0 | Canvas/WebGL 2D rendering |
| TypeScript | 5.9.3 | Type safety |
| Vite | 7.3.1 | Build tool and dev server |
| xterm.js | 6.0 | Terminal emulator (retained for chat input fallback) |
| Bun | 1.x+ | Package manager and task runner |

## Recommended New Stack

### Bottom Sheet / Drawer System

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| react-modal-sheet | ^5.6.0 | Bottom sheet panels for all game systems on mobile | Purpose-built bottom sheet with snap points, gesture-driven drag, virtual keyboard avoidance (critical for chat input), compound component API (Container/Content/Header/Backdrop). Built on Motion. Better fit than Vaul because it is designed specifically as a bottom sheet (not a general drawer), has built-in keyboard avoidance on mobile, and supports multi-snap-point behavior needed for peek/half/full panel states. |
| motion | ^12.38.0 | Animation engine (peer dependency of react-modal-sheet) | Required by react-modal-sheet. Also provides `AnimatePresence` for toast enter/exit, layout animations for panel transitions, and spring physics for gesture-driven interactions. Replaces the need for any separate animation library. React 19 compatible. The `motion` package is the renamed framer-motion (import from `motion/react`). |

**Confidence:** HIGH -- react-modal-sheet 5.6.0 verified on npm registry (published April 2026), actively maintained. motion 12.38.0 verified on npm registry. Both support React 19.

### Toast / Notification System

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| sonner | ^2.0.7 | Toast overlay for game events, combat feedback, system messages | Replaces terminal scroll output with brief contextual messages. Zero-config API (`toast.success("You gained 50 XP!")`), callable from anywhere without hooks, stacking with auto-dismiss, TypeScript-first, 48.6 KB gzipped. Chosen over react-hot-toast because Sonner has 11x more weekly downloads, better TypeScript support, and richer built-in toast types (success/error/warning/info/loading/promise). Custom toast rendering supports the "Surreal Gentle Magic" aesthetic via the `className` and custom component props. |

**Confidence:** HIGH -- sonner 2.0.7 verified on npm registry. React 18/19 peer dependency confirmed.

### Gesture Handling

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| @use-gesture/react | ^10.3.1 | Swipe, pinch, long-press detection for React UI components | Provides `useDrag`, `usePinch`, `useLongPress` hooks for swipe-to-dismiss panels, pinch-to-zoom minimap, swipe between chat channels. Works standalone or with motion for animated responses. The only mature React gesture library for web (not React Native). Handles touch vs mouse unification, velocity tracking, and `touchAction` management. |
| use-long-press | ^3.3.0 | Context menus on tap-and-hold (entities, items, NPCs) | Dedicated long-press detection with configurable threshold, `onStart`/`onFinish`/`onCancel` callbacks. Used for showing action menus when holding on canvas entities or list items. Lighter than @use-gesture when you only need press-and-hold (no drag/pinch). |

**Confidence:** MEDIUM -- @use-gesture/react 10.3.1 last published 2 years ago per npm metadata. The library is stable and feature-complete (not abandoned), but verify no breaking issues with React 19 during implementation. use-long-press 3.3.0 updated March 2025.

**PixiJS touch events (no library needed):** For canvas-layer interactions (tapping rooms, entities, compass), use PixiJS 8's built-in pointer event system (`eventMode = 'static'`, `pointertap`, `pointerdown`). No additional library needed for canvas touch -- PixiJS handles it natively with unified pointer events that work across mouse, touch, and pen inputs.

### PWA Capabilities

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| vite-plugin-pwa | ^1.2.0 | Service worker generation, precache manifest, update prompts | Replaces the hand-written `sw.js` with Workbox-powered automatic precaching of all static assets (JS chunks, CSS, images, fonts). Provides `generateSW` mode for zero-config service worker creation, runtime caching strategies (cache-first for assets, network-first for HTML), and `useRegisterSW` hook for React integration (update-available prompts). Compatible with Vite 7.x. The existing manual service worker only caches 2 static assets -- vite-plugin-pwa will auto-precache the entire build output. |
| workbox-window | ^7.4.0 | Service worker lifecycle management in the main thread | Peer dependency of vite-plugin-pwa. Provides `Workbox` class for detecting SW updates, controlling skip-waiting, and triggering reload prompts. |

**Confidence:** HIGH -- vite-plugin-pwa 1.2.0 verified on npm registry, supports Vite 3-7. Workbox 7.4.0 verified.

**Manifest:** The existing `manifest.json` and PWA icons are already in place and well-configured. Only addition needed: `viewport-fit=cover` in the HTML viewport meta tag (for safe-area-inset support on notched devices).

### Mobile Canvas Optimization (No New Dependencies)

No new libraries needed. Optimization is configuration and technique, not library addition.

| Technique | Purpose | How |
|-----------|---------|-----|
| Resolution capping | Prevent 3x DPI devices from creating massive canvases | `Math.min(window.devicePixelRatio, 2)` in PixiJS init |
| Sprite sheets | Reduce draw calls and texture swaps | Bundle entity/UI sprites into texture atlases |
| `eventMode = 'none'` | Skip hit testing on non-interactive elements | Set on decorative sprites, background layers, particle containers |
| Conditional antialiasing | Save GPU on mobile | `antialias: false` when `devicePixelRatio >= 2` (retina already smooths) |
| `autoDensity: true` | Match CSS pixel sizing | Already set in existing PixiCanvas.tsx |

**Confidence:** HIGH -- All techniques documented in official PixiJS 8 performance guide.

### CSS Mobile Patterns (No New Dependencies)

| Technique | Purpose | How |
|-----------|---------|-----|
| `env(safe-area-inset-*)` | Avoid notch/home indicator overlap | Padding on bottom nav bar, canvas container margins |
| `viewport-fit=cover` | Enable safe-area environment variables | Add to existing viewport meta tag |
| CSS container queries | Component-level responsive behavior | `container-type: inline-size` on panel wrappers, `@container` for adaptive layouts |
| `touch-action: manipulation` | Disable double-tap-to-zoom on interactive areas | Apply to game UI areas, keep default on chat/text inputs |
| `-webkit-overflow-scrolling: touch` | Momentum scrolling in panels | Apply to scrollable panel content areas |
| `overscroll-behavior: contain` | Prevent pull-to-refresh and scroll chaining | Apply to bottom sheet content, game canvas |

**Confidence:** HIGH -- All CSS features have 90%+ browser support per caniuse.com.

### Haptic Feedback (No New Dependencies)

| API | Purpose | Limitation |
|-----|---------|------------|
| `navigator.vibrate()` | Tap feedback on combat hits, level-up, achievement unlock | Not supported on iOS Safari. Android-only. Use as progressive enhancement, never as a required feedback mechanism. |

**Confidence:** HIGH -- Web Vibration API is a W3C standard. iOS non-support is well-documented.

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Bottom sheet | react-modal-sheet | **Vaul** (1.1.2) | Vaul is a general-purpose drawer, not optimized for bottom sheets. No built-in keyboard avoidance. No multi-snap-point support out of the box. Depends on Radix UI Dialog which adds bundle weight for features we don't need (focus trapping for accessibility is handled differently in game UIs). |
| Bottom sheet | react-modal-sheet | **Base UI Drawer** | Part of the Base UI library -- would pull in a larger dependency for one component. react-modal-sheet is purpose-built and lighter. |
| Toast | sonner | **react-hot-toast** | 11x fewer weekly downloads, weaker TypeScript support, less flexible custom rendering. The 5 KB size advantage doesn't justify the DX gap. |
| Toast | sonner | **Custom implementation** | Toast systems look simple but handling stacking, auto-dismiss timers, swipe-to-dismiss, accessible announcements, and animation exit is substantial. Sonner handles all of this. |
| Animation | motion | **CSS animations only** | CSS alone cannot drive gesture-aware spring physics needed for bottom sheet drag interactions, or layout animations for panel transitions. motion is already a required dependency of react-modal-sheet. |
| Animation | motion | **react-spring** | motion is more mature, has a simpler API, larger ecosystem, and is already required by react-modal-sheet. Adding react-spring would mean two animation libraries. |
| Gesture | @use-gesture/react | **Hammer.js** | Hammer.js is unmaintained (last release 2016). @use-gesture is the standard React gesture library with hooks API. |
| Gesture | @use-gesture/react | **Native pointer events only** | Raw pointer events require manual velocity calculation, swipe detection thresholds, and touch/mouse unification. @use-gesture encapsulates all of this. |
| PWA | vite-plugin-pwa | **Keep hand-written sw.js** | The current SW only caches 2 assets. Workbox-based generation auto-precaches the entire build, handles cache versioning, and provides runtime caching strategies. Manual SW maintenance doesn't scale. |
| PWA | vite-plugin-pwa | **@vite-pwa/assets-generator** | Optional companion for generating PWA icons from a source SVG. Not needed -- icons are already created manually and exist in `web-v3/public/icons/`. |
| React-PixiJS | Imperative (current) | **@pixi/react** (8.0.5) | The existing codebase uses imperative PixiJS with a SceneManager class hierarchy. Migrating to @pixi/react's declarative JSX model would require rewriting all canvas code for no gameplay benefit. The imperative approach is also more performant for complex scenes. Keep the current approach. |
| Chat UI | Custom implementation | **Stream Chat SDK** | External service dependency, subscription cost, and the chat system is simple (channel-based text messaging over existing WebSocket). A messenger-style UI is straightforward to build with React + motion. |

## Installation

```bash
# New production dependencies
bun add react-modal-sheet motion sonner @use-gesture/react use-long-press

# New dev dependency (PWA)
bun add -D vite-plugin-pwa
```

## Configuration Notes

### vite-plugin-pwa setup in vite.config.ts

```typescript
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      workbox: {
        globPatterns: ["**/*.{js,css,html,ico,png,svg,woff2}"],
        navigateFallback: "/",
        runtimeCaching: [
          {
            urlPattern: /^https:\/\/fonts\.googleapis\.com/,
            handler: "StaleWhileRevalidate",
            options: { cacheName: "google-fonts-stylesheets" },
          },
          {
            urlPattern: /^https:\/\/fonts\.gstatic\.com/,
            handler: "CacheFirst",
            options: {
              cacheName: "google-fonts-webfonts",
              expiration: { maxEntries: 10, maxAgeSeconds: 60 * 60 * 24 * 365 },
            },
          },
        ],
      },
      manifest: false, // Use existing manifest.json
    }),
  ],
});
```

### Viewport meta tag update (index.html)

```html
<!-- Add viewport-fit=cover for safe-area-inset support -->
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
```

### motion import pattern

```typescript
// Use the new package name, not framer-motion
import { motion, AnimatePresence } from "motion/react";
```

## Bundle Impact Assessment

| Package | Gzipped Size (approx) | Tree-Shakeable | Notes |
|---------|----------------------|----------------|-------|
| motion | ~35 KB | Yes | Core animations only; full bundle larger but tree shaking is effective |
| react-modal-sheet | ~8 KB | Yes | Thin wrapper over motion |
| sonner | ~12 KB | Yes | Self-contained toast system |
| @use-gesture/react | ~10 KB | Yes | Individual hooks importable |
| use-long-press | ~2 KB | Yes | Single hook |
| vite-plugin-pwa | 0 KB runtime | N/A | Build-time only; generates SW at build |
| **Total new runtime** | **~67 KB gzipped** | | Reasonable for the functionality gained |

The existing bundle has PixiJS (~200 KB gzipped) and xterm.js (~150 KB gzipped) as the heavy dependencies. Adding ~67 KB for a complete mobile interaction layer is proportional.

## Sources

- [react-modal-sheet npm registry](https://www.npmjs.com/package/react-modal-sheet) -- version 5.6.0 confirmed
- [react-modal-sheet GitHub](https://github.com/Temzasse/react-modal-sheet) -- virtual keyboard avoidance, compound component API
- [motion npm registry](https://www.npmjs.com/package/motion) -- version 12.38.0 confirmed, React 19 support
- [Motion docs](https://motion.dev/docs/react) -- framer-motion renamed to motion
- [sonner npm registry](https://www.npmjs.com/package/sonner) -- version 2.0.7 confirmed
- [sonner docs](https://sonner.emilkowal.ski/) -- API reference
- [LogRocket toast comparison](https://blog.logrocket.com/react-toast-libraries-compared-2025/) -- sonner vs react-hot-toast analysis
- [@use-gesture/react npm registry](https://www.npmjs.com/package/@use-gesture/react) -- version 10.3.1
- [@use-gesture docs](https://use-gesture.netlify.app/) -- gesture hooks reference
- [use-long-press npm registry](https://www.npmjs.com/package/use-long-press) -- version 3.3.0
- [vite-plugin-pwa npm registry](https://www.npmjs.com/package/vite-plugin-pwa) -- version 1.2.0, Vite 7.x support
- [Vite PWA docs](https://vite-pwa-org.netlify.app/) -- configuration reference
- [PixiJS 8 Events guide](https://pixijs.com/8.x/guides/components/events) -- pointer event system
- [PixiJS 8 Performance Tips](https://pixijs.com/8.x/guides/concepts/performance-tips) -- mobile optimization
- [Vaul npm registry](https://www.npmjs.com/package/vaul) -- version 1.1.2, evaluated and rejected
- [MDN Vibration API](https://developer.mozilla.org/en-US/docs/Web/API/Vibration_API) -- iOS non-support documented
- [MagicBell PWA iOS Limitations](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide) -- iOS PWA limitations 2026
- [caniuse CSS container queries](https://caniuse.com/css-container-queries) -- 90%+ browser support
- [MDN safe-area-inset](https://developer.mozilla.org/en-US/docs/Web/CSS/env) -- env() safe area variables
