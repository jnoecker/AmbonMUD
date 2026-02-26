/**
 * Lighting System (Phase 5a)
 * Manages dynamic shadows and light sources
 */
class LightingSystem {
    constructor() {
        this.sunPosition = 0; // 0-360 degrees
        this.shadowDirection = 0;
        this.shadowIntensity = 0.5;
        this.lightSources = [];
        this.globalLighting = 1.0;
    }

    setSunPosition(angle) {
        this.sunPosition = angle;
        this.shadowDirection = (angle + 180) % 360;
    }

    setShadowIntensity(intensity) {
        this.shadowIntensity = Math.max(0, Math.min(1, intensity));
    }

    setGlobalLighting(brightness) {
        this.globalLighting = Math.max(0.2, Math.min(1, brightness));
    }

    addLightSource(id, x, y, radius, intensity) {
        this.lightSources.push({ id, x, y, radius, intensity });
    }

    removeLightSource(id) {
        this.lightSources = this.lightSources.filter((ls) => ls.id !== id);
    }

    calculateShadowOffset(x, y, distance) {
        const angle = (this.shadowDirection * Math.PI) / 180;
        const offset = (distance / 100) * this.shadowIntensity;
        return {
            x: x + Math.cos(angle) * offset,
            y: y + Math.sin(angle) * offset,
        };
    }

    applyGlobalLighting(ctx) {
        // Apply shadow overlay
        ctx.fillStyle = `rgba(0, 0, 0, ${(1 - this.globalLighting) * 0.3})`;
        ctx.fillRect(0, 0, ctx.canvas.width, ctx.canvas.height);
    }

    applyLightSourceGlows(ctx) {
        for (const light of this.lightSources) {
            const gradient = ctx.createRadialGradient(light.x, light.y, 0, light.x, light.y, light.radius);
            const alpha = light.intensity;
            gradient.addColorStop(0, `rgba(255, 200, 100, ${alpha})`);
            gradient.addColorStop(0.5, `rgba(255, 150, 0, ${alpha * 0.5})`);
            gradient.addColorStop(1, `rgba(255, 100, 0, 0)`);
            ctx.fillStyle = gradient;
            ctx.fillRect(
                light.x - light.radius,
                light.y - light.radius,
                light.radius * 2,
                light.radius * 2
            );
        }
    }

    renderLights(ctx) {
        this.applyGlobalLighting(ctx);
        this.applyLightSourceGlows(ctx);
    }
}
