/**
 * Weather System (Phase 5a)
 * Manages weather states and weather-driven particle effects
 * Integrates with particle system and time of day
 */

class WeatherSystem {
    constructor(particleSystem, timeOfDaySystem) {
        this.particleSystem = particleSystem;
        this.timeOfDaySystem = timeOfDaySystem;

        this.currentWeather = 'clear';
        this.targetWeather = 'clear';
        this.weatherTransition = 0; // 0-1 progress
        this.transitionDuration = 3000; // 3 seconds for weather change

        this.weatherParticleEmitters = {}; // { weatherType: emitterConfig }
        this.setupWeatherConfigs();

        this.emissionRate = 1.0; // Particles per frame
        this.accumulatedEmission = 0;
    }

    setupWeatherConfigs() {
        this.weatherParticleEmitters = {
            clear: {
                type: 'dust', // Dust motes
                color: 'rgba(232, 224, 216, 0.4)',
                particlesPerSecond: 2,
                speed: 0.5,
                enabled: true,
            },
            cloudy: {
                type: 'cloud',
                color: 'rgba(200, 200, 200, 0.3)',
                particlesPerSecond: 0,
                speed: 0.2,
                enabled: false, // No particles, just visual overlay
            },
            rain: {
                type: 'rain',
                color: 'rgba(180, 200, 220, 0.6)',
                particlesPerSecond: 30,
                speed: 3.0,
                lifetime: 800,
                enabled: true,
            },
            fog: {
                type: 'mist',
                color: 'rgba(200, 210, 220, 0.2)',
                particlesPerSecond: 5,
                speed: 0.3,
                lifetime: 3000,
                enabled: true,
            },
            snow: {
                type: 'snow',
                color: 'rgba(240, 240, 255, 0.7)',
                particlesPerSecond: 15,
                speed: 0.5,
                lifetime: 5000,
                enabled: true,
            },
            storm: {
                type: 'rain',
                color: 'rgba(150, 170, 200, 0.8)',
                particlesPerSecond: 60,
                speed: 4.0,
                lifetime: 600,
                enabled: true,
            },
        };
    }

    /**
     * Update weather (called each frame or from GMCP)
     */
    update(newWeather, dt = 16) {
        if (newWeather && newWeather !== this.currentWeather) {
            this.targetWeather = newWeather;
            this.weatherTransition = 0;
        }

        // Smooth weather transition
        if (this.weatherTransition < 1) {
            this.weatherTransition += (dt / this.transitionDuration);
            if (this.weatherTransition >= 1) {
                this.currentWeather = this.targetWeather;
                this.weatherTransition = 1;
            }
        }

        // Emit weather particles
        this.emitWeatherParticles(dt);
    }

    /**
     * Emit particles based on current weather
     */
    emitWeatherParticles(dt) {
        const config = this.weatherParticleEmitters[this.currentWeather];
        if (!config || !config.enabled) return;

        const particlesToEmit = (config.particlesPerSecond * dt) / 1000;
        this.accumulatedEmission += particlesToEmit;

        while (this.accumulatedEmission >= 1) {
            this.emitWeatherParticle(config);
            this.accumulatedEmission -= 1;
        }
    }

    /**
     * Emit a single weather particle
     */
    emitWeatherParticle(config) {
        const width = this.getCanvasWidth();
        const height = this.getCanvasHeight();

        let particle;

        switch (config.type) {
            case 'rain':
                particle = {
                    x: Math.random() * width,
                    y: -10,
                    type: 'rain',
                    color: config.color,
                    duration: config.lifetime || 800,
                    velocity: {
                        x: (Math.random() - 0.5) * 2, // Slight sideways drift
                        y: config.speed * 3, // Falling rain
                    },
                };
                break;

            case 'snow':
                particle = {
                    x: Math.random() * width,
                    y: -10,
                    type: 'snow',
                    color: config.color,
                    duration: config.lifetime || 5000,
                    velocity: {
                        x: (Math.random() - 0.5) * config.speed,
                        y: config.speed * 0.5,
                    },
                    radius: Math.random() * 2 + 1, // 1-3px snowflakes
                };
                break;

            case 'dust':
                particle = {
                    x: Math.random() * width,
                    y: Math.random() * (height * 0.6) + height * 0.2,
                    type: 'dust',
                    color: config.color,
                    duration: 3000,
                    velocity: {
                        x: (Math.random() - 0.5) * config.speed,
                        y: Math.sin(performance.now() / 2000) * config.speed * 0.5,
                    },
                    radius: Math.random() * 1.5 + 0.5, // 0.5-2px dust motes
                };
                break;

            case 'mist':
                particle = {
                    x: Math.random() * width,
                    y: Math.random() * height,
                    type: 'mist',
                    color: config.color,
                    duration: config.lifetime || 3000,
                    velocity: {
                        x: (Math.random() - 0.5) * config.speed,
                        y: (Math.random() - 0.5) * config.speed,
                    },
                    radius: Math.random() * 5 + 3, // 3-8px fog particles
                };
                break;

            default:
                return;
        }

        this.particleSystem.emit(particle);
    }

    /**
     * Get weather description string
     */
    getWeatherString(weather = null) {
        weather = weather || this.currentWeather;
        const descriptions = {
            clear: 'Clear skies',
            cloudy: 'Cloudy',
            rain: 'Rainy',
            fog: 'Foggy',
            snow: 'Snowing',
            storm: 'Thunderstorm',
        };
        return descriptions[weather] || 'Unknown';
    }

    /**
     * Get weather-specific color overlay for sky
     */
    getWeatherColorOverlay(weather = null) {
        weather = weather || this.currentWeather;
        const overlays = {
            clear: { color: 'rgba(255, 255, 255, 0)', opacity: 0 },
            cloudy: { color: 'rgba(200, 200, 200, 0.15)', opacity: 0.15 },
            rain: { color: 'rgba(100, 120, 140, 0.25)', opacity: 0.25 },
            fog: { color: 'rgba(200, 210, 220, 0.4)', opacity: 0.4 },
            snow: { color: 'rgba(240, 240, 255, 0.2)', opacity: 0.2 },
            storm: { color: 'rgba(80, 100, 120, 0.35)', opacity: 0.35 },
        };
        return overlays[weather] || overlays.clear;
    }

    /**
     * Check if weather reduces visibility
     */
    affectsVisibility(weather = null) {
        weather = weather || this.currentWeather;
        return ['fog', 'rain', 'storm', 'snow'].includes(weather);
    }

    /**
     * Get visibility distance (how far can see, as percentage of normal)
     */
    getVisibilityDistance(weather = null) {
        weather = weather || this.currentWeather;
        const visibility = {
            clear: 1.0,   // 100% visibility
            cloudy: 0.95, // Minimal effect
            rain: 0.85,   // Slightly reduced
            fog: 0.6,     // 60% visibility
            snow: 0.7,    // 70% visibility
            storm: 0.5,   // 50% visibility
        };
        return visibility[weather] || 1.0;
    }

    /**
     * Should time-of-day lighting be affected by weather?
     */
    getWeatherLightingModulation(weather = null, timeOfDay = null) {
        weather = weather || this.currentWeather;
        timeOfDay = timeOfDay || this.timeOfDaySystem.timeOfDay;

        const baseModulation = this.timeOfDaySystem.calculateLighting(timeOfDay);

        const weatherModifiers = {
            clear: 1.0,
            cloudy: 0.9,
            rain: 0.75,
            fog: 0.8,
            snow: 0.85,
            storm: 0.6,
        };

        return baseModulation * (weatherModifiers[weather] || 1.0);
    }

    /**
     * Get sound effect name for weather (for potential audio system)
     */
    getWeatherSound(weather = null) {
        weather = weather || this.currentWeather;
        const sounds = {
            clear: null,
            cloudy: null,
            rain: 'sound_rain.wav',
            fog: null,
            snow: 'sound_wind.wav',
            storm: 'sound_thunder.wav',
        };
        return sounds[weather] || null;
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
    module.exports = WeatherSystem;
}
