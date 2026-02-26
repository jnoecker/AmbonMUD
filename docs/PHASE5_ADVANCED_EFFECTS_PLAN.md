# Phase 5: Advanced Effects & Multi-Zone Rendering

**Status:** Planning
**Target Timeline:** Weeks 12–14 (3 weeks)
**Build Upon:** Phase 4 Canvas Rendering System
**Focus:** Dynamic visual effects, multi-zone visibility, performance dashboard

---

## 🎯 Phase 5 Objectives

Extend the Phase 4 canvas system with:

1. **Advanced Background Effects** — Weather, lighting, time of day, dynamic particles
2. **Multi-Zone Rendering** — See adjacent rooms simultaneously (north, south, east, west)
3. **Performance Dashboard** — Real-time FPS, memory, particle monitoring
4. **Visual Customization** — Player skins, effect themes, effect intensity settings
5. **Environmental Dynamics** — Day/night cycle, weather effects, seasonal changes

---

## 📋 Phase 5 Breakdown

### Phase 5a: Advanced Background Effects (Week 12)

**Goal:** Create dynamic, weather-responsive background layers

**Features:**

#### Weather System
- ☐ Clear weather
- ☐ Cloudy skies
- ☐ Rain effect (particle-based)
- ☐ Fog/mist (opacity gradients)
- ☐ Snow effect (light particles)
- ☐ Thunderstorm (lightning flashes)

#### Time of Day System
- ☐ Dawn (5:00–7:00) — Orange/pink gradient
- ☐ Day (7:00–18:00) — Bright sky
- ☐ Dusk (18:00–20:00) — Purple/orange gradient
- ☐ Night (20:00–5:00) — Dark blue, starfield
- ☐ Smooth transitions (30-second interpolation)

#### Dynamic Lighting
- ☐ Sun position (affects shadow direction)
- ☐ Moon phases (affects night brightness)
- ☐ Torch/lantern effects (localized glow)
- ☐ Magic light sources (color-coded)

#### Ambient Particles
- ☐ Dust motes (swirling in daylight)
- ☐ Fireflies (twinkling at dusk)
- ☐ Falling leaves (autumn theme)
- ☐ Blowing snow (winter theme)

**Files to Create:**
- `weather-system.js` — Weather state management
- `lighting-system.js` — Dynamic lighting calculations
- `ambient-effects.js` — Environmental particle effects
- `time-of-day.js` — Day/night cycle management

**GMCP Integration:**
```
gmcp.Room.Ambiance
├─ weather: 'clear' | 'cloudy' | 'rain' | 'fog' | 'snow' | 'storm'
├─ timeOfDay: 0–1440 (minutes since midnight)
├─ lighting: 'bright' | 'normal' | 'dim' | 'dark'
└─ lightSources: { x, y, color, intensity }[]
```

---

### Phase 5b: Multi-Zone Rendering (Week 13)

**Goal:** Display adjacent zones in a unified canvas view

**Features:**

#### Multi-Zone Display
- ☐ Show current room in center
- ☐ Show north/south/east/west adjacent rooms
- ☐ Show diagonal rooms (optional)
- ☐ Smooth transitions when moving zones
- ☐ Clear visual separation between zones

#### Zone Boundaries
- ☐ Subtle visual borders (fading lines)
- ☐ Color-coded zone regions
- ☐ Zone transition indicators
- ☐ "Edge of world" visualization

#### Camera Panning
- ☐ Pan camera between zones
- ☐ Follow player across zone boundaries
- ☐ Smooth animation (500ms pan)
- ☐ Optional snap-to-zone mode

#### Performance Optimization
- ☐ Render only visible zones
- ☐ LOD (level of detail) for far zones
- ☐ Cull off-screen entities
- ☐ Shared tile cache

**Architecture:**

```
Canvas Viewport (1200×800px typical)
├─ Zone Grid (3×3 or 1×3 depending on setting)
│  ├─ [NW] [N] [NE]
│  ├─ [W] [CURRENT] [E]
│  └─ [SW] [S] [SE]
│
├─ Camera
│  ├─ Tracks player across zones
│  └─ Pans smoothly between zones
│
└─ Rendering Pipeline
   ├─ Current zone: Full detail
   ├─ Adjacent zones: Reduced detail
   └─ Diagonal zones: Low detail (if visible)
```

**GMCP Integration:**

```
gmcp.Room.Adjacent
├─ north: { roomId, title, mobs: [] }
├─ south: { roomId, title, mobs: [] }
├─ east: { roomId, title, mobs: [] }
└─ west: { roomId, title, mobs: [] }
```

---

### Phase 5c: Performance Dashboard (Week 14)

**Goal:** Provide real-time performance monitoring and optimization tools

**Features:**

#### Performance Metrics Display
- ☐ FPS counter (current, average, min/max)
- ☐ Frame time graph (real-time)
- ☐ Memory usage indicator
- ☐ Particle count display
- ☐ Render time breakdown (per layer)
- ☐ Entity count
- ☐ Network latency

#### Performance Profiling
- ☐ Layer rendering times
- ☐ Particle system stats
- ☐ Camera update time
- ☐ GMCP message processing time
- ☐ Memory allocation tracking

#### Optimization Controls
- ☐ Quality level selector (low/medium/high/ultra)
- ☐ Particle limit adjustment
- ☐ Zone rendering distance
- ☐ Multi-zone visibility toggle
- ☐ Effect intensity slider

#### Performance Warnings
- ☐ FPS warning (<50fps)
- ☐ Memory warning (>10MB)
- ☐ Particle overload warning
- ☐ Recommendations for optimization

**Dashboard UI:**

```
┌─ Performance Dashboard ─┐
├─ FPS: 58 (avg 59)      │
├─ Frame: 12ms           │
├─ Memory: 3.2MB         │
├─ Particles: 18/30      │
├─ Entities: 12          │
├─ Network: 45ms latency │
├─                       │
├─ [Graph: FPS over time]│
├─                       │
├─ Quality: Medium ▼     │
├─ Zones: 1×3 ▼          │
├─ Effects: 80% ●────    │
└─────────────────────────┘
```

**Files to Create:**
- `performance-dashboard.js` — Dashboard rendering and stats
- `performance-profiler.js` — Detailed profiling
- `quality-settings.js` — Quality/performance presets

---

## 🔌 GMCP Extensions

### New GMCP Packages

```
gmcp.Room.Ambiance (Enhanced)
├─ weather: string
├─ timeOfDay: number (0–1440 minutes)
├─ lighting: string
├─ lightSources: LightSource[]
└─ season: 'spring' | 'summer' | 'autumn' | 'winter'

gmcp.Room.Adjacent
├─ north: RoomData
├─ south: RoomData
├─ east: RoomData
└─ west: RoomData

gmcp.Client.Settings
├─ qualityLevel: 'low' | 'medium' | 'high' | 'ultra'
├─ multiZoneEnabled: boolean
├─ effectIntensity: 0–100
└─ dashboardEnabled: boolean
```

---

## 📊 Architecture & Data Flow

```
Phase 5 System Architecture
├─ Weather System
│  ├─ GMCP messages trigger weather changes
│  ├─ Weather affects particle emission
│  └─ Weather affects background rendering
│
├─ Time of Day System
│  ├─ GMCP time updates
│  ├─ Updates sky gradient and lighting
│  └─ Controls ambient effects (fireflies, etc)
│
├─ Lighting System
│  ├─ Calculates shadow direction
│  ├─ Applies global light modulation
│  └─ Renders light sources
│
├─ Multi-Zone Rendering
│  ├─ Receives adjacent room data from GMCP
│  ├─ Manages 5 canvas "viewports" (current + 4 adjacent)
│  ├─ Camera tracks player across zones
│  └─ Culls/LODs far zones
│
├─ Performance Dashboard
│  ├─ Monitors all rendering metrics
│  ├─ Collects per-frame statistics
│  ├─ Provides quality presets
│  └─ Adjusts rendering parameters
│
└─ Master Animation Loop
   ├─ Update weather particles
   ├─ Update time of day lighting
   ├─ Update multi-zone camera
   ├─ Collect performance metrics
   └─ Render all zones + dashboard
```

---

## 🎨 Visual Specifications

### Weather Effects

| Weather | Visual Effect | Particle Type |
|---------|---------------|---------------|
| **Clear** | Bright sky | Dust motes |
| **Cloudy** | Gray overlay | None |
| **Rain** | Dark sky, wet effect | Rain drops |
| **Fog** | Dense opacity | Fog/mist |
| **Snow** | Light flakes | Snowflakes |
| **Storm** | Lightning flashes | Heavy rain + sparks |

### Time of Day Gradients

| Time | Sky Gradient | Ambient Light |
|------|--------------|---------------|
| **Dawn** | Orange → Blue | 70% brightness |
| **Day** | Light Blue | 100% brightness |
| **Dusk** | Purple → Orange | 60% brightness |
| **Night** | Dark Blue → Black | 30% brightness |

### Lighting Effects

- Sun position: Moves across sky (0° at dawn, 180° at dusk)
- Shadow direction: Based on sun position
- Moon position: Opposite sun position at night
- Torch glow: Circular light gradient, 50px radius
- Magic light: Color-coded (red, blue, green, yellow)

---

## 🧪 Testing Strategy

### Visual Regression Testing
- [ ] Weather transitions are smooth
- [ ] Time of day gradients accurate
- [ ] Lighting effects consistent
- [ ] Particle effects visible
- [ ] Multi-zone boundaries clear
- [ ] Performance dashboard readable

### Performance Testing
- [ ] Multi-zone rendering doesn't drop FPS
- [ ] Weather effects don't exceed particle limits
- [ ] Dashboard overhead <5% frame time
- [ ] Memory stable over 30 minutes
- [ ] No visual jank during transitions

### Interaction Testing
- [ ] Quality selector works
- [ ] Particle limit adjustment effective
- [ ] Multi-zone toggle functions
- [ ] Effect intensity slider responsive
- [ ] Dashboard toggles visibility

### Cross-Device Testing
- [ ] Desktop: Full multi-zone (1×3)
- [ ] Tablet: 1×1 or 1×3 option
- [ ] Mobile: 1×1 current zone only
- [ ] Performance acceptable on all

---

## 📈 Success Metrics

| Metric | Target | Notes |
|--------|--------|-------|
| **FPS (Desktop)** | 50+ | Multi-zone + effects |
| **FPS (Mobile)** | 25+ | Reduced zones |
| **Weather Smoothness** | 60fps | Transitions smooth |
| **Time of Day FPS** | 60fps | No lighting stutter |
| **Dashboard Impact** | <5% | Minimal overhead |
| **Memory (Multi-Zone)** | <8MB | With 5 zones |
| **Zone Transition** | <500ms | Smooth pan |

---

## 🗂️ Project Structure

```
src/main/resources/web/
├── js/
│   ├── weather-system.js           (Phase 5a)
│   ├── lighting-system.js          (Phase 5a)
│   ├── ambient-effects.js          (Phase 5a)
│   ├── time-of-day.js              (Phase 5a)
│   ├── multi-zone-renderer.js      (Phase 5b)
│   ├── zone-manager.js             (Phase 5b)
│   ├── performance-dashboard.js    (Phase 5c)
│   ├── performance-profiler.js     (Phase 5c)
│   └── quality-settings.js         (Phase 5c)
└── styles/
    └── performance-dashboard.css   (Phase 5c)

docs/
├── PHASE5_ADVANCED_EFFECTS_PLAN.md         (this file)
├── PHASE5A_WEATHER_LIGHTING.md             (Week 12)
├── PHASE5B_MULTI_ZONE_RENDERING.md         (Week 13)
└── PHASE5C_PERFORMANCE_DASHBOARD.md        (Week 14)
```

---

## 📅 Weekly Breakdown

### Week 12: Advanced Effects (Phase 5a)
- **Days 1-2:** Weather system architecture
  - Weather state machine
  - GMCP integration
  - Particle emission logic

- **Days 3-4:** Time of day system
  - Sky gradient interpolation
  - Lighting calculations
  - Ambient particle timing

- **Day 5:** Testing & refinement
  - Visual regression testing
  - Performance profiling
  - Browser compatibility

### Week 13: Multi-Zone Rendering (Phase 5b)
- **Days 1-2:** Multi-zone architecture
  - Zone viewport system
  - Camera management
  - Zone data structures

- **Days 3-4:** Rendering implementation
  - Multi-viewport rendering
  - Zone transitions
  - LOD system

- **Day 5:** Testing & optimization
  - Cross-device testing
  - Performance profiling
  - Edge case handling

### Week 14: Performance Dashboard (Phase 5c)
- **Days 1-2:** Dashboard UI & metrics
  - Dashboard rendering
  - FPS counter
  - Memory tracking

- **Days 3-4:** Quality settings & profiling
  - Quality presets
  - Performance profiler
  - Optimization recommendations

- **Day 5:** Polish & final testing
  - Dashboard UI refinement
  - Accuracy verification
  - Cross-browser testing

---

## 🎯 Acceptance Criteria (Phase 5 Complete)

### Phase 5a Complete When:
- [ ] Weather system implemented (5 weather types)
- [ ] Time of day cycle working (smooth 24-hour)
- [ ] Lighting affects all rendering layers
- [ ] Ambient particles emit correctly
- [ ] GMCP ambiance messages handled
- [ ] Performance: 50+ fps with all effects
- [ ] Cross-browser compatible
- [ ] Mobile optimized

### Phase 5b Complete When:
- [ ] Multi-zone rendering displays 1×3 layout
- [ ] Camera smoothly pans between zones
- [ ] Adjacent zone data from GMCP
- [ ] Zone boundaries visible
- [ ] Quality degrades gracefully (LOD)
- [ ] Performance: 45+ fps with multi-zone
- [ ] Mobile option: single zone view
- [ ] Smooth transitions verified

### Phase 5c Complete When:
- [ ] Dashboard displays all metrics
- [ ] FPS counter accurate
- [ ] Memory tracking works
- [ ] Quality selector functional
- [ ] Dashboard overhead <5%
- [ ] Presets effective
- [ ] Warnings trigger correctly
- [ ] Mobile: Dashboard optional

---

## 🚀 Deployment Strategy

1. **Week 12:** Deploy Phase 5a (weather/lighting)
   - Beta test with users
   - Gather feedback on effects
   - Optimize based on performance

2. **Week 13:** Deploy Phase 5b (multi-zone)
   - Optional feature (toggle)
   - Reduced zone view on mobile
   - Monitor for performance regressions

3. **Week 14:** Deploy Phase 5c (dashboard)
   - Optional debug overlay
   - Admin-only initially
   - Enable for all users after validation

---

## 💡 Future Enhancements (Phase 6+)

- **Procedural terrain generation** — Infinite world generation
- **Custom player skins** — Cosmetic customization
- **Effect themes** — Alternative visual styles
- **Environmental hazards** — Damage from weather
- **Season cycles** — Multi-month seasonal changes
- **NPC animation** — Idle animations, dialogue gestures
- **Spell animation** — Custom ability effects
- **World events** — Meteor showers, aurora effects

---

**Document Created:** February 26, 2026
**Phase 5 Status:** 📋 Planning (Ready to Begin)
**Next Steps:** Approve plan → Begin Phase 5a implementation

