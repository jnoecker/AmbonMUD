# Phase 3: Bottom Sheet, HUD & PWA Foundation - Context

**Gathered:** 2026-04-05
**Status:** Ready for planning

<domain>
## Phase Boundary

Players access all game panels through a familiar pull-up bottom sheet, see essential stats at a glance in a minimal HUD, and can install the app as a PWA with reliable WebSocket reconnection. Requirements: PANEL-01, PANEL-02, PANEL-03, HUD-01, HUD-03, PWA-01, PWA-02, PWA-05.

</domain>

<decisions>
## Implementation Decisions

### Bottom Sheet Behavior
- **D-01:** Three snap points: Peek (~15%, tab bar visible), Half (~50%, quick interactions), Full (~90%, deep content like inventory). Standard 3-stop pattern like Apple/Google Maps.
- **D-02:** Custom CSS transforms + pointer event handlers -- no external gesture or animation libraries. Zero new dependencies, full control over snap physics.
- **D-03:** Tap outside the sheet (on canvas or HUD) to collapse to peek state.
- **D-04:** Drag handle for continuous resize with snap-on-release + tap tabs to jump to half snap. Full gesture vocabulary: drag to resize, tap tab to open, tap outside to collapse.

### Panel Tab Organization
- **D-05:** Top 5 pinned icon tabs + overflow menu (⋯ button) for remaining panels. Clean tab bar, 1 tap for essentials, 2 taps for the rest via overflow grid.
- **D-06:** Tab bar positioned at top of bottom sheet, below drag handle. Tabs move with the sheet as it snaps between positions.
- **D-07:** Default pinned tabs: Character, Inventory, Combat, Quest, Chat. These cover the core RPG loop + social. Equipment, Crafting, Bank, Auction, Mail, Housing, Leaderboard, Admin accessible via overflow.

### HUD Presentation
- **D-08:** Thin stacked horizontal bars (HP, mana, XP) at 4-6px height each, with numeric values (e.g., "245/312"). Level and class badge displayed above the bars (e.g., "Lv12 Ranger"). Clean, data-dense, familiar RPG style consistent with existing design system.
- **D-09:** Tapping the HUD opens the bottom sheet to half-snap with the Character panel selected. Quick access to full stats as a natural extension of the at-a-glance pattern.

### Reconnection UX
- **D-10:** Subtle banner during reconnection -- slim banner with spinner and "Reconnecting..." text. Game state stays visible underneath. Auto-dismiss on successful reconnect. Extends existing `.reconnect-banner` CSS pattern.
- **D-11:** Full GMCP resync after reconnection -- server sends all packages (Char.Vitals, Room.Info, Char.Items, etc.) just like initial login. Client replaces all state. Simple, reliable, no server changes needed.
- **D-12:** Exponential backoff replaces the current simple 500ms retry in App.tsx. Proper backoff with jitter to prevent thundering herd.

### PWA Foundation
- **D-13:** Enhance existing `manifest.json` and `sw.js` in `web-v3/public/` -- don't start from scratch. Manifest already has icons, theme color, standalone display. Service worker already has cache-first static assets and network-first HTML.
- **D-14:** On desktop (1024px+), the bottom sheet renders as a side panel using CSS-only positioning change -- no JavaScript code branching between mobile and desktop modes (PANEL-03).

### Claude's Discretion
- Exact exponential backoff parameters (start delay, cap, max attempts, jitter strategy)
- Overflow menu layout (grid vs list of remaining panels)
- Drag handle visual design (pill indicator, width, color)
- Nested scroll handling strategy within sheet content areas
- Service worker cache versioning and update strategy
- Sheet transition animation timing and easing curves

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Bottom sheet and layout
- `web-v3/src/components/LayoutShell.tsx` -- Thin wrapper from Phase 2; bottom sheet and HUD will be built within or alongside this component
- `web-v3/src/styles.css` -- All CSS custom properties, responsive breakpoints (768px/1024px from Phase 2), existing progress bar and panel styling
- `.planning/REQUIREMENTS.md` -- PANEL-01 (bottom sheet snaps), PANEL-02 (tab bar navigation), PANEL-03 (desktop side panel via CSS)

### HUD
- `web-v3/src/components/panels/CharacterPanel.tsx` -- Character panel that HUD tap opens; understand its props and state dependencies
- `.planning/REQUIREMENTS.md` -- HUD-01 (HP/mana/XP bars, max 60px), HUD-03 (level and class badge)

### Panel system
- `web-v3/src/components/panels/` -- All 14 existing panel components that will be hosted inside the bottom sheet
- `web-v3/src/components/ActionBar.tsx` -- Current action bar; may need repositioning within new layout
- `web-v3/src/components/PopoutLayer.tsx` -- Existing popout pattern; bottom sheet may replace or coexist

### PWA and reconnection
- `web-v3/public/manifest.json` -- Existing PWA manifest with icons, theme color, standalone display mode
- `web-v3/public/sw.js` -- Existing service worker with cache-first statics and network-first HTML
- `web-v3/index.html` -- Already has manifest link, apple-mobile-web-app meta tags, theme-color
- `web-v3/src/hooks/useMudSocket.ts` -- WebSocket hook with connect/disconnect/reconnect; backoff logic goes here
- `web-v3/src/App.tsx` -- Current reconnection logic (simple 500ms retry at line ~600); needs exponential backoff upgrade
- `web-v3/src/gmcp/applyGmcpPackage.ts` -- GMCP handler that processes state resync on reconnect
- `.planning/REQUIREMENTS.md` -- PWA-01 (manifest), PWA-02 (service worker caching), PWA-05 (WebSocket reconnection + state resync)

### Design system
- `docs/STYLE_GUIDE.md` -- Color tokens, typography, motion, component states
- `.impeccable.md` -- Design context, brand personality, aesthetic direction ("Surreal Gentle Magic")

### Prior phase context
- `.planning/phases/01-state-extraction/01-CONTEXT.md` -- State management architecture (domain-split contexts, GameStateBridgeSync)
- `.planning/phases/02-layout-shell-canvas-safety/02-CONTEXT.md` -- LayoutShell zones (CanvasZone/HudSlot/InteractionZone), breakpoints, VRAM management

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `LayoutShell.tsx` -- Thin wrapper from Phase 2; provides the zone structure (CanvasZone, HudSlot, InteractionZone) where bottom sheet and HUD will be placed
- `.reconnect-banner` CSS class -- Existing reconnection banner styling with slide-in animation and spinner; can be enhanced for exponential backoff UI
- `manifest.json` + `sw.js` -- Already functional PWA scaffolding with icons, theme color, cache-first strategy
- `PopoutLayer.tsx` -- Existing overlay pattern with backdrop click-to-close; similar dismiss pattern to bottom sheet
- Progress bar CSS in `styles.css` -- Existing bar styling with gradients and glows that can inform HUD bar design
- `index.html` -- Already includes `viewport-fit=cover`, apple-mobile-web-app meta tags, manifest link

### Established Patterns
- CSS custom properties for all colors/spacing -- new HUD and sheet components must use design tokens from `styles.css`
- `100dvh` viewport handling for iOS Safari address bar -- maintain in sheet height calculations
- `env(safe-area-inset-*)` for notch handling -- sheet and HUD must respect safe areas
- Domain-split React contexts (Phase 1) -- HUD will consume vitals/character context directly via useContext

### Integration Points
- HudSlot zone in LayoutShell -- HUD component renders here (~60px, between canvas and interaction zone)
- InteractionZone in LayoutShell -- Bottom sheet renders here (remaining space below HUD)
- `useMudSocket.ts` reconnect function -- Backoff logic wraps this existing hook
- `applyGmcpPackage` -- Full state resync happens through existing GMCP handler on reconnect
- Panel components (`panels/*.tsx`) -- Each panel becomes a content pane inside the bottom sheet; no panel refactoring in this phase, just hosting

</code_context>

<specifics>
## Specific Ideas

No specific requirements -- open to standard approaches

</specifics>

<deferred>
## Deferred Ideas

None -- discussion stayed within phase scope

</deferred>

---

*Phase: 03-bottom-sheet-hud-pwa-foundation*
*Context gathered: 2026-04-05*
