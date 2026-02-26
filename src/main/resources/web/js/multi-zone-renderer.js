/**
 * Multi-Zone Renderer (Phase 5b)
 * Renders multiple adjacent zones with level-of-detail optimization
 */
class MultiZoneRenderer {
    constructor(zoneManager) {
        this.zoneManager = zoneManager;
        this.zones = new Map();
        this.viewportLayout = '1x1';
    }

    addZone(zone) {
        this.zones.set(zone.id, zone);
    }

    updateZone(zoneId, data) {
        let zone = this.zones.get(zoneId);
        if (!zone) {
            zone = new Zone(zoneId);
            this.addZone(zone);
        }
        if (data.terrain) zone.setTerrain(data.terrain);
        if (data.entities) zone.setEntities(data.entities);
    }

    renderZones(ctx, gameState) {
        const visibleZones = this.zoneManager.getVisibleZonesInRenderOrder();

        for (const zoneInfo of visibleZones) {
            const zone = this.zones.get(zoneInfo.id);
            if (!zone) continue;

            zone.setLod(zoneInfo.lod);
            this.renderZone(ctx, zone, zoneInfo.position, gameState);
        }
    }

    renderZone(ctx, zone, position, gameState) {
        // Calculate viewport position based on layout
        let x = 0, y = 0, width = ctx.canvas.width, height = ctx.canvas.height;

        switch (position) {
            case 'center':
                x = 0;
                y = 0;
                break;
            case 'west':
                x = -width;
                y = 0;
                break;
            case 'east':
                x = width;
                y = 0;
                break;
            case 'north':
                x = 0;
                y = -height;
                break;
            case 'south':
                x = 0;
                y = height;
                break;
        }

        ctx.save();
        ctx.translate(x, y);

        // Apply LOD effects
        if (zone.lod > 0) {
            ctx.globalAlpha = 1.0 - (zone.lod * 0.1);
            ctx.filter = `saturate(${100 - zone.lod * 20}%)`;
        }

        // Render terrain
        this.renderTerrain(ctx, zone);

        // Render entities
        this.renderEntities(ctx, zone);

        // Draw zone boundary for non-center zones
        if (position !== 'center') {
            ctx.strokeStyle = 'rgba(100, 100, 150, 0.2)';
            ctx.lineWidth = 2;
            ctx.strokeRect(0, 0, width, height);
        }

        ctx.restore();
    }

    renderTerrain(ctx, zone) {
        if (!zone.terrain || zone.terrain.length === 0) return;

        const tileSize = 32;
        let tileIndex = 0;

        for (let y = 0; y < ctx.canvas.height; y += tileSize) {
            for (let x = 0; x < ctx.canvas.width; x += tileSize) {
                if (tileIndex >= zone.terrain.length) break;

                const tile = zone.terrain[tileIndex];
                ctx.fillStyle = tile.color || '#2a4a6a';
                ctx.fillRect(x, y, tileSize, tileSize);

                // LOD: Skip some tiles if heavily reduced
                if (zone.lod >= 2 && tileIndex % 4 !== 0) {
                    tileIndex++;
                    continue;
                }

                // Draw border
                ctx.strokeStyle = 'rgba(100, 150, 200, 0.1)';
                ctx.lineWidth = 1;
                ctx.strokeRect(x, y, tileSize, tileSize);

                tileIndex++;
            }
        }
    }

    renderEntities(ctx, zone) {
        if (!zone.entities || zone.entities.length === 0) return;

        for (const entity of zone.entities) {
            // Scale entities based on LOD
            let scale = 1.0;
            if (zone.lod === 1) scale = 0.75;
            if (zone.lod === 2) scale = 0.5;

            // Skip some entities for distant zones
            if (zone.lod > 0 && Math.random() > 0.7) continue;

            ctx.save();
            ctx.globalAlpha = 1.0 - (zone.lod * 0.1);

            const x = entity.x || Math.random() * ctx.canvas.width;
            const y = entity.y || Math.random() * ctx.canvas.height;
            const size = (entity.size || 8) * scale;

            ctx.fillStyle = entity.color || '#78d8a7';
            ctx.beginPath();
            ctx.arc(x, y, size, 0, Math.PI * 2);
            ctx.fill();

            ctx.restore();
        }
    }

    moveToAdjacentZone(direction) {
        return this.zoneManager.moveToZone(direction);
    }
}
