/**
 * Ambient Effects System (Phase 5a)
 * Manages ambient particles: fireflies, falling leaves, blowing wind, etc.
 * Integrates with particle system and time of day
 */

class AmbientEffectsSystem {
    constructor(particleSystem, timeOfDaySystem, weatherSystem) {
        this.particleSystem = particleSystem;
        this.timeOfDaySystem = timeOfDaySystem;
        this.weatherSystem = weatherSystem;

        this.activeEffects = []; // Currently active ambient effects
        this.effectConfigs = this.setupEffectConfigs();
    }

    setupEffectConfigs() {
        return {
            fireflies: {
                name: 'Fireflies',
                enabled: false, // Only at dusk/dawn
                particlesPerSecond: 3,
                color: 'rgba(255, 240, 0, 0.8)',
                glowColor: 'rgba(255, 200, 0, 0.6)',
                lifetime: 2000,
                minTime: 16 * 60, // 4 PM
                maxTime: 22 * 60, // 10 PM
                season: ['spring', 'summer', 'autumn'],
            },
            fallingLeaves: {
                name: 'Falling Leaves',
                enabled: false, // Only in autumn
                particlesPerSecond: 4,
                colors: ['#d4a574', '#c9825f', '#b8704d', '#e8c868'],
                lifetime: 4000,
                season: ['autumn'],
            },
            blowingSnow: {
                name: 'Blowing Snow',
                enabled: false, // Only when snowing
                particlesPerSecond: 8,
                color: 'rgba(240, 245, 255, 0.9)',
                lifetime: 3000,
                season: ['winter'],
                weatherTrigger: 'snow',
            },
            dustMotes: {
                name: 'Dust Motes',
                enabled: false, // Only on clear days
                particlesPerSecond: 2,
                color: 'rgba(232, 224, 216, 0.3)',
                lifetime: 3000,
                season: ['spring', 'summer'],
                weatherTrigger: 'clear',
            },
            pollen: {
                name: 'Pollen',
                enabled: false, // Spring effect
                particlesPerSecond: 2,
                colors: ['rgba(255, 200, 0, 0.4)', 'rgba(255, 220, 0, 0.4)'],
                lifetime: 4000,
                season: ['spring'],
            },
        };
    }

    /**
     * Update ambient effects based on time and season
     */
    update(timeOfDay, season, weather, dt = 16) {
        this.updateActiveEffects(timeOfDay, season, weather, dt);
    }

    /**
     * Update which effects should be active
     */
    updateActiveEffects(timeOfDay, season, weather, dt) {
        const minute = timeOfDay % 1440;

        for (const [effectKey, config] of Object.entries(this.effectConfigs)) {
            const shouldBeActive = this.shouldEffectBeActive(
                effectKey,
                config,
                minute,
                season,
                weather
            );

            const isActive = this.activeEffects.some(e => e.type === effectKey);

            if (shouldBeActive && !isActive) {
                // Start effect
                this.activeEffects.push({
                    type: effectKey,
                    config,
                    accumulatedEmission: 0,
                });
            } else if (!shouldBeActive && isActive) {
                // Stop effect
                this.activeEffects = this.activeEffects.filter(e => e.type !== effectKey);
            }
        }

        // Emit particles for active effects
        for (const effect of this.activeEffects) {
            this.emitAmbientParticles(effect, dt);
        }
    }

    /**
     * Determine if an effect should be active
     */
    shouldEffectBeActive(effectKey, config, minute, season, weather) {
        // Check season
        if (!config.season.includes(season)) {
            return false;
        }

        // Check weather trigger if specified
        if (config.weatherTrigger && config.weatherTrigger !== weather) {
            return false;
        }

        // Check time window if specified
        if (config.minTime !== undefined) {
            if (minute < config.minTime || minute >= config.maxTime) {
                return false;
            }
        }

        return true;
    }

    /**
     * Emit particles for an ambient effect
     */
    emitAmbientParticles(effect, dt) {
        const config = effect.config;
        const particlesToEmit = (config.particlesPerSecond * dt) / 1000;
        effect.accumulatedEmission += particlesToEmit;

        while (effect.accumulatedEmission >= 1) {
            this.emitAmbientParticle(effect.type, config);
            effect.accumulatedEmission -= 1;
        }
    }

    /**
     * Emit a single ambient particle
     */
    emitAmbientParticle(effectType, config) {
        const width = this.getCanvasWidth();
        const height = this.getCanvasHeight();

        let particle;

        switch (effectType) {
            case 'fireflies':
                particle = this.createFireflyParticle(width, height, config);
                break;

            case 'fallingLeaves':
                particle = this.createFallingLeafParticle(width, height, config);
                break;

            case 'blowingSnow':
                particle = this.createBlowingSnowParticle(width, height, config);
                break;

            case 'dustMotes':
                particle = this.createDustMoteParticle(width, height, config);
                break;

            case 'pollen':
                particle = this.createPollenParticle(width, height, config);
                break;

            default:
                return;
        }

        if (particle) {
            this.particleSystem.emit(particle);
        }
    }

    createFireflyParticle(width, height, config) {
        const x = Math.random() * width;
        const y = Math.random() * height * 0.6 + height * 0.1; // Upper 60% of screen
        const angle = Math.random() * Math.PI * 2;
        const speed = 0.5 + Math.random() * 0.3;

        return {
            x,
            y,
            type: 'firefly',
            color: config.color,
            duration: config.lifetime,
            velocity: {
                x: Math.cos(angle) * speed,
                y: Math.sin(angle) * speed,
            },
            radius: 2,
            glowColor: config.glowColor,
            // Store pulsing animation state
            pulsePhase: Math.random() * Math.PI * 2,
        };
    }

    createFallingLeafParticle(width, height, config) {
        const x = Math.random() * width;
        const color = config.colors[Math.floor(Math.random() * config.colors.length)];
        const rotation = Math.random() * Math.PI * 2;
        const wobbleAmount = 1 + Math.random();

        return {
            x,
            y: -20,
            type: 'leaf',
            color,
            duration: config.lifetime,
            velocity: {
                x: Math.cos(rotation) * wobbleAmount * 0.5,
                y: 1.5 + Math.random() * 0.5, // Falling
            },
            rotation,
            size: 3 + Math.random() * 2, // 3-5px leaves
        };
    }

    createBlowingSnowParticle(width, height, config) {
        return {
            x: Math.random() * width,
            y: -10,
            type: 'snow_ambient',
            color: config.color,
            duration: config.lifetime,
            velocity: {
                x: (Math.random() - 0.5) * 2.5, // More horizontal drift
                y: 0.8 + Math.random() * 0.5, // Slower falling
            },
            radius: 1.5 + Math.random() * 1.5, // 1.5-3px
        };
    }

    createDustMoteParticle(width, height, config) {
        return {
            x: Math.random() * width,
            y: Math.random() * height,
            type: 'dust_ambient',
            color: config.color,
            duration: config.lifetime,
            velocity: {
                x: (Math.random() - 0.5) * 0.3,
                y: Math.sin(performance.now() / 3000 + Math.random() * Math.PI * 2) * 0.2,
            },
            radius: 0.5 + Math.random() * 1, // 0.5-1.5px
        };
    }

    createPollenParticle(width, height, config) {
        const color = config.colors[Math.floor(Math.random() * config.colors.length)];
        return {
            x: Math.random() * width,
            y: -10,
            type: 'pollen',
            color,
            duration: config.lifetime,
            velocity: {
                x: (Math.random() - 0.5) * 0.5,
                y: 0.5 + Math.random() * 0.3,
            },
            radius: 1 + Math.random() * 1, // 1-2px
        };
    }

    /**
     * Get description of current ambient effects
     */
    getAmbientDescription() {
        if (this.activeEffects.length === 0) {
            return 'The air is still.';
        }

        const descriptions = this.activeEffects.map(e => {
            const names = {
                fireflies: 'Fireflies dance in the twilight',
                fallingLeaves: 'Leaves drift gently through the air',
                blowingSnow: 'Snow swirls in the wind',
                dustMotes: 'Dust motes drift in the sunlight',
                pollen: 'Pollen floats on the breeze',
            };
            return names[e.type] || e.config.name;
        });

        if (descriptions.length === 1) {
            return descriptions[0] + '.';
        } else if (descriptions.length === 2) {
            return descriptions[0] + ' and ' + descriptions[1] + '.';
        } else {
            return descriptions.slice(0, -1).join(', ') + ', and ' + descriptions[descriptions.length - 1] + '.';
        }
    }

    // ========== PRIVATE HELPERS ==========

    getCanvasWidth() {
        const canvas = document.getElementById('world-canvas');
        return canvas ? canvas.width : 800;
    }

    getCanvasHeight() {
        const canvas = document.getElementById('world-canvas');
        return canvas ? canvas.height : 600;
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = AmbientEffectsSystem;
}
