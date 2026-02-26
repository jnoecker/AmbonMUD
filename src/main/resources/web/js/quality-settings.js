/**
 * Quality Settings (Phase 5c)
 * Manages performance presets and optimization settings
 * Allows players to balance visual quality vs performance
 */

class QualitySettings {
    constructor() {
        this.qualityLevel = 'medium'; // low, medium, high, ultra
        this.presets = this.initializePresets();
        this.currentSettings = this.presets.medium;

        // Performance tweaks
        this.effectIntensity = 100; // 0-100%
        this.particleLimit = 100;   // Max particles
        this.multiZoneEnabled = true;
        this.zoneRenderDistance = 1; // 0=current, 1=adjacent, 2=all
        this.motionEnabled = true;  // prefers-reduced-motion check
    }

    initializePresets() {
        return {
            low: {
                name: 'Low',
                description: 'Maximum performance, minimal effects',
                settings: {
                    renderDistance: 1,     // Current zone only
                    particleEmission: 0.5, // 50% particle rate
                    terrainLOD: 2,        // Minimal tiles
                    entitySize: 0.7,      // Smaller entities
                    shadowsEnabled: false,
                    weatherEffects: false,
                    ambientEffects: false,
                    multiZone: false,
                    fpsTarget: 60,
                },
                particleLimit: 50,
                effectIntensity: 50,
            },
            medium: {
                name: 'Medium',
                description: 'Balanced performance and visuals',
                settings: {
                    renderDistance: 1,
                    particleEmission: 0.8,
                    terrainLOD: 1,
                    entitySize: 1.0,
                    shadowsEnabled: true,
                    weatherEffects: true,
                    ambientEffects: true,
                    multiZone: true,
                    fpsTarget: 60,
                },
                particleLimit: 100,
                effectIntensity: 100,
            },
            high: {
                name: 'High',
                description: 'High quality with good performance',
                settings: {
                    renderDistance: 2,     // Adjacent zones
                    particleEmission: 1.0,
                    terrainLOD: 0,        // Full detail
                    entitySize: 1.2,
                    shadowsEnabled: true,
                    weatherEffects: true,
                    ambientEffects: true,
                    multiZone: true,
                    fpsTarget: 60,
                },
                particleLimit: 150,
                effectIntensity: 120,
            },
            ultra: {
                name: 'Ultra',
                description: 'Maximum quality and visual effects',
                settings: {
                    renderDistance: 2,     // All adjacent zones
                    particleEmission: 1.2,
                    terrainLOD: 0,
                    entitySize: 1.3,
                    shadowsEnabled: true,
                    weatherEffects: true,
                    ambientEffects: true,
                    multiZone: true,
                    fpsTarget: 60,
                },
                particleLimit: 200,
                effectIntensity: 150,
            },
        };
    }

    /**
     * Set quality level
     */
    setQualityLevel(level) {
        if (this.presets[level]) {
            this.qualityLevel = level;
            this.currentSettings = { ...this.presets[level].settings };
            this.particleLimit = this.presets[level].particleLimit;
            this.effectIntensity = this.presets[level].effectIntensity;
            return true;
        }
        return false;
    }

    /**
     * Get current quality level name
     */
    getQualityLevelName() {
        return this.presets[this.qualityLevel]?.name || 'Unknown';
    }

    /**
     * Auto-detect appropriate quality level based on device
     */
    autoDetectQualityLevel() {
        // Check device capabilities
        const isHighEnd = this.isHighEndDevice();
        const isMobile = this.isMobileDevice();

        if (isMobile) {
            this.setQualityLevel('low');
        } else if (isHighEnd) {
            this.setQualityLevel('ultra');
        } else {
            this.setQualityLevel('medium');
        }

        return this.qualityLevel;
    }

    /**
     * Determine if device is high-end
     */
    isHighEndDevice() {
        // Cache result to avoid leaking WebGL contexts on repeated calls
        if (this._highEndCached !== undefined) return this._highEndCached;

        // Check for high-end GPU support
        const canvas = document.createElement('canvas');
        const gl = canvas.getContext('webgl2');

        if (gl) {
            const renderer = gl.getParameter(gl.RENDERER);

            // Heuristic: dedicated GPU (not integrated)
            const isDedicatedGpu = !(renderer.includes('Intel') ||
                                    renderer.includes('Vega'));

            // Release WebGL context
            gl.getExtension('WEBGL_lose_context')?.loseContext();

            this._highEndCached = isDedicatedGpu;
            return isDedicatedGpu;
        }

        // Fallback: assume high-end
        this._highEndCached = true;
        return true;
    }

    /**
     * Determine if device is mobile
     */
    isMobileDevice() {
        const userAgent = navigator.userAgent.toLowerCase();
        const mobileKeywords = ['android', 'iphone', 'ipad', 'mobile', 'touch'];
        return mobileKeywords.some(keyword => userAgent.includes(keyword));
    }

    /**
     * Set effect intensity (0-150%)
     */
    setEffectIntensity(intensity) {
        this.effectIntensity = Math.max(0, Math.min(150, intensity));
    }

    /**
     * Set particle emission limit
     */
    setParticleLimit(limit) {
        this.particleLimit = Math.max(10, Math.min(300, limit));
    }

    /**
     * Toggle multi-zone rendering
     */
    toggleMultiZone() {
        this.multiZoneEnabled = !this.multiZoneEnabled;
        return this.multiZoneEnabled;
    }

    /**
     * Set zone render distance (0=current, 1=adjacent, 2=all)
     */
    setZoneRenderDistance(distance) {
        this.zoneRenderDistance = Math.max(0, Math.min(2, distance));
    }

    /**
     * Check system motion preferences
     */
    checkMotionPreferences() {
        const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        this.motionEnabled = !prefersReduced;
        return this.motionEnabled;
    }

    /**
     * Get all current settings
     */
    getSettings() {
        return {
            qualityLevel: this.qualityLevel,
            presetName: this.getQualityLevelName(),
            settings: this.currentSettings,
            effectIntensity: this.effectIntensity,
            particleLimit: this.particleLimit,
            multiZoneEnabled: this.multiZoneEnabled,
            zoneRenderDistance: this.zoneRenderDistance,
            motionEnabled: this.motionEnabled,
        };
    }

    /**
     * Save settings to localStorage
     */
    saveToLocalStorage() {
        try {
            const settings = {
                qualityLevel: this.qualityLevel,
                effectIntensity: this.effectIntensity,
                particleLimit: this.particleLimit,
                multiZoneEnabled: this.multiZoneEnabled,
                zoneRenderDistance: this.zoneRenderDistance,
            };
            localStorage.setItem('ambonmud_graphics_settings', JSON.stringify(settings));
            return true;
        } catch (e) {
            console.warn('Failed to save graphics settings:', e);
            return false;
        }
    }

    /**
     * Load settings from localStorage
     */
    loadFromLocalStorage() {
        try {
            const stored = localStorage.getItem('ambonmud_graphics_settings');
            if (stored) {
                const settings = JSON.parse(stored);
                if (settings.qualityLevel) this.setQualityLevel(settings.qualityLevel);
                if (settings.effectIntensity !== undefined) this.effectIntensity = settings.effectIntensity;
                if (settings.particleLimit !== undefined) this.particleLimit = settings.particleLimit;
                if (settings.multiZoneEnabled !== undefined) this.multiZoneEnabled = settings.multiZoneEnabled;
                if (settings.zoneRenderDistance !== undefined) this.zoneRenderDistance = settings.zoneRenderDistance;
                return true;
            }
        } catch (e) {
            console.warn('Failed to load graphics settings:', e);
        }
        return false;
    }

    /**
     * Get quality preset description
     */
    getPresetDescription(level = null) {
        level = level || this.qualityLevel;
        const preset = this.presets[level];
        return preset ? preset.description : 'Unknown preset';
    }

    /**
     * Get all available presets for UI
     */
    getAllPresets() {
        return Object.entries(this.presets).map(([key, preset]) => ({
            id: key,
            name: preset.name,
            description: preset.description,
        }));
    }

    /**
     * Adaptive quality adjustment based on FPS
     */
    adaptiveAdjustment(currentFps) {
        // If FPS drops below target, reduce quality
        const targetFps = this.currentSettings.fpsTarget || 60;
        const fpsThreshold = targetFps * 0.8; // 80% of target

        if (currentFps < fpsThreshold) {
            // Try next lower quality level
            const levels = ['low', 'medium', 'high', 'ultra'];
            const currentIndex = levels.indexOf(this.qualityLevel);

            if (currentIndex > 0) {
                const nextLevel = levels[currentIndex - 1];
                console.log(`Adaptive: Reducing quality from ${this.qualityLevel} to ${nextLevel} (FPS: ${currentFps.toFixed(0)})`);
                this.setQualityLevel(nextLevel);
                return true;
            }
        }

        // If FPS is high, try to increase quality
        const highThreshold = targetFps * 1.1; // 110% of target

        if (currentFps > highThreshold) {
            const levels = ['low', 'medium', 'high', 'ultra'];
            const currentIndex = levels.indexOf(this.qualityLevel);

            if (currentIndex < levels.length - 1) {
                const nextLevel = levels[currentIndex + 1];
                console.log(`Adaptive: Increasing quality from ${this.qualityLevel} to ${nextLevel} (FPS: ${currentFps.toFixed(0)})`);
                this.setQualityLevel(nextLevel);
                return true;
            }
        }

        return false;
    }

    /**
     * Get recommended quality level for device
     */
    getRecommendedQualityLevel() {
        const recommendations = {
            android: 'low',
            iphone: 'low',
            ipad: 'medium',
            desktop: 'high',
            highend: 'ultra',
        };

        if (this.isMobileDevice()) {
            return /ipad/i.test(navigator.userAgent) ? 'medium' : 'low';
        }

        return this.isHighEndDevice() ? 'ultra' : 'high';
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = QualitySettings;
}
