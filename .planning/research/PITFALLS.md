# Domain Pitfalls

**Domain:** Mobile-first RPG web client (desktop MUD to tap-first game UI conversion)
**Researched:** 2026-04-05

## Critical Pitfalls

Mistakes that cause rewrites, major performance failures, or broken mobile experiences.

### Pitfall 1: PixiJS VRAM Exhaustion Crashes on iOS Safari

**What goes wrong:** PixiJS 8 has a known VRAM management regression compared to v7. Textures created as Sprites consume excessive GPU memory. On iOS Safari, where available VRAM is significantly less than desktop or Android, the browser tab crashes silently -- no error, just a dead white screen. This is documented in [pixijs/pixijs#11331](https://github.com/pixijs/pixijs/issues/11331). The existing WorldScene already creates per-mob sprites, per-item sprites, room background sprites, badge sprites (shop, trainer, bank, tavern, station), dialogue/aggro/quest indicator sprites, and multiple overlay systems (AmbientMotes, RoomTransition, CombatAnimator, StatusEffectDisplay). Adding more tap-target sprites, bottom sheet panel content, and context menus will increase texture count substantially.

**Why it happens:** PixiJS v8's default TextureGCSystem only collects textures unused for 3600 frames (~60 seconds at 60fps). On mobile, rooms change frequently and each room loads new background images, mob sprites, and item sprites. If the player navigates quickly through 10+ rooms, all those textures remain in VRAM for a full minute before GC runs. iOS Safari has roughly 200-400MB VRAM budget for WebGL contexts; a single RenderTexture can consume ~75MB.

**Consequences:** App crashes with no recovery on iPhone. Users see a blank white page and must fully close and reopen the PWA. No error is surfaceable. This is the single most likely cause of "the game doesn't work on my phone" reports.

**Prevention:**
1. Configure aggressive TextureGCSystem settings: `textureGCMaxIdle: 300` (5 seconds at 60fps), `textureGCCheckCountMax: 60` (check every second)
2. Call `texture.unload()` explicitly when leaving rooms for background images and mob sprites not carried to the new room
3. Implement a texture budget tracker that monitors approximate VRAM usage and triggers early cleanup when approaching a threshold (~150MB)
4. Use sprite sheets instead of individual textures for mob/item sprites -- batching up to 16 textures per draw call
5. Cap `resolution` at `Math.min(window.devicePixelRatio, 2)` to prevent 3x retina devices from tripling VRAM usage
6. Test on a real iPhone SE or iPhone 12 Mini (lowest VRAM tier) -- simulators do not reproduce VRAM limits

**Detection:** Monitor `app.renderer.texture.managedTextures.length` and log it. If texture count exceeds 200 between room transitions, VRAM is accumulating. Safari crashes are silent -- can only be detected by absence (user stops sending GMCP, WebSocket closes unexpectedly).

**Phase relevance:** Must be addressed in the very first phase that touches canvas rendering for mobile. Retrofitting texture management after building 14 panels with tap targets is extremely painful.

---

### Pitfall 2: iOS Safari WebSocket Death on Background/Lock Screen

**What goes wrong:** iOS aggressively suspends web apps when backgrounded or when the screen locks. WebSocket connections are silently dropped -- not closed cleanly, just killed at the TCP level. The existing `useMudSocket` hook has a basic `visibilitychange` listener and `reconnect()` call, but it does not handle: (a) game state that drifted while the player was away (combat could have ended, mobs could have respawned, room could have changed), (b) the 120ms reconnection delay being too short for iOS to fully restore networking, (c) no exponential backoff if the first reconnection fails, (d) no state resync after reconnection.

**Why it happens:** iOS does not allow arbitrary long-running TCP/WebSocket activity in the background. The OS suspends the web process within seconds of backgrounding. When the user returns, the WebSocket object still exists in JS memory but the underlying TCP connection is dead. The server's `SessionGracePeriodManager` has a grace window, but the client must reconnect before it expires.

**Consequences:** Players return from a phone call or notification to find the game frozen. The reconnect may succeed at the WebSocket level but the client's React state is stale -- showing the old room, old combat state, old HP values. Without a full state resync, the UI is desynchronized from the server until the next GMCP push for each individual package. Some packages (Room.Info, Char.Vitals) update frequently; others (Guild.Info, Quest.Log) may never update until the player triggers them.

**Prevention:**
1. On `visibilitychange` back to "visible", implement exponential backoff reconnection: 500ms, 1s, 2s, 4s, capped at 15s, max 10 attempts
2. After successful reconnection, send GMCP requests for all critical state: `Core.Hello`, then trigger server-side "full refresh" by sending a look command or implementing a `Core.Resync` GMCP package
3. Show a reconnection overlay during the process so the user knows state is refreshing
4. Use the server's existing `SessionGracePeriodManager` grace window (configure it to at least 120 seconds for mobile)
5. Queue user actions during reconnection and replay them after state is synced
6. Pause the PixiJS ticker during background via Page Visibility API to prevent animation state drift

**Detection:** Track reconnection frequency per session. If a session reconnects more than 3 times in 5 minutes, the grace period or reconnection logic is likely misconfigured. Monitor the server-side `fullDisconnect` rate vs `gracePeriodResume` rate.

**Phase relevance:** Must be solved before any PWA work. A PWA that drops connections every time the user checks a text message is unusable.

---

### Pitfall 3: xterm.js Is Fundamentally Broken on Mobile

**What goes wrong:** The existing client uses xterm.js (`@xterm/xterm` v6) for terminal rendering and command input. xterm.js has [documented, unfixed issues on mobile](https://github.com/xtermjs/xterm.js/issues/5377): touch support is described as "limited", predictive keyboard text appears ahead of the cursor, backspace behaves counter-intuitively with IME composition, and the mobile keyboard's auto-suggest/auto-correct layer conflicts with terminal input semantics. On iOS, Smart Keyboard arrow events fire inconsistently. On Android, keyboard input is buffered and sometimes requires pressing Enter twice.

**Why it happens:** xterm.js is designed for desktop terminal emulation. Its input handling is built around `keydown`/`keypress` events, not touch events or composition events from mobile IMEs. The `CoreBrowserTerminal.ts` has no dedicated touch event handling.

**Consequences:** Keeping xterm.js as the primary input method on mobile makes the app feel broken. Players will type text that appears garbled, delete the wrong characters, and struggle with basic command entry. This single issue can make the entire mobile experience feel unprofessional regardless of how good the rest of the UI is.

**Prevention:**
1. Do NOT use xterm.js for input on mobile. Replace with a standard HTML `<input>` or `<textarea>` element for the chat/command input in the messenger-style chat UI
2. Keep xterm.js only for the optional terminal overlay on desktop (for power users who want raw MUD output)
3. On mobile, intercept all gameplay through tap-based UI that sends commands via GMCP/WebSocket -- the player should never need to type game commands
4. For the chat bubble UI (the one place users DO type), use a native `<input>` with `enterkeyhint="send"` and proper mobile keyboard management
5. Consider making xterm.js a lazy-loaded module that is never downloaded on mobile at all, saving ~200KB of bundle size

**Detection:** Test with a real Android phone and real iPhone (not browser DevTools device simulation, which does not emulate IME behavior). If a tester cannot type "say hello" and press Enter on the first try, the input is broken.

**Phase relevance:** This must be designed into phase 1 of the mobile redesign. The entire architecture of "terminal as primary interface" vs "tap UI with optional terminal" must be decided before building any panels.

---

### Pitfall 4: Virtual Keyboard Destroys Layout on Mobile

**What goes wrong:** When the virtual keyboard opens on mobile (user taps the chat input), it resizes the viewport. On iOS Safari, the viewport scrolls up to keep the input visible, pushing fixed-position elements off screen. On Android Chrome, the keyboard overlays the content by default. The existing CSS uses `100dvh` for `.app-shell` height (line 6879 of styles.css), which is correct for the address bar but does not account for the keyboard. Fixed-position elements like the action bar, HUD overlay, compass, and bottom sheet all break when the keyboard appears.

**Why it happens:** Mobile browsers handle virtual keyboards differently:
- iOS Safari: scrolls the document up, `100dvh` does not change, fixed elements stay at their original positions (now off-screen above the keyboard)
- Android Chrome (default): overlays keyboard on top of content, `100dvh` does not change, content behind keyboard is hidden
- Android Chrome with `interactive-widget=resizes-content`: resizes viewport, `100dvh` shrinks, fixed elements reposition correctly but the canvas/game area shrinks dramatically

**Consequences:** The game UI jumps, resizes, or becomes unusable when the chat input is focused. The PixiJS canvas may resize triggering expensive re-renders. The bottom sheet may be pushed behind the keyboard. The HUD stats may disappear. Players learn to avoid the chat input because it "breaks the game."

**Prevention:**
1. Use the VirtualKeyboard API where supported: `navigator.virtualKeyboard.overlaysContent = true` and use `env(keyboard-inset-height)` CSS variable to adjust only the chat area
2. Set `<meta name="viewport" content="..., interactive-widget=overlays-content">` so the keyboard does not resize the viewport on any platform
3. When chat input gains focus, transition the bottom sheet to a "chat mode" that slides up above the keyboard using `visualViewport.height` to calculate positioning
4. Do NOT resize the PixiJS canvas when the keyboard opens -- let the canvas stay behind the keyboard; only the chat panel needs to adjust
5. Debounce resize events from keyboard open/close (iOS fires multiple resize events in sequence) with at least 150ms debounce
6. Test with `visualViewport` API: `window.visualViewport.addEventListener('resize', ...)` to detect keyboard open/close without relying on window.innerHeight

**Detection:** Focus the chat input on a real iPhone SE. If any element jumps, resizes, or disappears, the keyboard handling is broken. Automated testing cannot catch this -- it requires real device testing or BrowserStack.

**Phase relevance:** Must be solved when building the chat bubble UI. If chat is built without keyboard management, the entire bottom sheet system will need to be re-architected later.

---

### Pitfall 5: Notification/Toast Fatigue in Combat-Heavy RPGs

**What goes wrong:** The project plan calls for replacing scrolling terminal output with toast notifications. In a MUD, combat generates 2-10 events per tick (100ms ticks): damage dealt, damage received, dodge, parry, spell cast, spell effect, status effect applied, status effect expired, mob death, XP gain, loot drop. That is potentially 20-100 toast notifications per second during active combat. Even at 5 per second, the screen becomes an unreadable wall of overlapping toasts that obscure the game canvas, drain mobile GPU performance (CSS animations), and cause the React reconciler to thrash with constant mount/unmount cycles.

**Why it happens:** Desktop terminal scrollback works because text scrolls and the eye tracks it linearly. Toasts are designed for infrequent, discrete events (1-2 per minute in typical apps). Mapping terminal output 1:1 to toasts misunderstands both the volume of MUD output and the purpose of toast notifications.

**Consequences:** Players cannot play during combat because the screen is covered in toasts. Performance drops as dozens of animated elements are created and destroyed per second. Players disable notifications entirely (if that option exists) and then miss important non-combat messages.

**Prevention:**
1. Create a combat event aggregation system that batches combat events per tick: "You dealt 45 damage (3 hits). Goblin dealt 12 damage to you. You dodged 2 attacks." -- one summary toast per tick, not one per event
2. Use the existing `CombatLog` component (already in the codebase) as an inline scrollable area within the combat panel, not as toasts
3. Reserve toasts exclusively for discrete, important events: level up, quest complete, achievement unlocked, item received, player whisper. Cap at 3 visible toasts maximum
4. Implement toast priority levels: URGENT (always show, e.g. "You died"), HIGH (show if under cap, e.g. "Level up!"), LOW (queue and show when space available, e.g. "You received 5 gold")
5. Use floating combat numbers (the existing `GainPopup` and `CombatAnimator` systems in the canvas) for damage/heal numbers instead of toasts -- these are already built and work on mobile
6. Use `will-change: transform` on toast elements and limit animation to `transform` and `opacity` only -- no `height`, `top`, or `margin` animations

**Detection:** Enter combat with 3+ mobs on a mid-range phone. If the frame rate drops below 20fps during combat, or if more than 3 toasts are visible simultaneously, the notification system is over-emitting.

**Phase relevance:** The toast/notification architecture must be designed before any panel conversion. If panels are built assuming they can emit toasts freely, retrofitting aggregation requires touching every panel.

---

### Pitfall 6: Tap Target Size and Density on Small Screens

**What goes wrong:** The existing WorldScene renders mob sprites at 128px base size (scaled relative to 1200x800 reference), item sprites at 96px, and has click targets on entity labels, role icons (12px diameter), quest icons (28px), and compass directions. On a 375px-wide phone, multiple mob sprites overlap, their labels collide, and tap targets become smaller than Apple's 44x44pt minimum or Material Design's 48x48dp minimum. The EntityPopout buttons are 44px tall (line 8 of EntityPopout.ts) but the popout itself is 240px wide -- consuming 64% of a 375px screen.

**Why it happens:** The canvas layout was designed for a 1200x800 reference viewport. Scaling down to 375x667 (iPhone SE) means sprites, labels, and hit areas shrink by ~69%. A 12px role icon becomes 4px -- untappable. The entity popout that fits comfortably on desktop blocks most of the game on mobile.

**Consequences:** Players tap the wrong mob, can't tap small items at all, accidentally trigger navigation when trying to interact with entities, and find the entity popout covers the entities they're trying to interact with.

**Prevention:**
1. Set minimum sprite sizes on mobile: `MIN_SPRITE_SIZE` should be 80px (not 64px as currently coded) on screens under 600px
2. All hit areas for tappable elements must be at least 44x44 CSS pixels, regardless of visual size -- use invisible expanded hit areas (`hitArea` Graphics with padding)
3. When entities overlap on mobile, show a disambiguation menu ("Which target? [Goblin Guard] [Goblin Scout]") rather than stacking tiny sprites
4. EntityPopout on mobile should be a bottom sheet (full width) rather than a 240px popout anchored to the entity
5. Compass rose on mobile should use large, padded directional buttons (at least 48x48 each) rather than a scaled-down rose diagram
6. Keep 16px minimum distance from screen edges to avoid conflicts with iOS swipe-back gesture and Android system navigation gestures
7. Items on the ground should be represented as a tappable list badge (e.g., "3 items") rather than individual tiny sprites on mobile

**Detection:** Use Chrome DevTools "Show touch areas" debug overlay, or tap-test on a real iPhone SE (smallest common iPhone). If any tappable element requires precision aiming, it's too small.

**Phase relevance:** Every phase that adds tappable elements must enforce minimum sizes. Establish the size constants and hit area patterns in phase 1 so all subsequent phases inherit them.

---

## Moderate Pitfalls

### Pitfall 7: devicePixelRatio 3x Kills Performance and Memory

**What goes wrong:** The existing PixiJS setup uses `resolution: window.devicePixelRatio || 1`. On modern phones (iPhone 15: 3x, Samsung Galaxy S24: 3x, Pixel 8: 2.625x), this means the canvas is rendered at 3x resolution. A 375x812 iPhone screen becomes a 1125x2436 pixel canvas -- 2.7 million pixels per frame. Combined with antialias:true, this can halve the frame rate on mid-range devices.

**Prevention:**
1. Cap resolution at 2x on mobile: `resolution: Math.min(window.devicePixelRatio, 2)`
2. Disable `antialias` on mobile (PixiJS 8 performance tips recommend this for older devices, and even newer phones don't benefit visually at 2-3x resolution)
3. Provide a quality setting in the UI that lets players choose between "crisp" (2x, antialias) and "smooth" (1x, no antialias) -- the existing PerformanceDashboard with QualitySettings presets should drive this
4. Monitor frame time and auto-downgrade resolution if average frame time exceeds 25ms (below 40fps)

**Detection:** Check `app.ticker.FPS` on a Pixel 6a or similar mid-range device. If it's consistently below 30fps in the world scene (no combat), resolution is too high.

**Phase relevance:** Address in the first phase that enables mobile canvas rendering.

---

### Pitfall 8: Service Worker Cache Traps Users on Stale Versions

**What goes wrong:** The project plans PWA support. Service workers cache the app shell for offline/installable behavior. If a broken deployment is cached, users get stuck on the broken version because the `service-worker.js` file itself may be cached for up to 24 hours. There is no current service worker in the web client -- this is greenfield -- but the risk is highest when first implementing it.

**Prevention:**
1. Set `Cache-Control: max-age=0, no-cache` on the `service-worker.js` file in the server response headers
2. Use cache versioning: increment a version string in the service worker on every deploy, and clean up old caches in the `activate` event
3. Implement an update notification: when `registration.waiting` is set (new SW waiting), show a toast "Update available -- tap to refresh" that calls `skipWaiting()` and reloads
4. Provide a manual "Check for updates" button in settings as an escape hatch
5. Use `stale-while-revalidate` for static assets (JS bundles, CSS) and `network-first` for API responses and WebSocket negotiation -- never cache WebSocket upgrade requests
6. Do NOT cache-first the GMCP or WebSocket transport -- only the app shell and static assets

**Detection:** Deploy a new version and check on a phone that has the PWA installed. If the old version persists after closing and reopening the app, cache invalidation is broken.

**Phase relevance:** Must be designed correctly in the PWA implementation phase. Retrofitting cache invalidation after users are stuck on broken versions is painful.

---

### Pitfall 9: Bottom Sheet Gesture Conflicts with System UI

**What goes wrong:** Bottom sheets use vertical swipe gestures to open, expand, and dismiss. These conflict with: (a) iOS Control Center swipe-down from top-right, (b) iOS notification center swipe-down from top-left, (c) Android notification shade swipe-down, (d) browser address bar reveal swipe-down, (e) iOS home indicator swipe-up, (f) Android gesture navigation swipe-up. Additionally, scrollable content inside a bottom sheet creates ambiguity: is the user swiping to scroll the sheet content or to dismiss the sheet?

**Prevention:**
1. Use a visible, large (44px tall) grab handle on the bottom sheet with clear visual affordance
2. Implement snap points (collapsed, half, full) rather than free-form dragging -- this makes the gesture intent clearer
3. Only allow sheet dismissal via the grab handle area, not from swiping anywhere on the sheet
4. Inside the sheet, disable sheet gesture recognition while content is scrolled away from top -- only re-enable sheet dismiss gesture when scrolled to top
5. Add `padding-top: env(safe-area-inset-top)` and `padding-bottom: env(safe-area-inset-bottom)` to ensure sheet content doesn't overlap with device notch or home indicator
6. Set `viewport-fit=cover` in the viewport meta tag to enable safe area insets
7. Test on iPhone with gesture navigation (no home button) and on Android with 3-button vs gesture navigation

**Detection:** Open a bottom sheet with scrollable content (e.g., inventory with 20+ items). Try to scroll to the bottom of the list. If the sheet dismisses instead of scrolling, the gesture detection is wrong.

**Phase relevance:** Must be solved when building the bottom sheet component. All 14 panels depend on it.

---

### Pitfall 10: React Re-render Storms from High-Frequency GMCP Updates

**What goes wrong:** The MUD server sends GMCP updates every tick (100ms) during combat: `Char.Vitals`, `Combat.Update`, `Room.UpdateMob` (for each mob's HP), status effect changes. Each GMCP update calls `applyGmcpPackage` which calls React setState, which triggers a re-render. On desktop, React can handle 10 state updates per second. On a mid-range phone with React 19 concurrent mode, the reconciler still struggles if each update touches the top-level App component's state (which it currently does -- all game state lives in App.tsx's useState calls).

**Prevention:**
1. Move high-frequency state (vitals, combat data, mob positions) into refs or a game state store (e.g., Zustand or a simple pub/sub) that bypasses React's render cycle entirely
2. The existing `gameStateRef` pattern (used for canvas) is correct -- extend it to all combat/vitals state
3. Only trigger React re-renders for user-visible panel changes (panel open/close, new chat message, quest update)
4. Batch GMCP updates per animation frame: accumulate all GMCP received in one frame, apply them all, then trigger one re-render
5. Use `React.memo` aggressively on all panel components with proper dependency arrays
6. Profile with React DevTools Profiler on a real phone -- if any component re-renders more than 10 times per second, it needs optimization

**Detection:** Open React DevTools Profiler, enter combat with 3 mobs. If total render count exceeds 10/second or any individual render takes >16ms, state management needs restructuring.

**Phase relevance:** The state management architecture should be established in phase 1. Retrofitting state management after 14 panels are built requires touching every component.

---

### Pitfall 11: PWA on iOS Safari in the EU is Broken

**What goes wrong:** As of iOS 17.4, Apple removed standalone PWA support in EU countries under the Digital Markets Act. PWAs in the EU open in Safari tabs with no push notification support, no full-screen mode, and no home screen icon behavior. This affects all EU users regardless of the developer's location.

**Prevention:**
1. Design the mobile experience to work well as a regular browser tab, not just as an installed PWA
2. Do not rely on PWA-only features (push notifications, badge API) for core gameplay -- use in-app notifications via WebSocket
3. Test the app in Safari without "Add to Home Screen" to ensure it functions correctly
4. Consider a thin native wrapper (Capacitor/Ionic) as a fallback for EU users who want app-like experience
5. Document this limitation prominently so EU users are not confused when "Install App" doesn't behave as expected

**Detection:** Test from an EU IP address (or VPN) on an iPhone. If "Add to Home Screen" opens in a Safari tab instead of standalone mode, EU restrictions are active.

**Phase relevance:** PWA implementation phase. Must be acknowledged in the PWA manifest and installation flow design.

---

### Pitfall 12: Canvas and React Fighting for the GPU

**What goes wrong:** PixiJS renders to a WebGL canvas on every frame via requestAnimationFrame. React also schedules DOM updates via requestAnimationFrame (in concurrent mode). On mobile, both compete for the same GPU and main thread. The PixiJS ticker runs at 60fps by default. React concurrent mode may interrupt PixiJS rendering for high-priority DOM updates. This causes visual stuttering where the canvas freezes for 50-100ms while React processes a state update.

**Prevention:**
1. Throttle the PixiJS ticker to 30fps on mobile to leave headroom for React: `app.ticker.maxFPS = 30`
2. Use `requestIdleCallback` for non-urgent React state updates
3. Ensure PixiJS canvas updates are independent of React renders -- the existing `GameStateBridge` pattern (canvas reads from refs, not React state) is correct and must be maintained
4. Pause the PixiJS ticker when a full-screen bottom sheet is open (canvas is not visible anyway)
5. Use the Page Visibility API to stop the ticker entirely when backgrounded -- the existing code does NOT do this

**Detection:** Open a bottom sheet panel while in combat. If the panel's scroll animation stutters or the canvas has visible frame drops, GPU contention is occurring.

**Phase relevance:** Phase 1 canvas optimization.

---

## Minor Pitfalls

### Pitfall 13: Missing Safe Area Insets for Notched/Punch-Hole Devices

**What goes wrong:** The existing CSS has one `env(safe-area-inset-bottom)` usage on the action bar (line 6910). No other elements account for safe area insets. On iPhones with Dynamic Island, the top 59px is a non-rectangular safe area. On phones with punch-hole cameras, the top-right corner is obstructed. The HUD overlay, compass, and minimap currently position at fixed offsets from edges without safe area consideration.

**Prevention:**
1. Add `viewport-fit=cover` to the viewport meta tag
2. Wrap all edge-positioned elements with `padding: env(safe-area-inset-*)` using the `max()` function pattern: `padding-top: max(8px, env(safe-area-inset-top))`
3. Create CSS utility classes: `.safe-top`, `.safe-bottom`, `.safe-left`, `.safe-right` for consistent application
4. Test on iPhone 14 Pro or newer (Dynamic Island) and Samsung Galaxy S24 (punch-hole camera)

**Detection:** Run on an iPhone with Dynamic Island. If any UI element overlaps with the Dynamic Island area, safe area insets are missing.

**Phase relevance:** Every phase that positions UI elements near screen edges.

---

### Pitfall 14: Orientation Change Breaks Canvas and Layout

**What goes wrong:** When a phone rotates from portrait to landscape (or vice versa), the PixiJS canvas must resize, the React layout must reflow, and the bottom sheet snap points must recalculate. The existing `ResizeObserver` in `PixiCanvas.tsx` handles canvas resize, but if the canvas resize triggers texture re-creation (background images, sprite scaling), the transition will be visually jarring with a flash of incorrect layout.

**Prevention:**
1. Debounce orientation change handling by 300ms to allow the browser to settle
2. Show a brief transition overlay during orientation change to mask the reflow
3. Lock to portrait via PWA manifest if the game doesn't benefit from landscape: `"orientation": "portrait-primary"`
4. If supporting both orientations, pre-compute layout for both and swap instantly rather than recalculating

**Detection:** Rotate a phone during active gameplay. If the layout takes more than 500ms to settle, or if there's a visible flash of wrong-sized content, orientation handling needs work.

**Phase relevance:** Layout/responsive design phase.

---

### Pitfall 15: Bundle Size Blocks First Load on Slow Networks

**What goes wrong:** The current client includes React 19 (~45KB gzipped), PixiJS 8 (~150KB gzipped), xterm.js (~120KB gzipped), and application code. Total bundle size is likely 400-500KB gzipped. On a 3G connection (750KB/s typical), this is a 0.5-0.7 second download -- acceptable. But adding bottom sheet libraries, animation libraries, service worker code, and 14 panel components could push it to 800KB+. The PWA's perceived first-load time directly determines whether users "install" or bounce.

**Prevention:**
1. Lazy-load all panel components -- only the initially visible UI (canvas, HUD, action bar) should be in the main bundle
2. Code-split xterm.js into a separate chunk that loads only when the terminal overlay is opened
3. Use PixiJS tree-shaking (v8 supports it) to exclude unused features (e.g., if not using WebGPU renderer, exclude it)
4. Set a bundle size budget: main chunk < 200KB gzipped, total lazy chunks < 400KB
5. Measure with Lighthouse on "Slow 4G" preset -- Time to Interactive should be under 3 seconds

**Detection:** Run `bun run build` and check output chunk sizes. If main chunk exceeds 200KB gzipped, investigate what's included.

**Phase relevance:** Every phase that adds dependencies or components. Set up bundle analysis in phase 1.

---

### Pitfall 16: Battery Drain from Continuous Animation

**What goes wrong:** The PixiJS ticker runs at 60fps continuously, even when the player is idle (standing in a room, reading chat). AmbientMotes spawn particles at 6/second (up to 60 active). StatusEffectDisplay, CombatAnimator, and RoomTransition all tick every frame. On mobile, continuous 60fps rendering drains battery noticeably -- users will close the app to save battery.

**Prevention:**
1. Implement idle detection: if no player input for 30 seconds and no combat, reduce ticker to 10fps
2. Reduce ambient mote count on mobile: 20 max instead of 60, spawn rate 2/sec instead of 6/sec
3. When the bottom sheet fully covers the canvas, stop the ticker entirely
4. Respect `prefers-reduced-motion` (the AmbientMotes system already does this) but extend it to ALL animation systems
5. iOS Low Power Mode throttles rAF -- detect this via reduced frame rate and auto-switch to minimal animation preset

**Detection:** Play for 30 minutes on a phone and check battery usage in system settings. If the PWA appears in the top 5 battery consumers, animation is too aggressive.

**Phase relevance:** Canvas optimization phase, but the idle detection pattern should be established early.

---

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Canvas mobile optimization | VRAM crash on iOS (Pitfall 1), DPI scaling (7), battery drain (16) | Cap resolution at 2x, aggressive texture GC, idle throttling |
| Bottom sheet panel system | Gesture conflicts (9), keyboard layout break (4), safe areas (13) | Snap points, grab handle only dismiss, safe-area padding everywhere |
| Toast/notification system | Notification fatigue (5) | Combat event aggregation, 3-toast cap, use canvas floating numbers for combat |
| Chat bubble UI | Keyboard viewport (4), xterm.js mobile failure (3) | Native HTML input, VirtualKeyboard API, overlays-content mode |
| PWA implementation | Service worker cache (8), EU restrictions (11) | Versioned caches, stale-while-revalidate, test as browser tab too |
| Tap-based combat UI | Tap target density (6), React re-render storms (10) | 44px min targets, entity disambiguation, ref-based combat state |
| State management refactor | GMCP update frequency (10), reconnection state sync (2) | Game state store outside React, animation-frame batching |
| Responsive layout | Orientation change (14), safe areas (13), mobile shrinkage (6) | Portrait lock or debounced reflow, env() safe-area on all edges |
| Performance optimization | Canvas/React GPU contention (12), DPI (7), battery (16) | 30fps mobile cap, pause ticker when covered, lazy-load panels |
| Bundle optimization | First load time (15) | Lazy-load panels and xterm.js, tree-shake PixiJS, size budget |

## Sources

- [PixiJS 8 Performance Tips](https://pixijs.com/8.x/guides/concepts/performance-tips) -- Official documentation (HIGH confidence)
- [PixiJS 8 Garbage Collection](https://pixijs.com/8.x/guides/concepts/garbage-collection) -- Official documentation (HIGH confidence)
- [PixiJS VRAM regression issue #11331](https://github.com/pixijs/pixijs/issues/11331) -- First-party bug report (HIGH confidence)
- [PixiJS Graphics memory leak #10586](https://github.com/pixijs/pixijs/issues/10586) -- First-party bug report (HIGH confidence)
- [xterm.js mobile touch support #5377](https://github.com/xtermjs/xterm.js/issues/5377) -- First-party issue (HIGH confidence)
- [xterm.js predictive keyboard #2403](https://github.com/xtermjs/xterm.js/issues/2403) -- First-party issue (HIGH confidence)
- [PWA iOS Limitations 2026](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide) -- Industry analysis (MEDIUM confidence)
- [iOS Safari WebSocket background disconnect](https://github.com/socketio/socket.io/issues/2924) -- Community report, verified by Apple Developer Forums (HIGH confidence)
- [VirtualKeyboard API - MDN](https://developer.mozilla.org/en-US/docs/Web/API/VirtualKeyboard_API) -- Official documentation (HIGH confidence)
- [VisualViewport keyboard fix](https://dev.to/franciscomoretti/fix-mobile-keyboard-overlap-with-visualviewport-3a4a) -- Community guide (MEDIUM confidence)
- [Bottom Sheet UX Guidelines - NNGroup](https://www.nngroup.com/articles/bottom-sheet/) -- Authoritative UX research (HIGH confidence)
- [Chrome 300ms tap delay removal](https://developer.chrome.com/blog/300ms-tap-delay-gone-away) -- Official Chrome documentation (HIGH confidence)
- [CSS env() safe-area-inset - MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Values/env) -- Official documentation (HIGH confidence)
- [PWA Cache Behavior](https://iinteractive.com/resources/blog/taming-pwa-cache-behavior) -- Industry guide (MEDIUM confidence)
- [When browsers throttle rAF](https://motion.dev/magazine/when-browsers-throttle-requestanimationframe) -- Technical analysis (MEDIUM confidence)

---

*Pitfalls audit: 2026-04-05*
