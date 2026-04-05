# Feature Landscape

**Domain:** Mobile-first RPG web client (PWA) for an existing MUD with GMCP-driven structured data
**Researched:** 2026-04-05

## Table Stakes

Features mobile players expect. Missing any of these and the experience feels broken or amateurish.

### Navigation & Movement

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Tap compass exits to move | Every mobile RPG uses tap-to-move; typing directions is a non-starter on phone keyboards | Low | Existing compass component shows exits; wire taps to send direction commands |
| Swipe-to-move (4-way cardinal) | Swipe gestures are universal mobile navigation; 73% of top 100 mobile games use touch gestures for movement | Medium | Must handle diagonal tolerance, threshold distances, conflict with scroll; 4-way sufficient for MUD rooms |
| Room description as visual scene | Mobile players expect to see where they are visually, not read paragraphs of text | Low | Already have PixiJS canvas scenes; ensure they render well on small screens |
| Safe area inset handling | Notches, Dynamic Island, punch-hole cameras obscure HUD elements if not handled | Low | CSS `env(safe-area-inset-*)` on all edge-positioned UI; must test on iPhone and Android |

### HUD & Status Display

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Minimal always-visible HUD | Mobile RPGs show HP/mana/XP bars permanently; Genshin Impact, RuneScape Mobile, etc. all do this | Low | Compact bar cluster (HP red, mana blue, XP gold) pinned top or bottom; must not exceed ~60px vertical |
| Target/combat status in HUD | During combat, target HP and name must be visible without opening a panel | Low | Conditionally render target bar when in combat; leverage existing `Combat.Target` GMCP |
| Level and class indicator | Players always want to know their level at a glance | Low | Small badge in HUD corner; data already in `Char.Name` GMCP |
| Status effect icons | Active buffs/debuffs must be visible icons with remaining duration | Medium | Icon strip near HP bars; tap to expand details; data from `Char.Effects` GMCP |

### Panel System (Bottom Sheet / Drawer)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Bottom sheet panel container | Standard mobile pattern (Google Maps, Apple Music, RuneScape Mobile); replaces sidebar panels | High | Must handle peek/half/full snap points, drag-to-resize, backdrop dimming, swipe-to-dismiss. This is the backbone of all panel access on mobile |
| Panel tab bar / navigation | Players need quick access to 14+ panels without hunting through menus | Medium | Icon strip at bottom edge; RuneScape Mobile uses two-column stone buttons; we need categorized icons |
| Character panel in bottom sheet | View stats, level, class, achievements | Low | Existing `CharacterPanel` content, re-laid out for mobile width |
| Inventory panel with tap-to-act | Tap item to see actions (equip, use, drop, give); no right-click on mobile | Medium | Replace hover tooltips with tap-to-expand action menu; item cards must be 48x48dp minimum touch targets |
| Equipment panel with tap-to-unequip | Visual equipment slots, tap to unequip | Low | Existing panel, re-layout for portrait; slot icons as tappable 48x48dp targets |
| Quest log panel | View active/completed quests, objectives, rewards | Low | Existing panel, reflow for mobile |
| Chat panel (see Chat section below) | Messaging is core to multiplayer | High | Separate entry; see below |

### Combat Interaction

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Action bar with ability buttons | Every mobile RPG has a bottom action bar for attack/abilities/flee | Medium | Grid of ability icons with cooldown sweep overlays (dark + clockwise sweep is standard); must handle 4-8 visible slots |
| Tap-to-target entity selection | Tap mob in room entity list to set target; tap again or tap attack to engage | Low | Room entities already listed; add tappable interaction |
| Cooldown visualization | Darkened icon with radial sweep timer is universal for ability cooldowns | Medium | Must implement smooth 60fps sweep animation on ability icons; not just a number |
| Combat log as toast stream | Damage dealt/received, ability effects shown as brief toasts, not terminal scroll | Medium | See Toast system below; combat events are the highest-volume toast source |

### Toast / Notification System

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Toast notification system | Mobile games use brief, auto-dismissing overlays for feedback (loot, XP, level up, quest progress) instead of scrolling text | High | Core infrastructure: toast queue, stacking (max 3-4 visible), auto-dismiss timers, priority levels, different visual styles per category (damage=red, loot=gold, XP=blue, system=neutral) |
| Gain events as toasts | "+50 XP", "+120 gold", "Item acquired: Iron Sword" | Low | Wire `Char.Gains` GMCP into toast system (depends on toast infrastructure) |
| Combat feedback toasts | "You hit Goblin for 15 damage", "Goblin hits you for 8 damage" | Low | Wire combat GMCP events into toast system |
| Level up celebration overlay | Full-screen or prominent celebration when leveling up | Medium | Modal overlay with animation, auto-dismiss after 3-5 seconds; data from `Char.Vitals` level change |
| Quest completion toast | "Quest Complete: Goblin Slayer" with reward summary | Low | Wire `Quest.Complete` GMCP into toast |
| Achievement unlock toast | Badge-style notification that slides in | Low | Wire achievement GMCP into toast |

### Chat UI

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Messenger-style chat bubbles | The only place players type; must be familiar (WhatsApp/iMessage mental model) | High | Bubble alignment (own=right, others=left), sender differentiation by color, timestamps, channel identification |
| Channel tabs (say/tell/gossip/guild/ooc) | Players need to filter and switch channels easily | Medium | Tab strip at top of chat panel; unread indicators per channel; currently chat has channel support in GMCP |
| Expandable text input | Input grows from single line to multi-line as player types | Low | Standard textarea with auto-grow; max 3 lines visible |
| Chat minimized mode | Chat must not obscure gameplay; show last 1-2 messages as overlay, tap to expand | Medium | Floating bubble or thin strip at screen edge; tap opens full chat bottom sheet |

### NPC & Entity Interaction

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Tap NPC to talk | Dialogue initiation via tap, not typing "talk shopkeeper" | Low | Room entity list shows NPCs; tap sends `talk <npc>` command |
| Dialogue choices as buttons | NPC dialogue options shown as tappable buttons, not numbered list | Low | Existing dialogue GMCP provides choices; render as button list |
| Shop items as tappable cards | Browse shop inventory as scrollable card list; tap to buy | Medium | Existing `ShopPopout` redesigned as bottom sheet card list; item image + name + price + buy button |
| Context-sensitive action menus | Tap entity in room to see what you can do (look, kill, talk, trade) | Medium | Popup action menu on entity tap; filter actions by entity type (NPC: talk/trade, mob: attack/look, player: tell/trade/duel) |

### Responsive Layout

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Portrait-first mobile layout (375px-430px) | 73% of top 100 mobile games are portrait-mode; one-handed play is expected | High | Full layout redesign: canvas viewport fills upper portion, HUD overlay, bottom sheet for panels, action bar at bottom. This is the core layout architecture |
| Tablet layout (600px-1024px) | Tablets get a hybrid: larger canvas, maybe persistent side panel | Medium | Adapt bottom sheet to side panel at this breakpoint; two-column where sensible |
| Desktop layout (1024px+) | Desktop users expect to use the full screen efficiently | Medium | Canvas fills center, panels as sidebars or floating windows; essentially the current layout but refined |
| Touch target sizing (48x48dp minimum) | Android Material Design and Apple HIG both mandate minimum 48dp/44pt touch targets | Medium | Audit every interactive element; resize buttons, links, list items; add spacing between adjacent targets |
| Landscape orientation support | Some players rotate phone for wider view | Low | Canvas stretches; HUD repositions; bottom sheet stays but narrower |

### PWA Fundamentals

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Web app manifest | Required for Add to Home Screen on both iOS and Android | Low | `manifest.json` with name, icons (192px, 512px), theme_color, display: standalone |
| Service worker for app shell caching | Instant load on repeat visits; offline shell that shows "reconnecting" | Medium | Cache-first for static assets (JS, CSS, images), network-only for WebSocket. Show friendly offline state instead of browser error |
| Add to Home Screen prompting | Guide players to install; iOS requires manual Share > Add, Android shows `beforeinstallprompt` | Low | Custom install banner on Android; instructional overlay on iOS Safari |
| Splash screen / loading state | Native-feeling launch experience when opening from home screen | Low | manifest `background_color` + startup image; app-level loading spinner while WebSocket connects |

## Differentiators

Features that set AmbonMUD apart from other mobile MUD/RPG clients. Not expected, but valued.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Haptic feedback on combat/loot | Subtle vibration on hits, crits, loot pickup; makes combat feel physical and satisfying | Low | Vibration API works on Android Chrome only; iOS Safari does NOT support it. Implement as progressive enhancement. Use sparingly: critical hit, level up, death only |
| Swipe-down quick panel peek | Swipe down from top to peek at character stats without opening full panel; like iOS Control Center | Medium | Custom gesture recognizer; must not conflict with OS pull-down notifications. Show vitals + gold + quest progress |
| Floating combat text on canvas | Damage numbers rise from entities on the PixiJS canvas, like classic RPG hit splashes | Medium | PixiJS text sprites with float-up + fade animation; must not overwhelm on mobile; limit to own damage/healing |
| Canvas entity tap interaction | Tap directly on rendered entities in the PixiJS canvas (not just a list) to target/interact | High | Requires PixiJS hit testing on mobile touch events; entity sprites need tap zones; must coordinate with React UI layer |
| Guided first-time experience (FTUE) | Step-by-step walkthrough for new mobile players: how to move, fight, open panels | Medium | Highlight-and-explain overlays for key UI elements; 5-7 steps max; skip option; data shows 60-second FTUE is critical for retention |
| Quick-cast gesture shortcuts | Long-press ability icon to pin it; then swipe-up from action bar to cast without opening spellbook | Medium | Power-user feature that reduces taps for frequent spells; discoverable via FTUE |
| Auto-attack toggle | Tap once to start auto-attacking current target; eliminates repeated tap-to-attack | Low | Toggle button in combat HUD; sends `kill` command on each combat round; stops on target death or flee |
| Rich push notifications (PWA) | "Your auction sold!" or "Guild event starting in 10 minutes" as push notifications | High | Requires server-side push subscription management (new server endpoint); iOS requires PWA installed to home screen first; engagement boost but significant server work. May be out of scope per PROJECT.md constraints |
| Offline world map viewer | Cached zone map viewable offline; see where you were, plan next session | Medium | Cache zone adjacency data from GMCP; render simple node graph; read-only when disconnected |
| Ambient audio with spatial cues | Background music per zone, SFX for combat/loot; spatial audio for nearby events | Medium | Existing `AudioControls` component is a foundation; needs per-zone audio assets and mobile-optimized playback (auto-play restrictions on mobile browsers require user gesture first) |
| Mini-map with fog of war | Small corner mini-map showing explored rooms; dimmed for unexplored | High | Build from existing `useMiniMap` hook; render as small PixiJS overlay; needs room adjacency graph from GMCP |
| Keyboard avoidance on chat | When soft keyboard opens for chat, layout adjusts smoothly without jumping or covering input | Medium | `visualViewport` API + CSS adjustments; iOS Safari is notoriously bad at this; requires thorough testing |
| Gesture-based emotes | Quick swipe patterns on canvas to trigger common emotes (wave, bow, laugh) | Low | Fun social feature; map 4 gestures to emote commands; discoverable via emote menu |

## Anti-Features

Features to explicitly NOT build. Tempting but wrong for this project.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Virtual joystick for movement | MUD rooms are discrete locations (not continuous space); joystick implies analog movement and covers screen real estate. Research shows tap-to-move preferred for non-action RPGs | Tap compass exits + swipe-to-move (discrete directional) |
| Full terminal emulator on mobile | The entire point of this redesign is eliminating terminal-based gameplay; embedding xterm.js on mobile wastes screen space and is unusable for gameplay | Toast notifications + tap interactions + chat bubbles. Keep terminal only as a hidden debug/power-user option accessible via settings |
| Native app wrapper (Capacitor/Cordova) | Adds build complexity, app store approval delays, and a second deployment pipeline. PWA-first is the stated strategy; native only if PWA proves insufficient | PWA with manifest, service worker, push notifications. Re-evaluate native only after PWA ships and user feedback is collected |
| Drag-and-drop inventory management | Drag-and-drop is unreliable on mobile (conflicts with scroll, accidental drops, no hover state). Research shows tap-to-act is preferred on touch devices | Tap item to show action menu (equip/use/drop/give); reorder via explicit "sort" button if needed |
| Landscape-only lock | 73% of top mobile games are portrait-first. Forcing landscape alienates one-handed play and casual sessions | Portrait-first design that works in both orientations; canvas and UI adapt |
| Complex multi-touch gestures | Pinch-to-zoom on canvas, two-finger rotate, etc. add complexity without clear RPG gameplay value; conflict with OS gestures | Simple gestures only: single tap, long press, swipe in cardinal directions. Pinch only if map zoom is added later |
| Real-time minimap tracking | Continuous position tracking on a minimap implies spatial movement the MUD model doesn't have | Static room-graph mini-map that updates on room change, not in real-time |
| Desktop-only features | PROJECT.md explicitly states "no features that only work on desktop; everything must work on mobile" | Design mobile-first, enhance for desktop. If it doesn't work on a 375px screen, don't build it |
| Inline text command input for gameplay | Typing "kill goblin" or "cast fireball" on mobile is hostile UX; soft keyboards cover half the screen | All gameplay via tap interactions. Text input reserved exclusively for chat (say/tell/gossip/guild) and a hidden command fallback |
| Server-side changes for mobile features | PROJECT.md explicitly declares this out of scope. GMCP already provides all needed structured data | Pure client-side redesign. If a feature requires new GMCP packages or server endpoints, defer it to a future project (exception: push notification subscription endpoint, flagged as differentiator) |

## Feature Dependencies

```
Safe Area Handling ──> All UI Components (must be in place first)

Portrait Layout Architecture ──> Every other feature
    ├──> Bottom Sheet Panel System
    │       ├──> All panel redesigns (Character, Inventory, Equipment, etc.)
    │       ├──> Shop/Trade/Trainer as bottom sheet variants
    │       └──> Chat Panel (messenger UI)
    ├──> HUD Overlay
    │       ├──> Status Effect Icons
    │       ├──> Target/Combat Status
    │       └──> Level/Class Badge
    └──> Action Bar
            ├──> Ability Buttons + Cooldown Viz
            ├──> Auto-Attack Toggle
            └──> Quick-Cast Gestures

Toast Notification System ──> (independent, but consumed by many)
    ├──> Combat Feedback Toasts
    ├──> Gain Event Toasts
    ├──> Quest/Achievement Toasts
    └──> Level Up Celebration

Tap Compass Navigation (independent, early win)

Swipe-to-Move (independent, early win)

PWA Manifest + Service Worker (independent, early win)
    └──> Push Notifications (differentiator, needs server work)

Canvas Entity Tap Interaction ──> requires PixiJS hit testing
    └──> Floating Combat Text (enhances canvas)

Messenger Chat UI ──> Bottom Sheet Panel System
    └──> Chat Minimized Mode
            └──> Keyboard Avoidance

Context Action Menus ──> Tap-to-Target
    └──> NPC Tap Interaction

First-Time User Experience ──> All core features (build FTUE last)
```

## MVP Recommendation

### Phase 1: Foundation (build order matters)

Prioritize these because everything else depends on them:
1. **Portrait-first responsive layout architecture** - The container that everything lives in
2. **Safe area inset handling** - Must be correct before any UI positioning
3. **Bottom sheet panel system** - The primary interaction pattern for all panels
4. **Minimal HUD overlay** (HP/mana/XP bars) - Always-visible game state
5. **PWA manifest + basic service worker** - Installability from day one

### Phase 2: Core Interactions

6. **Tap compass navigation** - Most fundamental gameplay action
7. **Toast notification system** - Replaces terminal output for all feedback
8. **Action bar with ability buttons + cooldown viz** - Combat is the core loop
9. **Tap-to-target entity selection** - Required for combat
10. **Panel tab bar / navigation** - Access to all game panels

### Phase 3: Content Panels + Chat

11. **Inventory panel with tap-to-act** - Most-used panel after character
12. **Messenger-style chat UI with channels** - Social is critical for MUD retention
13. **NPC interaction (tap to talk, dialogue buttons, shop cards)**
14. **Context-sensitive action menus** - Unifies entity interaction
15. **Remaining panel redesigns** (Equipment, Quest, Crafting, Bank, etc.)

### Phase 4: Polish + Differentiators

16. **Swipe-to-move** - Navigation alternative to compass tap
17. **Combat feedback toasts** (damage, loot, XP) - Makes combat feel alive
18. **Level up celebration overlay**
19. **Haptic feedback** (Android only, progressive enhancement)
20. **Floating combat text on canvas**
21. **First-time user experience walkthrough**
22. **Chat minimized mode + keyboard avoidance**

### Defer

- **Push notifications:** Requires server-side changes (out of scope per PROJECT.md)
- **Offline world map viewer:** Nice-to-have, not essential for MVP
- **Canvas entity tap interaction:** Complex PixiJS hit testing; room entity list taps are sufficient initially
- **Mini-map with fog of war:** High complexity, low urgency for mobile MVP

## Sources

- [Game UI Database](https://www.gameuidatabase.com/) - Comprehensive game UI screenshot reference
- [Pixune: Best Mobile Game UI Designs 2026](https://pixune.com/blog/best-examples-mobile-game-ui-design/) - Mobile game UI trend review
- [Sanjay Dey: 7 Mobile UX/UI Design Patterns 2026](https://www.sanjaydey.com/mobile-ux-ui-design-patterns-2026-data-backed/) - Data-backed mobile UX patterns
- [Polydin: Mastering Game HUD Design](https://polydin.com/game-hud-design/) - HUD design guide
- [RuneScape Mobile UI Rework](https://secure.runescape.com/m=news/mobile-ui-rework---overview--open-beta?oldschool=1) - OSRS mobile panel/action bar layout reference
- [Genshin Impact Mobile UI](https://interfaceingame.com/games/genshin-impact-mobile/) - Mobile RPG HUD reference
- [Mobbin: Bottom Sheet Design](https://mobbin.com/explore/mobile/ui-elements/bottom-sheet) - Bottom sheet pattern gallery
- [BricxLabs: Chat UI Design Patterns 2025](https://bricklabs.com/blogs/message-screen-ui-deisgn) - Messenger-style chat patterns
- [Game UI Database: Notifications](https://www.gameuidatabase.com/index.php?scrn=145) - Toast/notification UI reference
- [Adrian Crook: Mobile Game Onboarding Best Practices](https://adriancrook.com/best-practices-for-mobile-game-onboarding/) - FTUE patterns
- [MDN: PWA Offline with Service Workers](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Tutorials/js13kGames/Offline_Service_workers) - PWA caching strategies
- [MagicBell: PWA iOS Limitations 2026](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide) - iOS PWA capability reference
- [MDN: Vibration API](https://developer.mozilla.org/en-US/docs/Web/API/Vibration_API) - Haptic feedback browser support
- [Medium: Mind the Notch](https://medium.com/design-bootcamp/mind-the-notch-creating-seamless-ui-across-games-ad5e57616ed5) - Safe area design for games
- [Android Developers: Haptics Principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) - Haptic feedback guidelines
- [PocketGamer: Portrait Mode Trumped Tablet-First](https://www.pocketgamer.biz/comment-and-opinion/62882/how-portrait-mode-trumped-tablet-first-gaming/) - Portrait vs landscape market data
- [Gamecrio: Game UI/UX Trends 2025](https://gamecrio.com/5-game-ui-ux-trends-in-2025-every-developer-should-follow/) - Current game UI trends
- [H3XED: CSS env() Safe Area Inset](https://www.h3xed.com/web-development/css-env-safe-area-inset-mobile-fullscreen-camera-cutout) - Technical implementation reference
