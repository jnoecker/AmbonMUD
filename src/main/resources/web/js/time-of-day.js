/**
 * Time of Day System (Phase 5a)
 * Manages 24-hour cycle with smooth sky gradient transitions
 * Integrates with weather, lighting, and ambient effects
 */

class TimeOfDaySystem {
    constructor() {
        // Time is in minutes (0-1440, where 1440 = midnight of next day)
        this.timeOfDay = 360; // Start at 6:00 AM
        this.skyGradient = this.calculateSkyGradient(this.timeOfDay);
        this.lightingLevel = this.calculateLighting(this.timeOfDay);
        this.season = 'spring';

        // Smooth transition parameters
        this.transitionDuration = 30000; // 30 seconds (GMCP-driven)
        this.lastUpdateTime = performance.now();
        this.targetTimeOfDay = this.timeOfDay;
    }

    /**
     * Update time of day with smooth interpolation
     * Called from GMCP Room.Ambiance handler
     */
    update(newTimeOfDay, dt = 16) {
        const now = performance.now();
        const elapsed = now - this.lastUpdateTime;

        // Smooth transition to new time if GMCP sends different value
        if (newTimeOfDay !== undefined && Math.abs(newTimeOfDay - this.timeOfDay) > 1) {
            const progress = Math.min(1, elapsed / this.transitionDuration);
            this.timeOfDay = this.lerp(this.timeOfDay, newTimeOfDay, progress);

            if (progress >= 1) {
                this.timeOfDay = newTimeOfDay;
                this.lastUpdateTime = now;
            }
        }

        // Update derived values
        this.skyGradient = this.calculateSkyGradient(this.timeOfDay);
        this.lightingLevel = this.calculateLighting(this.timeOfDay);
    }

    /**
     * Calculate sky gradient stops based on time of day
     * Returns array of { offset, color } for canvas gradient
     */
    calculateSkyGradient(timeOfDay) {
        // Normalize to 0-1 (0 = midnight, 0.5 = noon, 1 = next midnight)
        const normalizedTime = (timeOfDay % 1440) / 1440;

        let skyGradient;

        // Dawn: 5:00-7:00 (300-420 minutes, 0.208-0.292)
        if (normalizedTime >= 0.208 && normalizedTime < 0.292) {
            skyGradient = this.getDawnGradient(normalizedTime, 0.208, 0.292);
        }
        // Day: 7:00-18:00 (420-1080 minutes, 0.292-0.75)
        else if (normalizedTime >= 0.292 && normalizedTime < 0.75) {
            skyGradient = this.getDayGradient(normalizedTime);
        }
        // Dusk: 18:00-20:00 (1080-1200 minutes, 0.75-0.833)
        else if (normalizedTime >= 0.75 && normalizedTime < 0.833) {
            skyGradient = this.getDuskGradient(normalizedTime, 0.75, 0.833);
        }
        // Night: 20:00-5:00 (1200-1440 and 0-300 minutes, 0.833-1.0 and 0-0.208)
        else {
            skyGradient = this.getNightGradient(normalizedTime);
        }

        return skyGradient;
    }

    getDawnGradient(t, startT, endT) {
        // Fade from night (dark blue) to day (light blue) with orange horizon
        const progress = (t - startT) / (endT - startT); // 0-1

        return [
            { offset: 0, color: this.lerpColor('#1a1a3e', '#87ceeb', progress) }, // Sky top
            { offset: 0.4, color: this.lerpColor('#4a4a8a', '#e8a87c', progress) }, // Mid sky
            { offset: 0.7, color: this.lerpColor('#3a3a6a', '#f0a868', progress) }, // Horizon
            { offset: 1, color: this.lerpColor('#2a2a4a', '#e8d8a8', progress) }, // Ground
        ];
    }

    getDayGradient(t) {
        // Bright clear sky with sun effect
        return [
            { offset: 0, color: '#87ceeb' }, // Sky top (light blue)
            { offset: 0.3, color: '#b0d8e8' }, // Upper sky
            { offset: 0.6, color: '#d8e0f0' }, // Lower sky
            { offset: 1, color: '#e8e8f0' }, // Horizon/ground
        ];
    }

    getDuskGradient(t, startT, endT) {
        // Fade from day to night with purple and orange
        const progress = (t - startT) / (endT - startT); // 0-1

        return [
            { offset: 0, color: this.lerpColor('#87ceeb', '#6a4c93', progress) }, // Sky top
            { offset: 0.3, color: this.lerpColor('#e8a87c', '#d47c4c', progress) }, // Upper horizon
            { offset: 0.6, color: this.lerpColor('#f0a868', '#c85a54', progress) }, // Horizon
            { offset: 1, color: this.lerpColor('#e8d8a8', '#4a3a5a', progress) }, // Ground
        ];
    }

    getNightGradient(t) {
        // Dark sky with stars
        return [
            { offset: 0, color: '#0a0a1a' }, // Sky top (deep blue-black)
            { offset: 0.4, color: '#1a2a4a' }, // Upper sky
            { offset: 0.7, color: '#2a3a5a' }, // Lower sky
            { offset: 1, color: '#1a1a3a' }, // Horizon
        ];
    }

    calculateLighting(timeOfDay) {
        // Returns 0-1 lighting level for scene
        // 0.3 at night, 1.0 at noon, 0.7 at dawn/dusk
        const normalizedTime = (timeOfDay % 1440) / 1440;

        // Sine wave for smooth day/night cycle
        // Peak (1.0) at noon (0.5), minimum (0.3) at midnight (0 or 1)
        const lightingCurve = Math.sin((normalizedTime - 0.25) * Math.PI);
        return 0.3 + (lightingCurve * 0.7); // Range: 0.3-1.0
    }

    /**
     * Get sun position in sky (0-180 degrees, 0 = dawn, 90 = noon, 180 = dusk)
     */
    getSunPosition(timeOfDay) {
        const normalizedTime = (timeOfDay % 1440) / 1440;

        // Sun visible from 5:00 (0.208) to 20:00 (0.833)
        if (normalizedTime >= 0.208 && normalizedTime < 0.833) {
            const sunProgress = (normalizedTime - 0.208) / (0.833 - 0.208);
            return sunProgress * 180; // 0-180 degrees
        }
        return -1; // Sun below horizon
    }

    /**
     * Get moon phase (0-8, where 0 = new, 4 = full)
     * In real game, would be tied to in-game calendar
     */
    getMoonPhase(dayOfMonth = 15) {
        // Simple cycle every 28 days
        return Math.floor((dayOfMonth % 28) / 3.5); // 0-7 phases
    }

    /**
     * Get ambient light color based on time
     */
    getAmbientLightColor(timeOfDay) {
        const normalizedTime = (timeOfDay % 1440) / 1440;

        if (normalizedTime >= 0.208 && normalizedTime < 0.292) {
            // Dawn: warm orange
            return '#e8a87c';
        } else if (normalizedTime >= 0.292 && normalizedTime < 0.75) {
            // Day: white-ish
            return '#ffffff';
        } else if (normalizedTime >= 0.75 && normalizedTime < 0.833) {
            // Dusk: purple-orange
            return '#c87c68';
        } else {
            // Night: cool blue
            return '#5a7a9a';
        }
    }

    /**
     * Check if time is daytime (can see clearly without light sources)
     */
    isDaytime(timeOfDay) {
        const normalizedTime = (timeOfDay % 1440) / 1440;
        return normalizedTime >= 0.208 && normalizedTime < 0.833;
    }

    /**
     * Check if time is nighttime
     */
    isNighttime(timeOfDay) {
        return !this.isDaytime(timeOfDay);
    }

    /**
     * Get time of day as display string (e.g., "6:30 AM")
     */
    getTimeString(timeOfDay) {
        const minutes = timeOfDay % 1440;
        const hours = Math.floor(minutes / 60);
        const mins = Math.floor(minutes % 60);
        const isAM = hours < 12;
        const displayHours = hours === 0 ? 12 : (hours > 12 ? hours - 12 : hours);
        return `${displayHours}:${String(mins).padStart(2, '0')} ${isAM ? 'AM' : 'PM'}`;
    }

    // ========== UTILITY METHODS ==========

    lerp(a, b, t) {
        return a + (b - a) * t;
    }

    lerpColor(colorA, colorB, t) {
        const a = this.hexToRgb(colorA);
        const b = this.hexToRgb(colorB);
        const r = Math.floor(this.lerp(a.r, b.r, t));
        const g = Math.floor(this.lerp(a.g, b.g, t));
        const bl = Math.floor(this.lerp(a.b, b.b, t));
        return this.rgbToHex(r, g, bl);
    }

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
    module.exports = TimeOfDaySystem;
}
