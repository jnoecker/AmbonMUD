/**
 * AmbonMUD GMCP ↔ Canvas Integration
 * Phase 4b: Wire GMCP data to canvas rendering
 * Phase 5a: Ambient effects integration
 *
 * Handles GMCP messages and updates the canvas world state
 */

class GMCPCanvasIntegration {
    constructor(renderer, camera, interaction) {
        this.renderer = renderer;
        this.camera = camera;
        this.interaction = interaction;
        this.effectsLayer = null;

        // Phase 5a ambient systems
        this.timeOfDaySystem = null;
        this.lightingSystem = null;
        this.weatherSystem = null;
        this.ambientEffectsSystem = null;

        this.currentSeason = 'spring';
    }

    /**
     * Initialize ambient effect systems (called after renderer is ready)
     */
    initializeAmbientSystems() {
        if (!this.timeOfDaySystem && typeof TimeOfDaySystem !== 'undefined') {
            this.timeOfDaySystem = new TimeOfDaySystem();
            this.lightingSystem = new LightingSystem(this.timeOfDaySystem);
            this.weatherSystem = new WeatherSystem(this.renderer.particleSystem, this.timeOfDaySystem);
            this.ambientEffectsSystem = new AmbientEffectsSystem(
                this.renderer.particleSystem,
                this.timeOfDaySystem,
                this.weatherSystem
            );
        }
    }

    /**
     * Handle GMCP.Char.Vitals - Character HP/Mana/XP updates
     */
    handleCharVitals(data) {
        // Update player position if included
        if (data.playerPos) {
            this.renderer.updateGameState({
                playerPos: data.playerPos,
            });
        }
    }

    /**
     * Handle GMCP.Room.Info - Room description and basic data
     */
    handleRoomInfo(data) {
        const room = {
            id: data.id,
            title: data.title,
            description: data.description,
            exits: data.exits || {},
        };

        this.renderer.updateGameState({
            currentRoom: room,
            exits: Object.entries(data.exits || {}).map(([dir, roomId]) => ({
                direction: dir,
                roomId,
            })),
        });

        this.camera.setTarget(200, 150); // Center on player
    }

    /**
     * Handle GMCP.Room.Map - Room layout and terrain
     * (To be sent by backend when available)
     */
    handleRoomMap(data) {
        const room = this.renderer.gameState.currentRoom || {};
        const updatedRoom = {
            ...room,
            width: data.width,
            height: data.height,
            terrain: data.terrain || [],
            obstacles: data.obstacles || [],
        };

        this.renderer.updateGameState({
            currentRoom: updatedRoom,
        });
    }

    /**
     * Handle GMCP.Room.Entities - Mob/player positions
     * (To be sent by backend when available)
     */
    handleRoomEntities(data) {
        const mobs = (data.mobs || []).map(mob => ({
            ...mob,
            pos: { x: (mob.gridX || 0) * 20 + 10, y: (mob.gridY || 0) * 20 + 10 },
            threat: mob.threat || 0,
        }));

        const playersHere = (data.players || []).map(p => ({
            ...p,
            pos: { x: (p.gridX || 0) * 20 + 10, y: (p.gridY || 0) * 20 + 10 },
        }));

        this.renderer.updateGameState({
            mobs,
            playersHere,
        });
    }

    /**
     * Handle GMCP.Combat.Damage - Mob takes damage
     */
    handleCombatDamage(data) {
        if (!this.effectsLayer) {
            this.effectsLayer = this.renderer.layers.effects;
        }

        const pos = {
            x: (data.gridX || 0) * 20 + 10,
            y: (data.gridY || 0) * 20 + 10,
        };

        // Show damage number
        this.effectsLayer.drawDamageNumber(pos, data.damage, '#E8C5A8');

        // Optional: trigger hit effect
        if (data.critical) {
            this.effectsLayer.triggerGlowEffect(pos, '#E8D8A8', 400);
        }
    }

    /**
     * Handle GMCP.Abilities.Cast - Ability cast effect
     */
    handleAbilityCast(data) {
        if (!this.effectsLayer) {
            this.effectsLayer = this.renderer.layers.effects;
        }

        const pos = {
            x: (data.targetGridX || 0) * 20 + 10,
            y: (data.targetGridY || 0) * 20 + 10,
        };

        const color = data.color || '#E8D8A8';
        this.effectsLayer.triggerSpellEffect(pos, color);

        // If AoE, show area
        if (data.radius) {
            this.renderer.updateGameState({
                activeAoE: [
                    ...this.renderer.gameState.activeAoE,
                    {
                        x: pos.x,
                        y: pos.y,
                        radius: data.radius * 20,
                        duration: data.duration || 1000,
                    },
                ],
            });

            // Remove AoE after duration
            setTimeout(() => {
                const aoe = this.renderer.gameState.activeAoE;
                const updated = aoe.filter(a => !(a.x === pos.x && a.y === pos.y));
                this.renderer.updateGameState({ activeAoE: updated });
            }, data.duration || 1000);
        }
    }

    /**
     * Handle GMCP.Combat.GroundEffect - AoE effect or projectile
     */
    handleGroundEffect(data) {
        if (!this.effectsLayer) {
            this.effectsLayer = this.renderer.layers.effects;
        }

        const pos = {
            x: (data.gridX || 0) * 20 + 10,
            y: (data.gridY || 0) * 20 + 10,
        };

        switch (data.type) {
            case 'aoe':
                // Show pulsing circle
                this.renderer.updateGameState({
                    activeAoE: [
                        ...this.renderer.gameState.activeAoE,
                        {
                            x: pos.x,
                            y: pos.y,
                            radius: data.radius * 20,
                        },
                    ],
                });
                break;

            case 'projectile':
                // Could add projectile animation here
                this.effectsLayer.triggerGlowEffect(pos, data.color || '#E8D8A8');
                break;

            case 'particle':
                // Generic particle burst
                this.effectsLayer.triggerSpellEffect(pos, data.color || '#D8C5E8');
                break;
        }
    }

    /**
     * Handle GMCP.Room.Ambiance - Lighting, weather effects (Phase 5a)
     * Data structure from server:
     * {
     *   weather: 'clear' | 'cloudy' | 'rain' | 'fog' | 'snow' | 'storm',
     *   timeOfDay: 0-1440 (minutes since midnight),
     *   lighting: 'bright' | 'normal' | 'dim' | 'dark',
     *   lightSources: [{ x, y, color, intensity, radius }, ...],
     *   season: 'spring' | 'summer' | 'autumn' | 'winter'
     * }
     */
    handleRoomAmbiance(data) {
        this.initializeAmbientSystems();

        if (!this.timeOfDaySystem) return; // Systems not loaded yet

        // Update time of day
        if (data.timeOfDay !== undefined) {
            this.timeOfDaySystem.update(data.timeOfDay);
            this.lightingSystem.update(data.timeOfDay);
        }

        // Update weather
        if (data.weather) {
            this.weatherSystem.update(data.weather);
        }

        // Update season
        if (data.season) {
            this.currentSeason = data.season;
        }

        // Update light sources
        if (data.lightSources && Array.isArray(data.lightSources)) {
            // Clear previous light sources
            this.lightingSystem.lightSources = [];

            // Add new light sources
            for (const lightData of data.lightSources) {
                this.lightingSystem.addLightSource(lightData.x, lightData.y, {
                    color: lightData.color || '#f0d080',
                    intensity: lightData.intensity || 1.0,
                    radius: lightData.radius || 50,
                });
            }
        }

        // Update ambient effects
        this.ambientEffectsSystem.update(
            data.timeOfDay || this.timeOfDaySystem.timeOfDay,
            this.currentSeason,
            data.weather || this.weatherSystem.currentWeather
        );

        // Update game state with sky gradient and lighting
        this.renderer.updateGameState({
            skyGradient: this.timeOfDaySystem.skyGradient,
            lightingLevel: this.timeOfDaySystem.lightingLevel,
            weather: data.weather || this.weatherSystem.currentWeather,
            timeOfDay: data.timeOfDay || this.timeOfDaySystem.timeOfDay,
            ambiance: {
                weather: data.weather,
                timeOfDay: data.timeOfDay,
                lighting: data.lighting,
                season: data.season,
            },
        });
    }

    /**
     * Schedule render after state updates
     */
    scheduleRender() {
        this.renderer.scheduleRender();
    }
}

// ========== EXPORT ==========

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { GMCPCanvasIntegration };
}
