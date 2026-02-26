/**
 * Multi-Zone Renderer (Phase 5b)
 * Renders multiple zones simultaneously with LOD and performance optimizations
 * Extends the Phase 4 canvas rendering system
 */

class MultiZoneRenderer {
    constructor(renderer, camera, zoneManager) {
        this.renderer = renderer;
        this.camera = camera;
        this.zoneManager = zoneManager;

        // Rendering state
        this.isMultiZoneEnabled = true;
        this.zoneBoundariesVisible = true;
        this.debugMode = false;

        // Performance tracking
        this.renderedZones = 0;
        this.renderedTiles = 0;
        this.renderedEntities = 0;

        // Tile cache for performance
        this.tileCache = new Map(); // "zoneId:tileIndex" -> renderedTile
        this.tileCacheMaxSize = 1000;
    }

    /**
     * Render all visible zones
     */
    renderZones(ctx, gameState) {
        if (!this.isMultiZoneEnabled) {
            // Single-zone rendering (fallback to original system)
            return this.renderSingleZone(ctx, gameState);
        }

        this.renderedZones = 0;
        this.renderedTiles = 0;
        this.renderedEntities = 0;

        const visibleZones = this.zoneManager.getVisibleZonesInRenderOrder();

        for (const zone of visibleZones) {
            if (!zone.isVisible) continue;

            // Apply zone-specific canvas transform
            ctx.save();

            const boundary = this.zoneManager.getZoneBoundary(zone.id);
            if (boundary) {
                ctx.globalAlpha = zone.opacity;
                ctx.translate(boundary.x, boundary.y);

                // Render zone layers
                this.renderZoneBackground(ctx, zone);
                this.renderZoneTerrain(ctx, zone);
                this.renderZoneEntities(ctx, zone, gameState);
                this.renderZoneBoundary(ctx, zone, boundary);
            }

            ctx.restore();
            this.renderedZones++;
        }

        // Render zone labels in debug mode
        if (this.debugMode) {
            this.renderDebugOverlay(ctx, gameState);
        }
    }

    /**
     * Render zone background (sky, weather, etc)
     */
    renderZoneBackground(ctx, zone) {
        // Use zone's ambiance if available, otherwise use global
        const ambiance = zone.ambiance || {};

        // Simple background for now (sky gradient)
        const gradient = ctx.createLinearGradient(0, 0, 0, ctx.canvas.height);
        gradient.addColorStop(0, '#87ceeb'); // Sky blue
        gradient.addColorStop(1, '#e8e8f0'); // Ground lavender
        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, zone.width * 20, zone.height * 20);

        // Render weather overlay if different from current zone
        if (zone.id !== this.zoneManager.currentZoneId && ambiance.weather) {
            this.renderWeatherOverlay(ctx, zone, ambiance.weather);
        }
    }

    /**
     * Render weather overlay for zone
     */
    renderWeatherOverlay(ctx, zone, weather) {
        const weatherColors = {
            clear: 'rgba(255, 255, 255, 0)',
            cloudy: 'rgba(200, 200, 200, 0.1)',
            rain: 'rgba(100, 120, 140, 0.15)',
            fog: 'rgba(200, 210, 220, 0.25)',
            snow: 'rgba(240, 240, 255, 0.1)',
            storm: 'rgba(80, 100, 120, 0.2)',
        };

        const color = weatherColors[weather] || weatherColors.clear;
        ctx.fillStyle = color;
        ctx.fillRect(0, 0, zone.width * 20, zone.height * 20);
    }

    /**
     * Render zone terrain tiles
     */
    renderZoneTerrain(ctx, zone) {
        const tilesToRender = zone.getTilesToRender();
        const tileSize = 20;

        for (const tile of tilesToRender) {
            if (!tile) continue;

            const tileX = (tile.gridX || 0) * tileSize;
            const tileY = (tile.gridY || 0) * tileSize;

            // Check if tile is in viewport
            if (!this.isTileInViewport(tileX, tileY, tileSize, ctx.canvas)) {
                continue;
            }

            // Render tile with LOD color modulation
            this.renderTerrainTile(ctx, tile, tileX, tileY, zone);
            this.renderedTiles++;
        }
    }

    /**
     * Render single terrain tile
     */
    renderTerrainTile(ctx, tile, x, y, zone) {
        const tileSize = 20;
        const colors = {
            grass: '#D8E8B8',
            stone: '#D8D8D0',
            water: '#B8D8E8',
            dirt: '#D8C8A8',
            sand: '#E8D8A8',
            forest: '#B8C8A8',
            mountain: '#C8C8C0',
        };

        // Apply LOD color reduction for non-current zones
        let color = colors[tile.type] || colors.grass;
        if (zone.id !== this.zoneManager.currentZoneId) {
            color = this.desaturateColor(color, 0.3 + (zone.lodLevel * 0.1));
        }

        ctx.fillStyle = color;
        ctx.fillRect(x, y, tileSize, tileSize);

        // Draw grid
        ctx.strokeStyle = 'rgba(184, 216, 232, 0.1)';
        ctx.lineWidth = 0.5;
        ctx.strokeRect(x, y, tileSize, tileSize);

        // Obstacles
        if (tile.obstacle) {
            ctx.fillStyle = 'rgba(100, 100, 100, 0.4)';
            ctx.fillRect(x + 2, y + 2, tileSize - 4, tileSize - 4);
        }
    }

    /**
     * Render zone entities (mobs, players)
     */
    renderZoneEntities(ctx, zone, gameState) {
        const mobsToRender = zone.getMobsToRender();

        for (const mob of mobsToRender) {
            if (!mob) continue;

            const pos = {
                x: (mob.gridX || 0) * 20 + 10,
                y: (mob.gridY || 0) * 20 + 10,
            };

            this.renderEntity(ctx, mob, pos, zone);
            this.renderedEntities++;
        }

        // Render players in zone
        for (const player of zone.players || []) {
            if (!player) continue;

            const pos = {
                x: (player.gridX || 0) * 20 + 10,
                y: (player.gridY || 0) * 20 + 10,
            };

            this.renderEntity(ctx, player, pos, zone, true);
            this.renderedEntities++;
        }

        // Highlight current player in current zone
        if (zone.id === this.zoneManager.currentZoneId && gameState.playerPos) {
            this.renderPlayerHighlight(ctx, gameState.playerPos);
        }
    }

    /**
     * Render entity (mob or player)
     */
    renderEntity(ctx, entity, pos, zone, isPlayer = false) {
        const size = isPlayer ? 14 : 12;
        const color = isPlayer ? '#D8C5E8' : (entity.color || '#C8A8B8');

        // Reduce size for non-current zones
        const scaledSize = zone.id === this.zoneManager.currentZoneId
            ? size
            : Math.max(3, size - 4);

        ctx.fillStyle = color;
        ctx.globalAlpha = zone.opacity * (zone.id === this.zoneManager.currentZoneId ? 1 : 0.7);
        ctx.beginPath();
        ctx.arc(pos.x, pos.y, scaledSize, 0, Math.PI * 2);
        ctx.fill();
        ctx.globalAlpha = 1;

        // Health bar for current zone mobs only
        if (zone.id === this.zoneManager.currentZoneId && entity.hp !== undefined) {
            this.renderEntityHealthBar(ctx, pos, entity, scaledSize);
        }
    }

    /**
     * Render entity health bar
     */
    renderEntityHealthBar(ctx, pos, entity, entitySize = 12) {
        const barWidth = 24;
        const barHeight = 4;
        const barX = pos.x - barWidth / 2;
        const barY = pos.y - entitySize - 8;

        const hpPercent = Math.max(0, Math.min(1, entity.hp / (entity.maxHp || 100)));
        const fillWidth = barWidth * hpPercent;

        // Background
        ctx.fillStyle = 'rgba(0, 0, 0, 0.5)';
        ctx.fillRect(barX, barY, barWidth, barHeight);

        // Health color
        let healthColor = '#C5D8A8'; // Green
        if (hpPercent < 0.5) healthColor = '#E8D8A8'; // Yellow
        if (hpPercent < 0.25) healthColor = '#E8C5A8'; // Red

        ctx.fillStyle = healthColor;
        ctx.fillRect(barX, barY, fillWidth, barHeight);

        // Border
        ctx.strokeStyle = 'rgba(255, 255, 255, 0.3)';
        ctx.lineWidth = 1;
        ctx.strokeRect(barX, barY, barWidth, barHeight);
    }

    /**
     * Render player highlight in current zone
     */
    renderPlayerHighlight(ctx, playerPos) {
        ctx.strokeStyle = 'rgba(216, 197, 232, 0.6)';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(playerPos.x, playerPos.y, 16, 0, Math.PI * 2);
        ctx.stroke();

        // Pulsing outer ring
        const pulse = Math.sin(performance.now() / 200) * 0.5 + 0.5;
        ctx.strokeStyle = `rgba(216, 197, 232, ${0.3 * pulse})`;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.arc(playerPos.x, playerPos.y, 18 + pulse * 4, 0, Math.PI * 2);
        ctx.stroke();
    }

    /**
     * Render zone boundaries (subtle dividers)
     */
    renderZoneBoundary(ctx, zone, boundary) {
        if (!this.zoneBoundariesVisible) return;

        // Don't render boundary for current zone
        if (zone.id === this.zoneManager.currentZoneId) return;

        const width = boundary.width;
        const height = boundary.height;

        // Right edge
        if (boundary.col < 2) {
            ctx.strokeStyle = 'rgba(216, 197, 232, 0.2)';
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(width, 0);
            ctx.lineTo(width, height);
            ctx.stroke();
        }

        // Bottom edge
        if (boundary.row < 2) {
            ctx.strokeStyle = 'rgba(216, 197, 232, 0.2)';
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(0, height);
            ctx.lineTo(width, height);
            ctx.stroke();
        }

        // Zone label (top-left corner)
        ctx.fillStyle = 'rgba(216, 197, 232, 0.3)';
        ctx.font = '12px sans-serif';
        ctx.fillText(zone.title || zone.id, 4, 16);
    }

    /**
     * Render debug overlay with performance stats
     */
    renderDebugOverlay(ctx, gameState) {
        ctx.save();

        ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
        ctx.fillRect(10, 10, 200, 100);

        ctx.fillStyle = '#ffffff';
        ctx.font = '12px monospace';
        ctx.fillText(`Zones: ${this.renderedZones}`, 20, 30);
        ctx.fillText(`Tiles: ${this.renderedTiles}`, 20, 45);
        ctx.fillText(`Entities: ${this.renderedEntities}`, 20, 60);
        ctx.fillText(`Current: ${this.zoneManager.currentZoneId}`, 20, 75);
        ctx.fillText(`Layout: ${this.zoneManager.viewportLayout}`, 20, 90);

        ctx.restore();
    }

    /**
     * Render single zone (fallback mode)
     */
    renderSingleZone(ctx, gameState) {
        const currentZone = this.zoneManager.getCurrentZone();
        if (!currentZone) return;

        ctx.save();
        this.renderZoneBackground(ctx, currentZone);
        this.renderZoneTerrain(ctx, currentZone);
        this.renderZoneEntities(ctx, currentZone, gameState);
        ctx.restore();
    }

    /**
     * Check if tile is in viewport
     */
    isTileInViewport(x, y, size, canvas) {
        return x + size > 0 && x < canvas.width &&
               y + size > 0 && y < canvas.height;
    }

    /**
     * Desaturate color for LOD effect
     */
    desaturateColor(hexColor, factor) {
        const rgb = this.hexToRgb(hexColor);
        const gray = (rgb.r + rgb.g + rgb.b) / 3;
        const r = Math.round(rgb.r + (gray - rgb.r) * factor);
        const g = Math.round(rgb.g + (gray - rgb.g) * factor);
        const b = Math.round(rgb.b + (gray - rgb.b) * factor);
        return this.rgbToHex(r, g, b);
    }

    /**
     * Toggle multi-zone rendering
     */
    toggleMultiZone() {
        this.isMultiZoneEnabled = !this.isMultiZoneEnabled;
        return this.isMultiZoneEnabled;
    }

    /**
     * Toggle zone boundaries
     */
    toggleZoneBoundaries() {
        this.zoneBoundariesVisible = !this.zoneBoundariesVisible;
        return this.zoneBoundariesVisible;
    }

    /**
     * Toggle debug mode
     */
    toggleDebugMode() {
        this.debugMode = !this.debugMode;
        return this.debugMode;
    }

    // ========== UTILITY METHODS ==========

    hexToRgb(hex) {
        const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
        return result ? {
            r: parseInt(result[1], 16),
            g: parseInt(result[2], 16),
            b: parseInt(result[3], 16),
        } : { r: 0, g: 0, b: 0 };
    }

    rgbToHex(r, g, b) {
        return '#' + [r, g, b].map(x => {
            const hex = x.toString(16);
            return hex.length === 1 ? '0' + hex : hex;
        }).join('');
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = MultiZoneRenderer;
}
