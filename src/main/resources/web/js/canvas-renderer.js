/**
 * AmbonMUD Canvas Rendering System
 * Phase 4: Canvas-Based World Rendering
 *
 * Implements a 5-layer rendering system with parallax backgrounds,
 * entity rendering, particle effects, and interactive UI overlays.
 */

// ========== LAYER SYSTEM ==========

class Layer {
    constructor(name = 'layer') {
        this.name = name;
        this.elements = [];
    }

    add(element) {
        this.elements.push(element);
        return this;
    }

    clear() {
        this.elements = [];
        return this;
    }

    render(ctx, gameState) {
        for (const elem of this.elements) {
            if (elem.render) {
                elem.render(ctx, gameState);
            }
        }
    }

    remove(element) {
        const idx = this.elements.indexOf(element);
        if (idx >= 0) {
            this.elements.splice(idx, 1);
        }
        return this;
    }
}

// ========== DESIGN TOKENS LOADER ==========

class DesignTokens {
    constructor() {
        this.tokens = {};
        this.loadFromCSS();
    }

    loadFromCSS() {
        // Extract CSS custom properties from :root
        const root = document.documentElement;
        const computed = getComputedStyle(root);

        const colorTokens = [
            '--bg-primary',
            '--bg-secondary',
            '--color-primary-lavender',
            '--color-primary-pale-blue',
            '--color-primary-moss-green',
            '--color-primary-soft-gold',
            '--color-error',
            '--text-primary',
            '--text-secondary',
            '--radius-sm',
            '--radius-md',
            '--radius-lg',
        ];

        for (const token of colorTokens) {
            const value = computed.getPropertyValue(token).trim();
            this.tokens[token] = value;
        }
    }

    get(key, fallback = '#000000') {
        return this.tokens[key] || fallback;
    }

    color(name) {
        return this.get(`--color-${name}`, '#000000');
    }

    bg(name = 'primary') {
        return this.get(`--bg-${name}`, '#E8E8F0');
    }
}

// ========== PARTICLE SYSTEM ==========

class Particle {
    constructor(config) {
        this.x = config.x;
        this.y = config.y;
        this.type = config.type || 'spark'; // spark, glow, text, aoe
        this.color = config.color || '#D8C5E8';
        this.duration = config.duration || 600;
        this.velocity = config.velocity || { x: 0, y: 0 };
        this.text = config.text || '';
        this.radius = config.radius || 0;

        this.startTime = performance.now();
        this.life = 0;
    }

    update() {
        const now = performance.now();
        this.life = (now - this.startTime) / this.duration;
        this.x += this.velocity.x;
        this.y += this.velocity.y;
        return this.life >= 1;
    }

    getOpacity() {
        return Math.max(0, 1 - this.life);
    }
}

class ParticleSystem {
    constructor() {
        this.particles = [];
    }

    emit(config) {
        this.particles.push(new Particle(config));
    }

    emitBurst(x, y, color, count = 12, speed = 2, duration = 600) {
        for (let i = 0; i < count; i++) {
            const angle = (i / count) * Math.PI * 2;
            this.emit({
                x,
                y,
                type: 'spark',
                color,
                duration,
                velocity: {
                    x: Math.cos(angle) * speed,
                    y: Math.sin(angle) * speed,
                },
            });
        }
    }

    emitGlow(x, y, color, duration = 300) {
        this.emit({
            x,
            y,
            type: 'glow',
            color,
            duration,
            velocity: { x: 0, y: 0 },
        });
    }

    emitDamage(x, y, damage) {
        this.emit({
            x,
            y,
            type: 'text',
            text: `-${damage}`,
            color: '#E8C5A8',
            duration: 1000,
            velocity: { x: 0, y: -1 },
        });
    }

    update() {
        for (let i = this.particles.length - 1; i >= 0; i--) {
            if (this.particles[i].update()) {
                this.particles.splice(i, 1);
            }
        }
    }

    render(ctx) {
        for (const p of this.particles) {
            ctx.globalAlpha = p.getOpacity();

            switch (p.type) {
                case 'spark':
                    ctx.fillStyle = p.color;
                    ctx.beginPath();
                    ctx.arc(p.x, p.y, 2 * (1 - p.life), 0, Math.PI * 2);
                    ctx.fill();
                    break;

                case 'glow':
                    ctx.fillStyle = p.color;
                    ctx.beginPath();
                    ctx.arc(p.x, p.y, 8 * (1 - p.life), 0, Math.PI * 2);
                    ctx.fill();
                    break;

                case 'text':
                    ctx.fillStyle = p.color;
                    ctx.font = 'bold 14px sans-serif';
                    ctx.textAlign = 'center';
                    ctx.fillText(p.text, p.x, p.y);
                    break;
            }

            ctx.globalAlpha = 1;
        }
    }

    clear() {
        this.particles = [];
    }
}

// ========== CANVAS WORLD RENDERER ==========

class CanvasWorldRenderer {
    constructor(canvas, designTokens) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
        this.designTokens = designTokens;

        // Layers
        this.layers = {
            background: new Layer('background'),
            terrain: new Layer('terrain'),
            entities: new Layer('entities'),
            effects: new Layer('effects'),
            ui: new Layer('ui'),
        };

        this.particleSystem = new ParticleSystem();
        this.animationFrame = null;
        this.isRendering = false;

        // Game state
        this.gameState = {
            playerPos: { x: 0, y: 0 },
            currentRoom: null,
            mobs: [],
            playersHere: [],
            activeAoE: [],
            hoveredElement: null,
        };

        // Setup canvas
        this.setupCanvas();
    }

    setupCanvas() {
        // Set DPI scaling for retina displays
        const dpr = window.devicePixelRatio || 1;
        const rect = this.canvas.getBoundingClientRect();
        this.canvas.width = rect.width * dpr;
        this.canvas.height = rect.height * dpr;
        this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

        this.width = rect.width;
        this.height = rect.height;

        // Handle window resize
        window.addEventListener('resize', () => this.setupCanvas());
    }

    updateGameState(state) {
        this.gameState = { ...this.gameState, ...state };
    }

    render() {
        // Clear canvas
        const bgColor = this.designTokens.bg('primary');
        this.ctx.fillStyle = bgColor;
        this.ctx.fillRect(0, 0, this.width, this.height);

        // Check for multi-zone rendering (Phase 5b)
        if (this.gameState.multiZoneRenderer) {
            // Multi-zone rendering path
            this.gameState.multiZoneRenderer.renderZones(this.ctx, this.gameState);
        } else {
            // Single-zone rendering path (default, Phase 4)
            this.layers.background.render(this.ctx, this.gameState);
            this.layers.terrain.render(this.ctx, this.gameState);
            this.layers.entities.render(this.ctx, this.gameState);
            this.layers.effects.render(this.ctx, this.gameState);
        }

        this.particleSystem.render(this.ctx);
        this.layers.ui.render(this.ctx, this.gameState);
    }

    update() {
        this.particleSystem.update();
    }

    scheduleRender() {
        if (!this.isRendering) {
            this.isRendering = true;
            this.animationFrame = requestAnimationFrame(() => {
                this.update();
                this.render();
                this.isRendering = false;
            });
        }
    }

    destroy() {
        if (this.animationFrame) {
            cancelAnimationFrame(this.animationFrame);
        }
        this.particleSystem.clear();
    }

    /**
     * Update compass element based on room exits
     */
    updateCompass() {
        const compass = document.getElementById('compass');
        if (!compass || !this.gameState.exits) return;

        const exits = this.gameState.exits;
        const directions = Object.keys(exits);

        // Determine primary direction (for compass display)
        let directionText = 'N';
        if (directions.length > 0) {
            directionText = directions[0].toUpperCase();
        }

        compass.textContent = directionText;
    }
}

// ========== BACKGROUND LAYER ==========

class BackgroundLayer extends Layer {
    constructor(designTokens) {
        super('background');
        this.designTokens = designTokens;
        this.parallaxDepth = 0;
        this.scrollOffset = { x: 0, y: 0 };
        this.time = 0; // For animated effects
    }

    render(ctx, gameState) {
        this.time += 0.016; // ~60fps delta

        // Main sky gradient (use Phase 5a time-of-day if available, otherwise default)
        const gradient = ctx.createLinearGradient(0, 0, 0, ctx.canvas.height);

        if (gameState.skyGradient && Array.isArray(gameState.skyGradient)) {
            // Use dynamic gradient from TimeOfDaySystem
            for (const stop of gameState.skyGradient) {
                gradient.addColorStop(stop.offset, stop.color);
            }
        } else {
            // Default fallback gradient (warm golden to lavender)
            gradient.addColorStop(0, '#E8D8D0');   // Sky top (warm)
            gradient.addColorStop(0.3, '#F0E8E0'); // Upper air
            gradient.addColorStop(0.6, '#F8F0F0'); // Mid air
            gradient.addColorStop(1, '#E8E8F0');   // Ground (lavender tint)
        }

        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, ctx.canvas.width, ctx.canvas.height);

        // Parallax background layers
        const playerPos = gameState.playerPos || { x: 200, y: 150 };

        // Far layer (mountains/landscape)
        this.renderFarLayer(ctx, playerPos, ctx.canvas.width, ctx.canvas.height);

        // Mid layer (hills/trees)
        this.renderMidLayer(ctx, playerPos, ctx.canvas.width, ctx.canvas.height);

        // Near layer (close objects)
        this.renderNearLayer(ctx, playerPos, ctx.canvas.width, ctx.canvas.height);

        // Ambient fog effect (subtle)
        this.renderFog(ctx, ctx.canvas.width, ctx.canvas.height);
    }

    renderFarLayer(ctx, playerPos, width, height) {
        // Far mountains - parallax at 1/4 speed
        ctx.globalAlpha = 0.15;

        const parallaxX = (playerPos.x / 4) % (width * 2);
        const parallaxY = (playerPos.y / 4) % (height * 2);

        // Draw distant horizon line
        ctx.strokeStyle = 'rgba(200, 200, 200, 0.3)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(0 - parallaxX, height * 0.4 - parallaxY);
        ctx.lineTo(width - parallaxX, height * 0.4 - parallaxY);
        ctx.stroke();

        // Distant hills
        ctx.fillStyle = 'rgba(184, 200, 216, 0.2)';
        for (let i = 0; i < 3; i++) {
            const x = (i * 400 - parallaxX) % (width + 400);
            const y = height * 0.35;
            ctx.beginPath();
            ctx.arc(x, y, 80, Math.PI, 0);
            ctx.fill();
        }

        ctx.globalAlpha = 1;
    }

    renderMidLayer(ctx, playerPos, width, height) {
        // Mid-distance trees/objects - parallax at 1/2 speed
        ctx.globalAlpha = 0.25;

        const parallaxX = (playerPos.x / 2) % (width * 2);
        const parallaxY = (playerPos.y / 2) % (height * 2);

        ctx.fillStyle = 'rgba(168, 200, 168, 0.25)';

        // Tree clusters
        for (let i = 0; i < 4; i++) {
            const x = (i * 250 - parallaxX) % (width + 300);
            const y = height * 0.5;

            // Tree trunk
            ctx.fillRect(x - 8, y, 16, 40);

            // Tree canopy
            ctx.beginPath();
            ctx.arc(x, y - 10, 30, Math.PI, 0);
            ctx.fill();
        }

        ctx.globalAlpha = 1;
    }

    renderNearLayer(ctx, playerPos, width, height) {
        // Close foreground - parallax at 3/4 speed (almost full)
        ctx.globalAlpha = 0.35;

        const parallaxX = (playerPos.x * 0.75) % (width * 1.5);
        const parallaxY = (playerPos.y * 0.75) % (height * 1.5);

        // Ground vegetation
        ctx.fillStyle = 'rgba(197, 216, 168, 0.3)';

        for (let i = 0; i < 8; i++) {
            const x = (i * 150 - parallaxX) % (width + 200);
            const y = height * 0.7;

            // Flower/plant cluster
            ctx.beginPath();
            ctx.ellipse(x, y, 15, 25, 0, 0, Math.PI * 2);
            ctx.fill();
        }

        ctx.globalAlpha = 1;
    }

    renderFog(ctx, width, height) {
        // Ambient fog effect (very subtle)
        const gradient = ctx.createLinearGradient(0, 0, 0, height);
        gradient.addColorStop(0, 'rgba(232, 224, 216, 0)');
        gradient.addColorStop(0.7, 'rgba(232, 224, 216, 0.02)');
        gradient.addColorStop(1, 'rgba(232, 224, 216, 0.05)');

        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, width, height);
    }
}

// ========== TERRAIN LAYER ==========

class TerrainLayer extends Layer {
    constructor(designTokens) {
        super('terrain');
        this.designTokens = designTokens;
    }

    render(ctx, gameState) {
        const room = gameState.currentRoom;
        if (!room) return;

        const tileSize = 20;
        const width = room.width || 15;
        const height = room.height || 12;

        // Render walkable tiles
        for (let y = 0; y < height; y++) {
            for (let x = 0; x < width; x++) {
                const tileType = room.terrain?.[y]?.[x] || 'grass';
                const x_px = x * tileSize;
                const y_px = y * tileSize;
                this.drawTile(ctx, x_px, y_px, tileSize, tileType);
            }
        }

        // Render walls/obstacles
        if (room.obstacles) {
            for (const obs of room.obstacles) {
                this.drawObstacle(ctx, obs);
            }
        }
    }

    drawTile(ctx, x, y, size, type) {
        const colors = {
            grass: '#D8E8B8',
            stone: '#D8D8D0',
            water: '#B8D8E8',
            dirt: '#D8C8A8',
            sand: '#E8D8A8',
            forest: '#B8C8A8',
            mountain: '#C8C8C0',
        };

        // Base tile color
        ctx.fillStyle = colors[type] || colors.grass;
        ctx.fillRect(x, y, size, size);

        // Add subtle texture pattern
        ctx.strokeStyle = 'rgba(184, 216, 232, 0.15)';
        ctx.lineWidth = 0.5;
        ctx.strokeRect(x, y, size, size);

        // Diagonal pattern for special tiles
        if (type === 'water') {
            ctx.strokeStyle = 'rgba(168, 200, 232, 0.2)';
            ctx.beginPath();
            ctx.moveTo(x, y);
            ctx.lineTo(x + size, y + size);
            ctx.stroke();
        }

        if (type === 'forest') {
            // Add dot to indicate forest
            ctx.fillStyle = 'rgba(168, 200, 168, 0.3)';
            ctx.beginPath();
            ctx.arc(x + size / 2, y + size / 2, 2, 0, Math.PI * 2);
            ctx.fill();
        }
    }

    drawObstacle(ctx, obstacle) {
        ctx.fillStyle = 'rgba(107, 107, 123, 0.3)';
        ctx.fillRect(obstacle.x, obstacle.y, obstacle.width, obstacle.height);

        // Border
        ctx.strokeStyle = 'rgba(107, 107, 123, 0.5)';
        ctx.lineWidth = 1;
        ctx.strokeRect(obstacle.x, obstacle.y, obstacle.width, obstacle.height);
    }
}

// ========== ENTITY LAYER ==========

class EntityLayer extends Layer {
    constructor(designTokens) {
        super('entities');
        this.designTokens = designTokens;
    }

    render(ctx, gameState) {
        // Player (center)
        if (gameState.playerPos) {
            this.drawEntity(ctx, gameState.playerPos, {
                type: 'player',
                name: gameState.playerName || 'Player',
                color: '#D8C5E8',
                glow: true,
            });
        }

        // Mobs
        if (gameState.mobs) {
            for (const mob of gameState.mobs) {
                const pos = mob.pos || { x: mob.gridX * 20, y: mob.gridY * 20 };
                this.drawEntity(ctx, pos, {
                    type: 'mob',
                    name: mob.name,
                    hp: mob.hp,
                    maxHp: mob.maxHp,
                    color: this.getMobColor(mob),
                    glow: mob.threat > 0,
                });
            }
        }

        // Other players
        if (gameState.playersHere) {
            for (const player of gameState.playersHere) {
                const pos = player.pos || { x: player.gridX * 20, y: player.gridY * 20 };
                this.drawEntity(ctx, pos, {
                    type: 'player-other',
                    name: player.name,
                    color: '#B8D8E8',
                });
            }
        }
    }

    drawEntity(ctx, pos, config) {
        const size = config.type === 'player' ? 14 : 12;
        const isPlayer = config.type === 'player' || config.type === 'player-other';

        // Draw entity circle
        ctx.fillStyle = config.color;
        ctx.beginPath();
        ctx.arc(pos.x, pos.y, size, 0, Math.PI * 2);
        ctx.fill();

        // Border
        ctx.strokeStyle = isPlayer ? 'rgba(255, 255, 255, 0.4)' : 'rgba(0, 0, 0, 0.2)';
        ctx.lineWidth = isPlayer ? 2 : 1;
        ctx.stroke();

        // Glow effect if threatened/important
        if (config.glow) {
            // Pulsing threat indicator
            const pulse = Math.sin(performance.now() / 150) * 0.5 + 0.5;
            ctx.strokeStyle = `rgba(232, 168, 168, ${0.3 + pulse * 0.3})`;
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.arc(pos.x, pos.y, size + 3, 0, Math.PI * 2);
            ctx.stroke();
        }

        // Health bar above entity
        if (config.hp !== undefined && config.hp > 0) {
            this.drawHealthBar(ctx, pos, config.hp, config.maxHp, size);
        }

        // Player indicator (crown for player, asterisk for others)
        if (isPlayer) {
            ctx.fillStyle = 'rgba(255, 255, 255, 0.8)';
            ctx.font = 'bold 8px sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(config.type === 'player' ? '●' : '◯', pos.x, pos.y - size - 10);
        }
    }

    drawHealthBar(ctx, pos, hp, maxHp, entitySize = 12) {
        const barWidth = 24;
        const barHeight = 4;
        const barX = pos.x - barWidth / 2;
        const barY = pos.y - entitySize - 10;
        const hpPercent = Math.max(0, Math.min(1, hp / maxHp));

        // Background (dark)
        ctx.fillStyle = 'rgba(107, 107, 123, 0.5)';
        ctx.roundRect(barX, barY, barWidth, barHeight, 2);
        ctx.fill();

        // Health gradient
        const gradient = ctx.createLinearGradient(barX, barY, barX + barWidth * hpPercent, barY);

        if (hpPercent > 0.5) {
            // Green: healthy
            gradient.addColorStop(0, '#C5D8A8');
            gradient.addColorStop(1, '#B8D8A0');
        } else if (hpPercent > 0.25) {
            // Yellow: wounded
            gradient.addColorStop(0, '#E8D8A8');
            gradient.addColorStop(1, '#D8C8A0');
        } else {
            // Red: critical
            gradient.addColorStop(0, '#E8C5A8');
            gradient.addColorStop(1, '#D8B5A0');
        }

        ctx.fillStyle = gradient;
        ctx.roundRect(barX, barY, barWidth * hpPercent, barHeight, 2);
        ctx.fill();

        // Border
        ctx.strokeStyle = 'rgba(107, 107, 123, 0.4)';
        ctx.lineWidth = 1;
        ctx.roundRect(barX, barY, barWidth, barHeight, 2);
        ctx.stroke();
    }

    getMobColor(mob) {
        if (mob.threat > 0) return '#E8C5A8'; // Aggressive: warm
        if (mob.hp < mob.maxHp * 0.3) return '#C5A8A8'; // Weak: desaturated red
        return '#A8C5D8'; // Neutral: pale blue
    }
}

// ========== EFFECTS LAYER ==========

class EffectsLayer extends Layer {
    constructor(designTokens, particleSystem) {
        super('effects');
        this.designTokens = designTokens;
        this.particleSystem = particleSystem;
    }

    triggerSpellEffect(pos, color = '#E8D8A8') {
        this.particleSystem.emitBurst(pos.x, pos.y, color);
    }

    triggerGlowEffect(pos, color = '#B8D8E8', duration = 300) {
        this.particleSystem.emitGlow(pos.x, pos.y, color, duration);
    }

    drawDamageNumber(pos, damage, color = '#E8C5A8') {
        this.particleSystem.emitDamage(pos.x, pos.y, damage);
    }

    render(ctx, gameState) {
        // Draw AoE circles for active effects
        if (gameState.activeAoE) {
            for (const aoe of gameState.activeAoE) {
                this.drawAoECircle(ctx, aoe);
            }
        }
    }

    drawAoECircle(ctx, aoe) {
        ctx.strokeStyle = 'rgba(232, 200, 200, 0.5)';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(aoe.x, aoe.y, aoe.radius, 0, Math.PI * 2);
        ctx.stroke();

        // Pulsing animation
        const pulse = Math.sin(performance.now() / 200) * 0.5 + 0.5;
        ctx.fillStyle = `rgba(232, 200, 200, ${0.1 * pulse})`;
        ctx.beginPath();
        ctx.arc(aoe.x, aoe.y, aoe.radius, 0, Math.PI * 2);
        ctx.fill();
    }
}

// ========== UI OVERLAY LAYER ==========

class UIOverlayLayer extends Layer {
    constructor(designTokens) {
        super('ui');
        this.designTokens = designTokens;
    }

    render(ctx, gameState) {
        if (!gameState.currentRoom) return;

        // Render exit portals
        if (gameState.exits) {
            for (const exit of gameState.exits) {
                this.drawExitPortal(ctx, exit);
            }
        }

        // Render tooltip if hovering
        if (gameState.hoveredElement) {
            this.drawTooltip(ctx, gameState.hoveredElement);
        }
    }

    drawExitPortal(ctx, exit) {
        const lavender = this.designTokens.color('primary-lavender');

        // Soft lavender portal frame
        ctx.strokeStyle = lavender;
        ctx.lineWidth = 2;
        ctx.fillStyle = `rgba(216, 197, 232, 0.1)`;

        ctx.beginPath();
        ctx.rect(exit.x, exit.y, 40, 40);
        ctx.fill();
        ctx.stroke();

        // Glow effect
        ctx.shadowColor = `rgba(216, 197, 232, 0.5)`;
        ctx.shadowBlur = 8;
        ctx.strokeStyle = `rgba(216, 197, 232, 0.3)`;
        ctx.stroke();
        ctx.shadowColor = 'transparent';

        // Exit label
        ctx.fillStyle = lavender;
        ctx.font = '10px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText(exit.direction.toUpperCase(), exit.x + 20, exit.y + 25);
    }

    drawTooltip(ctx, element) {
        const x = element.x + 15;
        const y = element.y - 15;
        const padding = 4;
        const text = element.name;

        // Measure text
        ctx.font = '12px sans-serif';
        const metrics = ctx.measureText(text);
        const width = metrics.width + padding * 2;
        const height = 16;

        // Draw tooltip background
        ctx.fillStyle = 'rgba(107, 107, 123, 0.8)';
        ctx.beginPath();
        ctx.roundRect(x, y, width, height, 3);
        ctx.fill();

        // Draw text
        ctx.fillStyle = '#E8E8F0';
        ctx.fillText(text, x + padding, y + 12);
    }
}

// ========== EXPORT ==========

if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        CanvasWorldRenderer,
        Layer,
        ParticleSystem,
        DesignTokens,
        BackgroundLayer,
        TerrainLayer,
        EntityLayer,
        EffectsLayer,
        UIOverlayLayer,
    };
}
