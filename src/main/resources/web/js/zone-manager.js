/**
 * Zone Manager (Phase 5b)
 * Manages zone data structures and adjacent room relationships
 * Handles zone boundaries and level-of-detail culling
 */

class Zone {
    constructor(id, data = {}) {
        this.id = id; // Format: "zone:roomId"
        this.title = data.title || 'Unknown Zone';
        this.description = data.description || '';
        this.width = data.width || 40; // In tiles
        this.height = data.height || 30;
        this.terrain = data.terrain || []; // Array of terrain tiles
        this.mobs = data.mobs || [];
        this.players = data.players || [];
        this.exits = data.exits || {};
        this.ambiance = data.ambiance || {}; // Weather, lighting, etc.

        // LOD (Level of Detail) state
        this.lodLevel = 1; // 0 = full detail, 1 = reduced, 2 = minimal
        this.isVisible = true;
        this.opacity = 1.0;
    }

    /**
     * Calculate which tiles to render based on LOD level
     */
    getTilesToRender() {
        if (this.lodLevel === 0) {
            // Full detail: render all tiles
            return this.terrain;
        } else if (this.lodLevel === 1) {
            // Reduced detail: render every other tile
            return this.terrain.filter((_, idx) => idx % 2 === 0);
        } else {
            // Minimal detail: render every 4th tile
            return this.terrain.filter((_, idx) => idx % 4 === 0);
        }
    }

    /**
     * Calculate which mobs to render based on LOD level
     */
    getMobsToRender() {
        if (this.lodLevel === 0) {
            // Full detail: render all mobs
            return this.mobs;
        } else if (this.lodLevel === 1) {
            // Reduced detail: render boss mobs only (threat > 5)
            return this.mobs.filter(m => m.threat > 5 || m.isUnique);
        } else {
            // Minimal detail: render only bosses (threat > 10)
            return this.mobs.filter(m => m.threat > 10);
        }
    }

    /**
     * Update zone opacity for fade-in/out
     */
    setOpacity(opacity) {
        this.opacity = Math.max(0, Math.min(1, opacity));
    }

    /**
     * Update LOD level based on distance from player
     */
    updateLOD(distance) {
        // distance: 0 = current zone, 1 = adjacent, 2 = diagonal
        if (distance === 0) {
            this.lodLevel = 0; // Full detail
        } else if (distance === 1) {
            this.lodLevel = 1; // Reduced detail
        } else {
            this.lodLevel = 2; // Minimal detail
        }
    }
}

class ZoneManager {
    constructor() {
        this.zones = new Map(); // id -> Zone
        this.currentZoneId = null;
        this.adjacentZones = {
            north: null,
            south: null,
            east: null,
            west: null,
            // Optional diagonals
            northeast: null,
            northwest: null,
            southeast: null,
            southwest: null,
        };

        // Viewport layout (can be modified for different layouts)
        this.viewportLayout = '1x3'; // '1x1', '1x3', '3x3'
        this.visibleZones = []; // Currently rendered zones in viewport order
    }

    /**
     * Set current zone and load adjacent zone data
     */
    setCurrentZone(zoneId, zoneData) {
        this.currentZoneId = zoneId;

        // Create or update current zone
        if (!this.zones.has(zoneId)) {
            this.zones.set(zoneId, new Zone(zoneId, zoneData));
        } else {
            const zone = this.zones.get(zoneId);
            Object.assign(zone, zoneData);
        }

        // Update all zones' LOD levels
        this.updateAllLOD();
        this.updateVisibleZones();
    }

    /**
     * Update adjacent zone data from GMCP Room.Adjacent message
     */
    updateAdjacentZones(adjacentData) {
        const directions = ['north', 'south', 'east', 'west'];

        for (const direction of directions) {
            if (adjacentData[direction]) {
                const roomData = adjacentData[direction];
                const zoneId = roomData.roomId;

                this.adjacentZones[direction] = zoneId;

                // Create or update adjacent zone
                if (!this.zones.has(zoneId)) {
                    this.zones.set(zoneId, new Zone(zoneId, roomData));
                } else {
                    const zone = this.zones.get(zoneId);
                    Object.assign(zone, roomData);
                }
            }
        }

        this.updateAllLOD();
        this.updateVisibleZones();
    }

    /**
     * Get zone by ID
     */
    getZone(zoneId) {
        return this.zones.get(zoneId);
    }

    /**
     * Get current zone
     */
    getCurrentZone() {
        return this.zones.get(this.currentZoneId);
    }

    /**
     * Get adjacent zone by direction
     */
    getAdjacentZone(direction) {
        const zoneId = this.adjacentZones[direction];
        return zoneId ? this.zones.get(zoneId) : null;
    }

    /**
     * Update LOD levels for all zones based on distance
     */
    updateAllLOD() {
        for (const [zoneId, zone] of this.zones) {
            if (zoneId === this.currentZoneId) {
                zone.updateLOD(0); // Full detail
            } else if (
                zoneId === this.adjacentZones.north ||
                zoneId === this.adjacentZones.south ||
                zoneId === this.adjacentZones.east ||
                zoneId === this.adjacentZones.west
            ) {
                zone.updateLOD(1); // Reduced detail
            } else {
                zone.updateLOD(2); // Minimal detail
            }
        }
    }

    /**
     * Determine which zones are visible based on viewport layout
     */
    updateVisibleZones() {
        this.visibleZones = [];

        if (this.viewportLayout === '1x1') {
            // Only current zone
            if (this.currentZoneId) {
                this.visibleZones.push({
                    zoneId: this.currentZoneId,
                    position: { row: 0, col: 0 }, // Center
                    offset: { x: 0, y: 0 },
                });
            }
        } else if (this.viewportLayout === '1x3') {
            // Horizontal layout: West - Current - East
            const zones = [
                { dir: 'west', row: 0, col: 0 },
                { dir: null, row: 0, col: 1 }, // Current
                { dir: 'east', row: 0, col: 2 },
            ];

            let colIndex = 0;
            for (const { dir, row, col } of zones) {
                const zoneId = dir ? this.adjacentZones[dir] : this.currentZoneId;
                if (zoneId) {
                    this.visibleZones.push({
                        zoneId,
                        position: { row, col },
                        offset: { x: col * 800, y: row * 600 }, // Assuming 800×600 per zone
                    });
                }
            }
        } else if (this.viewportLayout === '3x3') {
            // Full grid layout
            const grid = [
                { dir: 'northwest', row: 0, col: 0 },
                { dir: 'north', row: 0, col: 1 },
                { dir: 'northeast', row: 0, col: 2 },
                { dir: 'west', row: 1, col: 0 },
                { dir: null, row: 1, col: 1 }, // Current
                { dir: 'east', row: 1, col: 2 },
                { dir: 'southwest', row: 2, col: 0 },
                { dir: 'south', row: 2, col: 1 },
                { dir: 'southeast', row: 2, col: 2 },
            ];

            for (const { dir, row, col } of grid) {
                const zoneId = dir ? this.adjacentZones[dir] : this.currentZoneId;
                if (zoneId) {
                    this.visibleZones.push({
                        zoneId,
                        position: { row, col },
                        offset: { x: col * 800, y: row * 600 },
                    });
                }
            }
        }
    }

    /**
     * Set viewport layout mode
     */
    setViewportLayout(layout) {
        if (['1x1', '1x3', '3x3'].includes(layout)) {
            this.viewportLayout = layout;
            this.updateVisibleZones();
        }
    }

    /**
     * Get distance from current zone to another
     */
    getZoneDistance(fromZoneId, toZoneId) {
        if (fromZoneId === toZoneId) return 0;
        if (Object.values(this.adjacentZones).includes(toZoneId)) return 1;
        return 2; // Diagonal or further
    }

    /**
     * Move player to new zone (camera pan)
     */
    moveToZone(direction) {
        const targetZoneId = this.adjacentZones[direction];
        if (!targetZoneId) return false;

        this.currentZoneId = targetZoneId;
        this.updateAllLOD();
        this.updateVisibleZones();
        return true;
    }

    /**
     * Get zone boundary info for visual rendering
     */
    getZoneBoundary(zoneId) {
        const zone = this.zones.get(zoneId);
        if (!zone) return null;

        const viewportEntry = this.visibleZones.find(v => v.zoneId === zoneId);
        if (!viewportEntry) return null;

        const { col, row } = viewportEntry.position;
        const { x, y } = viewportEntry.offset;

        return {
            x,
            y,
            width: zone.width * 20, // Assuming 20px tiles
            height: zone.height * 20,
            column: col,
            row,
            isCurrentZone: zoneId === this.currentZoneId,
        };
    }

    /**
     * Check if zones are adjacent
     */
    areZonesAdjacent(zoneId1, zoneId2) {
        if (zoneId1 === this.currentZoneId && zoneId2 === this.currentZoneId) {
            return false;
        }
        if (zoneId1 === this.currentZoneId) {
            return Object.values(this.adjacentZones).includes(zoneId2);
        }
        if (zoneId2 === this.currentZoneId) {
            return Object.values(this.adjacentZones).includes(zoneId1);
        }
        return false;
    }

    /**
     * Get all visible zones sorted by rendering order (back to front)
     */
    getVisibleZonesInRenderOrder() {
        return this.visibleZones
            .map(v => this.zones.get(v.zoneId))
            .filter(z => z && z.isVisible)
            .sort((a, b) => {
                // Current zone rendered last (on top)
                if (a.id === this.currentZoneId) return 1;
                if (b.id === this.currentZoneId) return -1;
                return 0;
            });
    }

    /**
     * Clear all zone data
     */
    clear() {
        this.zones.clear();
        this.currentZoneId = null;
        this.adjacentZones = {
            north: null,
            south: null,
            east: null,
            west: null,
            northeast: null,
            northwest: null,
            southeast: null,
            southwest: null,
        };
        this.visibleZones = [];
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = { Zone, ZoneManager };
}
