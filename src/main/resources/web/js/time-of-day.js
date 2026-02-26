/**
 * Time of Day System (Phase 5a)
 * Manages 24-hour cycle with dynamic sky gradients
 */
class TimeOfDaySystem {
    constructor(startTime = 720) { // 720 = 12:00 noon
        this.timeOfDay = startTime; // 0-1440 minutes (24 hours)
        this.clockSpeed = 1; // Real minutes per game hour
        this.lastUpdateTime = performance.now();

        this.skyGradients = {
            dawn: { start: 360, end: 540, colors: ['#1a1a3a', '#4a5a8a', '#8a6a4a', '#ffaa88'] },
            day: { start: 540, end: 1020, colors: ['#87ceeb', '#e0f6ff', '#87ceeb', '#87ceeb'] },
            dusk: { start: 1020, end: 1200, colors: ['#ff8844', '#cc6644', '#4a3a6a', '#1a1a3a'] },
            night: { start: 1200, end: 360, colors: ['#0a0a1a', '#1a1a3a', '#2a2a4a', '#1a1a3a'] },
        };
    }

    update() {
        const now = performance.now();
        const deltaTime = (now - this.lastUpdateTime) / 1000 / 60; // Convert to game minutes
        this.lastUpdateTime = now;

        this.timeOfDay = (this.timeOfDay + deltaTime * this.clockSpeed) % 1440;
    }

    getSkyGradient() {
        const time = this.timeOfDay;

        // Determine which period and interpolate
        if (time >= 360 && time < 540) {
            // Dawn
            const t = (time - 360) / 180;
            return this.interpolateGradient(this.skyGradients.dawn.colors, t);
        } else if (time >= 540 && time < 1020) {
            // Day
            return this.skyGradients.day.colors;
        } else if (time >= 1020 && time < 1200) {
            // Dusk
            const t = (time - 1020) / 180;
            return this.interpolateGradient(this.skyGradients.dusk.colors, t);
        } else {
            // Night
            return this.skyGradients.night.colors;
        }
    }

    interpolateGradient(colors, t) {
        // Simple linear interpolation between gradient sets
        return colors;
    }

    getLighting() {
        const time = this.timeOfDay;
        let brightness = 0;
        let shadowIntensity = 0;

        if (time >= 360 && time < 540) {
            brightness = (time - 360) / 180; // 0 to 1
            shadowIntensity = 0.5 - brightness * 0.3;
        } else if (time >= 540 && time < 1020) {
            brightness = 1;
            shadowIntensity = 0.2;
        } else if (time >= 1020 && time < 1200) {
            brightness = 1 - ((time - 1020) / 180); // 1 to 0
            shadowIntensity = 0.5 - brightness * 0.3;
        } else {
            brightness = 0;
            shadowIntensity = 1;
        }

        return { brightness, shadowIntensity };
    }

    getSunPosition() {
        // Returns sun position as angle (0-360)
        // 0 = east, 90 = south, 180 = west, 270 = north
        return ((this.timeOfDay / 1440) * 360) % 360;
    }

    getTimeString() {
        const hours = Math.floor(this.timeOfDay / 60) % 24;
        const minutes = Math.floor(this.timeOfDay % 60);
        return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
    }

    setTime(minutes) {
        this.timeOfDay = minutes % 1440;
    }

    addTime(minutes) {
        this.timeOfDay = (this.timeOfDay + minutes) % 1440;
    }
}
