/**
 * Quality Settings (Phase 5c)
 * Manages performance quality presets and adaptive adjustment
 */
class QualitySettings {
    constructor() {
        this.qualityLevel = 'medium';
        this.effectIntensity = 100; // 0-150%
        this.particleLimit = 100;
        this.multiZoneEnabled = true;
        this.zoneRenderDistance = 1;
        this.lastAdjustmentTime = performance.now();

        this.presets = {
            low: {
                effectIntensity: 50,
                particleLimit: 50,
                multiZoneEnabled: false,
                zoneRenderDistance: 0,
            },
            medium: {
                effectIntensity: 100,
                particleLimit: 100,
                multiZoneEnabled: true,
                zoneRenderDistance: 1,
            },
            high: {
                effectIntensity: 120,
                particleLimit: 150,
                multiZoneEnabled: true,
                zoneRenderDistance: 1,
            },
            ultra: {
                effectIntensity: 150,
                particleLimit: 200,
                multiZoneEnabled: true,
                zoneRenderDistance: 1,
            },
        };

        this.fpsTarget = 60;
        this.loadFromLocalStorage();
    }

    setQualityLevel(level) {
        if (!this.presets[level]) return;
        this.qualityLevel = level;
        const preset = this.presets[level];
        this.effectIntensity = preset.effectIntensity;
        this.particleLimit = preset.particleLimit;
        this.multiZoneEnabled = preset.multiZoneEnabled;
        this.zoneRenderDistance = preset.zoneRenderDistance;
        this.saveToLocalStorage();
    }

    setEffectIntensity(intensity) {
        this.effectIntensity = Math.max(0, Math.min(150, intensity));
        this.saveToLocalStorage();
    }

    setParticleLimit(limit) {
        this.particleLimit = Math.max(10, Math.min(300, limit));
        this.saveToLocalStorage();
    }

    toggleMultiZone() {
        this.multiZoneEnabled = !this.multiZoneEnabled;
        this.saveToLocalStorage();
    }

    setZoneRenderDistance(distance) {
        this.zoneRenderDistance = Math.max(0, Math.min(2, distance));
        this.saveToLocalStorage();
    }

    autoDetectQualityLevel() {
        if (this.isMobile()) {
            this.setQualityLevel('low');
        } else if (this.isTablet()) {
            this.setQualityLevel('medium');
        } else if (this.isHighEndDesktop()) {
            this.setQualityLevel('high');
        } else {
            this.setQualityLevel('medium');
        }
    }

    isMobile() {
        return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
    }

    isTablet() {
        return /iPad|Android(?!.*Mobile)/.test(navigator.userAgent);
    }

    isHighEndDesktop() {
        // Check for dedicated GPU or high core count
        const cores = navigator.hardwareConcurrency || 4;
        return cores >= 8;
    }

    checkMotionPreferences() {
        const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (prefersReducedMotion) {
            this.effectIntensity = Math.min(this.effectIntensity, 50);
        }
    }

    getRecommendedQualityLevel() {
        if (this.isMobile()) return 'low';
        if (this.isTablet()) return 'medium';
        if (this.isHighEndDesktop()) return 'high';
        return 'medium';
    }

    adaptiveAdjustment(currentFps) {
        const now = performance.now();
        if (now - this.lastAdjustmentTime < 2000) return; // Only adjust every 2 seconds

        const lowerBound = this.fpsTarget * 0.8; // 80% of target
        const upperBound = this.fpsTarget * 1.1; // 110% of target

        const levels = ['low', 'medium', 'high', 'ultra'];
        const currentIndex = levels.indexOf(this.qualityLevel);

        if (currentFps < lowerBound && currentIndex > 0) {
            const newLevel = levels[currentIndex - 1];
            console.log(`Adaptive: Reducing quality from ${this.qualityLevel} to ${newLevel}`);
            this.setQualityLevel(newLevel);
            this.lastAdjustmentTime = now;
        } else if (currentFps > upperBound && currentIndex < levels.length - 1) {
            const newLevel = levels[currentIndex + 1];
            console.log(`Adaptive: Increasing quality from ${this.qualityLevel} to ${newLevel}`);
            this.setQualityLevel(newLevel);
            this.lastAdjustmentTime = now;
        }
    }

    getQualityLevelName() {
        return this.qualityLevel.charAt(0).toUpperCase() + this.qualityLevel.slice(1);
    }

    saveToLocalStorage() {
        try {
            const settings = {
                qualityLevel: this.qualityLevel,
                effectIntensity: this.effectIntensity,
                particleLimit: this.particleLimit,
                multiZoneEnabled: this.multiZoneEnabled,
                zoneRenderDistance: this.zoneRenderDistance,
            };
            localStorage.setItem('ambonmud_quality_settings', JSON.stringify(settings));
        } catch (e) {
            console.warn('Could not save quality settings:', e);
        }
    }

    loadFromLocalStorage() {
        try {
            const stored = localStorage.getItem('ambonmud_quality_settings');
            if (stored) {
                const settings = JSON.parse(stored);
                this.qualityLevel = settings.qualityLevel || 'medium';
                this.effectIntensity = settings.effectIntensity || 100;
                this.particleLimit = settings.particleLimit || 100;
                this.multiZoneEnabled = settings.multiZoneEnabled !== false;
                this.zoneRenderDistance = settings.zoneRenderDistance || 1;
                return true;
            }
        } catch (e) {
            console.warn('Could not load quality settings:', e);
        }
        return false;
    }
}
