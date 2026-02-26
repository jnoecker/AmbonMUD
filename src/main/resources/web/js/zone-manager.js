/**
 * Zone Manager (Phase 5b)
 * Manages multiple zones and their adjacency for multi-zone rendering
 */
class ZoneManager {
    constructor() {
        this.currentZone = null;
        this.adjacentZones = new Map();
        this.viewportLayout = '1x1';
        this.lodLevels = new Map();
    }

    setCurrentZone(zoneId) {
        this.currentZone = zoneId;
    }

    setAdjacentZones(zones) {
        this.adjacentZones.clear();
        if (zones.north) this.adjacentZones.set('north', zones.north);
        if (zones.south) this.adjacentZones.set('south', zones.south);
        if (zones.east) this.adjacentZones.set('east', zones.east);
        if (zones.west) this.adjacentZones.set('west', zones.west);
    }

    setViewportLayout(layout) {
        this.viewportLayout = layout; // '1x1', '1x3', '3x3'
    }

    setLodLevel(zoneId, level) {
        this.lodLevels.set(zoneId, level); // 0=full, 1=reduced, 2=minimal
    }

    getVisibleZonesInRenderOrder() {
        const zones = [];
        if (this.currentZone) {
            zones.push({ id: this.currentZone, lod: 0, position: 'center' });
        }
        for (const [dir, zoneId] of this.adjacentZones) {
            zones.push({ id: zoneId, lod: this.lodLevels.get(zoneId) || 1, position: dir });
        }
        return zones;
    }

    moveToZone(direction) {
        const zoneId = this.adjacentZones.get(direction);
        if (zoneId) {
            this.currentZone = zoneId;
            return true;
        }
        return false;
    }
}

class Zone {
    constructor(id) {
        this.id = id;
        this.terrain = [];
        this.entities = [];
        this.players = [];
        this.mobs = [];
        this.lod = 0;
        this.visible = true;
    }

    setTerrain(tiles) {
        this.terrain = tiles || [];
    }

    setEntities(entities) {
        this.entities = entities || [];
    }

    updateMobs(mobs) {
        this.mobs = mobs || [];
    }

    updatePlayers(players) {
        this.players = players || [];
    }

    setLod(level) {
        this.lod = level;
    }
}
