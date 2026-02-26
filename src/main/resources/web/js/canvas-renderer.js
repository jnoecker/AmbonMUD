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

        // Render layers in order
        this.layers.background.render(this.ctx, this.gameState);
        this.layers.terrain.render(this.ctx, this.gameState);
        this.layers.entities.render(this.ctx, this.gameState);
        this.layers.effects.render(this.ctx, this.gameState);
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
}

// ========== BACKGROUND LAYER ==========

class BackgroundLayer extends Layer {
    constructor(designTokens) {
        super('background');
        this.designTokens = designTokens;
        this.parallaxDepth = 0;
        this.scrollOffset = { x: 0, y: 0 };
    }

    render(ctx, gameState) {
        // Sky gradient (warm golden to lavender)
        const gradient = ctx.createLinearGradient(0, 0, 0, ctx.canvas.height);
        gradient.addColorStop(0, '#E8D8D0');   // Sky top
        gradient.addColorStop(0.5, '#F8F0E8'); // Sky middle
        gradient.addColorStop(1, '#E8E8F0');   // Ground
        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, ctx.canvas.width, ctx.canvas.height);

        // Parallax layers
        const playerPos = gameState.playerPos || { x: 200, y: 150 };
        for (let depth = 1; depth <= 3; depth++) {
            const parallaxX = (playerPos.x / depth) % ctx.canvas.width;
            const parallaxY = (playerPos.y / depth) % ctx.canvas.height;
            this.renderParallaxLayer(ctx, depth, parallaxX, parallaxY, ctx.canvas.width, ctx.canvas.height);
        }
    }

    renderParallaxLayer(ctx, depth, offsetX, offsetY, width, height) {
        // Opacity decreases with depth (aerial perspective)
        ctx.globalAlpha = 1 - (depth * 0.15);

        // Background elements (simplified for now)
        // Could be enhanced with actual parallax images
        ctx.fillStyle = `rgba(184, 216, 232, ${0.1 * (1 - depth * 0.1)})`;
        ctx.fillRect(0, 0, width, height);

        ctx.globalAlpha = 1;
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
        };
        ctx.fillStyle = colors[type] || colors.grass;
        ctx.fillRect(x, y, size, size);

        // Subtle grid
        ctx.strokeStyle = 'rgba(184, 216, 232, 0.1)';
        ctx.lineWidth = 1;
        ctx.strokeRect(x, y, size, size);
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
        const size = 12;
        ctx.fillStyle = config.color;
        ctx.beginPath();
        ctx.arc(pos.x, pos.y, size, 0, Math.PI * 2);
        ctx.fill();

        // Glow effect if threatened/important
        if (config.glow) {
            ctx.strokeStyle = 'rgba(232, 168, 168, 0.5)';
            ctx.lineWidth = 2;
            ctx.stroke();
        }

        // Health bar above entity
        if (config.hp !== undefined) {
            this.drawHealthBar(ctx, pos, config.hp, config.maxHp);
        }
    }

    drawHealthBar(ctx, pos, hp, maxHp) {
        const barWidth = 20;
        const barHeight = 3;
        const barX = pos.x - barWidth / 2;
        const barY = pos.y - 20;

        // Background
        ctx.fillStyle = 'rgba(107, 107, 123, 0.3)';
        ctx.fillRect(barX, barY, barWidth, barHeight);

        // Health
        const hpPercent = Math.max(0, Math.min(1, hp / maxHp));
        const gradient = ctx.createLinearGradient(barX, barY, barX + barWidth * hpPercent, barY);
        gradient.addColorStop(0, '#C5D8A8');   // Moss green start
        gradient.addColorStop(1, '#A8C5A8');   // Darker green end
        ctx.fillStyle = gradient;
        ctx.fillRect(barX, barY, barWidth * hpPercent, barHeight);
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
