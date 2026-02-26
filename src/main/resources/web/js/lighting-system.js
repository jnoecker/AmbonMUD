/**
 * Lighting System (Phase 5a)
 * Manages dynamic lighting, shadows, and light sources
 * Integrates with time-of-day and canvas rendering
 */

class LightingSystem {
    constructor(timeOfDaySystem) {
        this.timeOfDaySystem = timeOfDaySystem;
        this.lightSources = []; // Array of { x, y, color, intensity, radius }
        this.globalLightModulation = 1.0;
        this.shadowDirection = 0; // Degrees (0-360)
        this.ambientLightColor = '#ffffff';
    }

    /**
     * Update lighting based on current time of day
     */
    update(timeOfDay) {
        // Update shadow direction based on sun position
        const sunPos = this.timeOfDaySystem.getSunPosition(timeOfDay);
        if (sunPos >= 0) {
            this.shadowDirection = sunPos; // 0-180 degrees from left to right
        }

        // Update global light modulation (affects all non-light-source areas)
        this.globalLightModulation = this.timeOfDaySystem.calculateLighting(timeOfDay);

        // Update ambient light color
        this.ambientLightColor = this.timeOfDaySystem.getAmbientLightColor(timeOfDay);
    }

    /**
     * Add a light source (e.g., torch, lantern, spell effect)
     */
    addLightSource(x, y, config = {}) {
        const lightSource = {
            x,
            y,
            color: config.color || '#f0d080', // Default warm yellow
            intensity: config.intensity || 1.0, // 0-1
            radius: config.radius || 50, // Pixels
            duration: config.duration || -1, // -1 = permanent, otherwise milliseconds
            startTime: performance.now(),
            type: config.type || 'torch', // torch, lantern, spell, magic
        };
        this.lightSources.push(lightSource);
        return lightSource;
    }

    /**
     * Remove a light source
     */
    removeLightSource(lightSource) {
        const idx = this.lightSources.indexOf(lightSource);
        if (idx >= 0) {
            this.lightSources.splice(idx, 1);
        }
    }

    /**
     * Update light sources (remove expired ones)
     */
    updateLightSources() {
        const now = performance.now();
        this.lightSources = this.lightSources.filter(light => {
            if (light.duration <= 0) return true; // Keep permanent lights
            const age = now - light.startTime;
            if (age > light.duration) {
                return false; // Remove expired
            }
            // Update intensity fade near end of duration
            if (age > light.duration * 0.8) {
                const fadeProgress = (age - light.duration * 0.8) / (light.duration * 0.2);
                light.currentIntensity = light.intensity * (1 - fadeProgress);
            } else {
                light.currentIntensity = light.intensity;
            }
            return true;
        });
    }

    /**
     * Apply lighting to canvas context
     * Called before rendering entities and terrain
     */
    applyGlobalLighting(ctx, width, height) {
        // Create global lighting overlay
        const lighting = ctx.createLinearGradient(0, 0, 0, height);

        // Subtle vertical lighting gradient based on sun position
        const sunPos = this.shadowDirection / 180; // 0-1 (0 = left, 1 = right)

        // Create lighting that varies by time of day
        const topColor = this.adjustColorBrightness(this.ambientLightColor, this.globalLightModulation);
        const bottomColor = this.adjustColorBrightness(this.ambientLightColor, this.globalLightModulation * 0.9);

        lighting.addColorStop(0, topColor);
        lighting.addColorStop(1, bottomColor);

        // Apply with low opacity for subtle effect
        ctx.globalAlpha = 0.15;
        ctx.fillStyle = lighting;
        ctx.fillRect(0, 0, width, height);
        ctx.globalAlpha = 1;
    }

    /**
     * Apply light source glow effects to canvas
     */
    applyLightSourceGlows(ctx, camera) {
        this.updateLightSources();

        for (const light of this.lightSources) {
            const screenPos = camera.worldToScreen(light.x, light.y);
            if (!camera.isVisible(screenPos.x, screenPos.y, light.radius)) {
                continue; // Skip off-screen lights
            }

            this.renderLightGlow(ctx, screenPos.x, screenPos.y, light, camera);
        }
    }

    /**
     * Render individual light source glow
     */
    renderLightGlow(ctx, x, y, light, camera) {
        const intensity = light.currentIntensity || light.intensity;
        const scaledRadius = light.radius / camera.zoom;

        // Create radial gradient for glow
        const gradient = ctx.createRadialGradient(x, y, 0, x, y, scaledRadius);

        const glowColor = this.adjustColorBrightness(light.color, 0.9);
        gradient.addColorStop(0, this.hexToRgba(glowColor, intensity * 0.8));
        gradient.addColorStop(0.5, this.hexToRgba(glowColor, intensity * 0.3));
        gradient.addColorStop(1, this.hexToRgba(glowColor, 0));

        ctx.fillStyle = gradient;
        ctx.fillRect(x - scaledRadius, y - scaledRadius, scaledRadius * 2, scaledRadius * 2);

        // Render light source core
        ctx.fillStyle = this.hexToRgba(light.color, intensity);
        ctx.beginPath();
        ctx.arc(x, y, 4, 0, Math.PI * 2);
        ctx.fill();
    }

    /**
     * Calculate shadow offset based on sun position and object height
     * Returns { x, y } offset in pixels
     */
    calculateShadowOffset(height = 10) {
        // Shadow direction: 0 = left side, 90 = behind, 180 = right side
        const angle = this.shadowDirection * (Math.PI / 180); // Convert to radians

        // Shadow length varies with sun height (shorter at noon, longer at dawn/dusk)
        const sunHeight = Math.sin((this.shadowDirection / 180) * Math.PI);
        const shadowLength = Math.max(2, 15 * (1 - sunHeight)); // 2-15 pixels

        return {
            x: Math.cos(angle) * shadowLength,
            y: Math.sin(angle) * shadowLength * 0.5, // Y offset is less pronounced
        };
    }

    /**
     * Apply lighting color filter to an object's rendered appearance
     * Returns color to apply as overlay or color shift
     */
    getLitColor(baseColor, lightingLevel = null) {
        const level = lightingLevel || this.globalLightModulation;

        // At nighttime, shift colors toward cool blue tones
        if (level < 0.6) {
            return this.lerpColor(baseColor, '#5a7a9a', 1 - level);
        }

        // During day, shift toward ambient light color
        return this.lerpColor(baseColor, this.ambientLightColor, 1 - level * 0.5);
    }

    /**
     * Check if a position is in darkness (nighttime or shadow)
     */
    isInDarkness(x, y) {
        if (this.globalLightModulation > 0.6) {
            return false; // Daytime
        }

        // Check if near any light source
        for (const light of this.lightSources) {
            const dist = Math.sqrt((x - light.x) ** 2 + (y - light.y) ** 2);
            if (dist < light.radius) {
                return false; // In light from a source
            }
        }

        return true; // In darkness
    }

    // ========== UTILITY METHODS ==========

    adjustColorBrightness(hexColor, factor) {
        const rgb = this.hexToRgb(hexColor);
        const r = Math.floor(Math.min(255, rgb.r * factor));
        const g = Math.floor(Math.min(255, rgb.g * factor));
        const b = Math.floor(Math.min(255, rgb.b * factor));
        return this.rgbToHex(r, g, b);
    }

    hexToRgba(hexColor, alpha) {
        const rgb = this.hexToRgb(hexColor);
        return `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, ${alpha})`;
    }

    hexToRgb(hex) {
        const result = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex);
        return result ? {
            r: parseInt(result[1], 16),
            g: parseInt(result[2], 16),
            b: parseInt(result[3], 16),
        } : { r: 255, g: 255, b: 255 };
    }

    rgbToHex(r, g, b) {
        return '#' + [r, g, b].map(x => {
            const hex = x.toString(16);
            return hex.length === 1 ? '0' + hex : hex;
        }).join('');
    }

    lerpColor(colorA, colorB, t) {
        const a = this.hexToRgb(colorA);
        const b = this.hexToRgb(colorB);
        const r = Math.floor(a.r + (b.r - a.r) * t);
        const g = Math.floor(a.g + (b.g - a.g) * t);
        const bl = Math.floor(a.b + (b.b - a.b) * t);
        return this.rgbToHex(r, g, bl);
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = LightingSystem;
}
