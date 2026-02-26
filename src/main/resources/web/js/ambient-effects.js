/**
 * Ambient Effects System (Phase 5a)
 * Season-aware ambient particles and atmospheric effects
 */
class AmbientEffectsSystem {
    constructor() {
        this.particles = [];
        this.currentSeason = 'spring';
        this.currentWeather = 'clear';
        this.timeOfDay = 720;
        this.emissionRates = {
            spring: { day: 5, dusk: 10, night: 2 },
            summer: { day: 8, dusk: 15, night: 3 },
            autumn: { day: 10, dusk: 12, night: 2 },
            winter: { day: 3, dusk: 5, night: 1 },
        };
        this.accumulatedEmission = 0;
        this.lastUpdateTime = performance.now();
    }

    update(timeOfDay, season, weather, deltaTime) {
        const now = performance.now();
        if (deltaTime === undefined) {
            deltaTime = (now - this.lastUpdateTime) / 16; // Assume 60fps
        }
        this.lastUpdateTime = now;

        this.timeOfDay = timeOfDay || 720;
        this.currentSeason = season || 'spring';
        this.currentWeather = weather || 'clear';

        this.emitAmbientParticles(deltaTime);
        this.updateParticles(deltaTime);
    }

    emitAmbientParticles(deltaTime) {
        let rate = 0;

        // Determine time period
        let period = 'day';
        if (this.timeOfDay >= 360 && this.timeOfDay < 540) period = 'dusk'; // Dawn
        if (this.timeOfDay >= 1020 && this.timeOfDay < 1200) period = 'dusk'; // Dusk
        if (this.timeOfDay >= 1200 || this.timeOfDay < 360) period = 'night'; // Night

        // Get emission rate
        const rates = this.emissionRates[this.currentSeason] || this.emissionRates.spring;
        rate = rates[period] || 0;

        // Skip if weather would produce particles
        if (this.currentWeather !== 'clear') {
            rate = Math.max(0, rate - 2);
        }

        // Accumulate and emit
        this.accumulatedEmission += rate * (deltaTime / 16.67);

        while (this.accumulatedEmission >= 1) {
            const particle = this.createAmbientParticle(period);
            if (particle) {
                this.particles.push(particle);
            }
            this.accumulatedEmission -= 1;
        }

        // Cap particles
        const maxParticles = 50;
        while (this.particles.length > maxParticles) {
            this.particles.shift();
        }
    }

    createAmbientParticle(period) {
        const types = {
            spring: {
                pollen: () => ({
                    x: Math.random() * 800,
                    y: Math.random() * 600,
                    vx: (Math.random() - 0.5) * 0.5,
                    vy: (Math.random() - 0.5) * 0.5,
                    size: 1,
                    color: 'rgba(255, 220, 100, 0.6)',
                    life: 200,
                    type: 'pollen',
                }),
                fireflies: () => ({
                    x: Math.random() * 800,
                    y: Math.random() * 600,
                    vx: (Math.random() - 0.5) * 2,
                    vy: (Math.random() - 0.5) * 2,
                    size: 2,
                    color: 'rgba(255, 255, 100, 0.8)',
                    life: 150,
                    type: 'firefly',
                }),
            },
            summer: {
                dust: () => ({
                    x: Math.random() * 800,
                    y: Math.random() * 600,
                    vx: 0.1,
                    vy: -0.05,
                    size: 2,
                    color: 'rgba(200, 180, 100, 0.2)',
                    life: 200,
                    type: 'dust',
                }),
            },
            autumn: {
                leaves: () => ({
                    x: Math.random() * 800,
                    y: Math.random() * -50,
                    vx: (Math.random() - 0.5) * 2,
                    vy: 1,
                    size: 3,
                    color: 'rgba(220, 100, 50, 0.7)',
                    life: 250,
                    type: 'leaf',
                }),
            },
            winter: {
                snow: () => ({
                    x: Math.random() * 800,
                    y: Math.random() * -50,
                    vx: (Math.random() - 0.5) * 0.5,
                    vy: 0.5,
                    size: 1,
                    color: 'rgba(255, 255, 255, 0.8)',
                    life: 300,
                    type: 'snow',
                }),
            },
        };

        // Select particle types based on season and time
        let particleTypes = [];

        if (this.currentSeason === 'spring') {
            particleTypes = period === 'dusk' ? [types.spring.fireflies] : [types.spring.pollen];
        } else if (this.currentSeason === 'summer') {
            particleTypes = [types.summer.dust];
        } else if (this.currentSeason === 'autumn') {
            particleTypes = [types.autumn.leaves];
        } else if (this.currentSeason === 'winter') {
            particleTypes = [types.winter.snow];
        }

        if (particleTypes.length === 0) return null;

        const typeFunc = particleTypes[Math.floor(Math.random() * particleTypes.length)];
        return typeFunc ? typeFunc() : null;
    }

    updateParticles(deltaTime) {
        for (let i = this.particles.length - 1; i >= 0; i--) {
            const p = this.particles[i];
            p.x += p.vx;
            p.y += p.vy;
            p.life -= deltaTime;

            // Drift particles
            if (p.type === 'dust' || p.type === 'pollen') {
                p.vy -= 0.01; // Float upward
            }

            // Remove dead particles
            if (p.life <= 0 || p.y > 650) {
                this.particles.splice(i, 1);
            }
        }
    }

    renderParticles(ctx) {
        for (const p of this.particles) {
            ctx.fillStyle = p.color;
            ctx.globalAlpha = p.life / 200;
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
            ctx.fill();
        }
        ctx.globalAlpha = 1.0;
    }

    setSeason(season) {
        this.currentSeason = season;
    }

    getActiveParticles() {
        return this.particles.length;
    }
}
