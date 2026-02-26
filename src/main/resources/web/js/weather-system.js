/**
 * Weather System (Phase 5a)
 * Manages weather types and particle effects
 */
class WeatherSystem {
    constructor() {
        this.currentWeather = 'clear';
        this.weatherTypes = ['clear', 'cloudy', 'rain', 'fog', 'snow', 'storm'];
        this.particles = [];
        this.weatherTransitionTime = 0;
        this.nextWeather = null;
        this.lastUpdateTime = performance.now();
        this.emissionRate = 0;
        this.accumulatedEmission = 0;
    }

    setWeather(weatherType) {
        if (!this.weatherTypes.includes(weatherType)) return;
        this.nextWeather = weatherType;
        this.weatherTransitionTime = 3000; // 3 second transition
    }

    update(newWeather, deltaTime) {
        const now = performance.now();
        if (deltaTime === undefined) {
            deltaTime = (now - this.lastUpdateTime) / 16; // Assume 60fps = 16ms
        }
        this.lastUpdateTime = now;

        // Update weather transition
        if (this.nextWeather && this.weatherTransitionTime > 0) {
            this.weatherTransitionTime -= deltaTime;
            if (this.weatherTransitionTime <= 0) {
                this.currentWeather = this.nextWeather;
                this.nextWeather = null;
            }
        }

        // Update from server if provided
        if (newWeather && newWeather !== this.currentWeather) {
            this.setWeather(newWeather);
        }

        // Update particles
        this.updateParticles(deltaTime);

        // Emit weather particles
        this.emitWeatherParticles(deltaTime);
    }

    emitWeatherParticles(deltaTime) {
        const rates = {
            clear: 0,
            cloudy: 2,
            rain: 30,
            fog: 5,
            snow: 15,
            storm: 50,
        };

        this.emissionRate = rates[this.currentWeather] || 0;
        this.accumulatedEmission += this.emissionRate * (deltaTime / 16.67); // Normalize to 60fps

        while (this.accumulatedEmission >= 1) {
            this.particles.push(this.createWeatherParticle());
            this.accumulatedEmission -= 1;
        }

        // Cap particle count
        const maxParticles = { rain: 200, snow: 150, storm: 300, fog: 100, cloudy: 50, clear: 0 };
        const max = maxParticles[this.currentWeather] || 0;
        while (this.particles.length > max) {
            this.particles.shift();
        }
    }

    createWeatherParticle() {
        const types = {
            rain: () => ({
                x: Math.random() * 800,
                y: Math.random() * -50,
                vx: -2,
                vy: 8,
                size: 1,
                color: '#8aafff',
                type: 'rain',
                life: 100,
            }),
            snow: () => ({
                x: Math.random() * 800,
                y: Math.random() * -50,
                vx: (Math.random() - 0.5) * 2,
                vy: 1,
                size: 2,
                color: '#ffffff',
                type: 'snow',
                life: 200,
            }),
            fog: () => ({
                x: Math.random() * 800,
                y: Math.random() * 600,
                vx: 0.1,
                vy: 0,
                size: 20,
                color: 'rgba(200, 200, 200, 0.1)',
                type: 'fog',
                life: 300,
            }),
            storm: () => ({
                x: Math.random() * 800,
                y: Math.random() * 100 - 50,
                vx: -3,
                vy: 10,
                size: 1,
                color: '#6a8aff',
                type: 'storm',
                life: 80,
            }),
        };

        const typeFunc = types[this.currentWeather];
        return typeFunc ? typeFunc() : {};
    }

    updateParticles(deltaTime) {
        for (let i = this.particles.length - 1; i >= 0; i--) {
            const p = this.particles[i];
            p.x += p.vx;
            p.y += p.vy;
            p.life -= deltaTime;

            // Remove dead particles
            if (p.life <= 0 || p.y > 600 || p.x < -50 || p.x > 850) {
                this.particles.splice(i, 1);
            }
        }
    }

    getWeatherVisibility() {
        const visibility = {
            clear: 1.0,
            cloudy: 0.9,
            rain: 0.8,
            fog: 0.6,
            snow: 0.85,
            storm: 0.5,
        };
        return visibility[this.currentWeather] || 1.0;
    }

    getActiveParticles() {
        return this.particles.length;
    }

    renderParticles(ctx) {
        for (const p of this.particles) {
            ctx.fillStyle = p.color;
            ctx.globalAlpha = p.life / 100; // Fade out at end of life
            if (p.size > 2) {
                ctx.fillRect(p.x, p.y, p.size, p.size);
            } else {
                ctx.beginPath();
                ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
                ctx.fill();
            }
        }
        ctx.globalAlpha = 1.0;
    }
}
