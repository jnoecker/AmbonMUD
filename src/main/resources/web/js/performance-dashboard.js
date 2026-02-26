/**
 * Performance Dashboard (Phase 5c)
 * Renders real-time performance metrics and optimization controls
 * Provides UI for quality settings and performance monitoring
 */

class PerformanceDashboard {
    constructor(profiler, qualitySettings) {
        this.profiler = profiler;
        this.qualitySettings = qualitySettings;

        // UI state
        this.isVisible = false;
        this.isExpanded = false;
        this.position = { x: 10, y: 10 };

        // Rendering
        this.metrics = {};
        this.lastUpdateTime = performance.now();
        this.updateInterval = 500; // Update metrics every 500ms
    }

    /**
     * Toggle dashboard visibility
     */
    toggle() {
        this.isVisible = !this.isVisible;
        return this.isVisible;
    }

    /**
     * Toggle expanded/collapsed state
     */
    toggleExpanded() {
        this.isExpanded = !this.isExpanded;
        return this.isExpanded;
    }

    /**
     * Update metrics (call periodically)
     */
    updateMetrics() {
        const now = performance.now();
        if (now - this.lastUpdateTime > this.updateInterval) {
            this.metrics = this.profiler.getMetrics();
            this.lastUpdateTime = now;
        }
    }

    /**
     * Render dashboard on canvas
     */
    render(ctx, canvasWidth, canvasHeight) {
        if (!this.isVisible) return;

        this.updateMetrics();

        if (this.isExpanded) {
            this.renderExpanded(ctx, canvasWidth, canvasHeight);
        } else {
            this.renderCompact(ctx, canvasWidth, canvasHeight);
        }
    }

    /**
     * Render compact dashboard (small overlay)
     */
    renderCompact(ctx, canvasWidth, canvasHeight) {
        const width = 250;
        const height = 120;
        const x = this.position.x;
        const y = this.position.y;

        // Background
        ctx.fillStyle = 'rgba(20, 20, 40, 0.85)';
        ctx.fillRect(x, y, width, height);

        // Border
        ctx.strokeStyle = 'rgba(216, 197, 232, 0.3)';
        ctx.lineWidth = 1;
        ctx.strokeRect(x, y, width, height);

        // Title bar
        ctx.fillStyle = 'rgba(216, 197, 232, 0.2)';
        ctx.fillRect(x, y, width, 20);
        ctx.fillStyle = '#FFFFFF';
        ctx.font = '12px bold sans-serif';
        ctx.fillText('Performance', x + 8, y + 14);

        // Close/Expand button
        ctx.fillStyle = '#E8D8A8';
        ctx.font = '10px sans-serif';
        ctx.fillText('[+]', x + width - 25, y + 14);

        // Metrics
        const metricsFormatted = this.profiler.getMetricsFormatted();
        const startY = y + 30;
        const lineHeight = 14;

        ctx.fillStyle = '#B8D8E8';
        ctx.font = '11px monospace';

        const lines = [
            `FPS: ${metricsFormatted.fps} (${metricsFormatted.fpsAvg} avg)`,
            `Frame: ${metricsFormatted.frameTime}`,
            `Memory: ${metricsFormatted.memory}`,
            `Particles: ${metricsFormatted.particles}`,
            `Quality: ${this.qualitySettings.getQualityLevelName()}`,
            `Multi-Zone: ${this.qualitySettings.multiZoneEnabled ? 'ON' : 'OFF'}`,
        ];

        for (let i = 0; i < lines.length; i++) {
            ctx.fillText(lines[i], x + 8, startY + i * lineHeight);
        }

        // Warning indicator
        if (this.metrics.warnings && this.metrics.warnings.length > 0) {
            ctx.fillStyle = '#E8A8A8';
            ctx.fillText(`⚠ ${this.metrics.warnings.length} warning`, x + 8, y + height - 5);
        }
    }

    /**
     * Render expanded dashboard (full metrics)
     */
    renderExpanded(ctx, canvasWidth, canvasHeight) {
        const width = 400;
        const height = 500;
        const x = this.position.x;
        const y = this.position.y;

        // Background
        ctx.fillStyle = 'rgba(20, 20, 40, 0.9)';
        ctx.fillRect(x, y, width, height);

        // Border
        ctx.strokeStyle = 'rgba(216, 197, 232, 0.5)';
        ctx.lineWidth = 2;
        ctx.strokeRect(x, y, width, height);

        // Title
        ctx.fillStyle = 'rgba(216, 197, 232, 0.3)';
        ctx.fillRect(x, y, width, 24);
        ctx.fillStyle = '#FFFFFF';
        ctx.font = '14px bold sans-serif';
        ctx.fillText('Performance Dashboard', x + 10, y + 17);

        let currentY = y + 40;
        const lineHeight = 16;
        const columnX = x + 10;
        const column2X = x + 200;

        ctx.fillStyle = '#B8D8E8';
        ctx.font = '11px monospace';

        // Left column
        const metricsFormatted = this.profiler.getMetricsFormatted();

        const leftMetrics = [
            `FPS: ${metricsFormatted.fps}`,
            `  Range: ${metricsFormatted.fpsRange}`,
            `  Avg: ${metricsFormatted.fpsAvg}`,
            `Frame Time: ${metricsFormatted.frameTime}`,
            `  Avg: ${metricsFormatted.frameTimeAvg}`,
            `Memory: ${metricsFormatted.memory}`,
            `Particles: ${metricsFormatted.particles}`,
            `Entities: ${metricsFormatted.entities}`,
            `Tiles: ${metricsFormatted.tiles}`,
            `Latency: ${metricsFormatted.latency}`,
        ];

        for (let i = 0; i < leftMetrics.length; i++) {
            ctx.fillText(leftMetrics[i], columnX, currentY + i * lineHeight);
        }

        // Quality preset selector
        currentY = y + 40 + (leftMetrics.length + 2) * lineHeight;

        ctx.fillStyle = '#D8C5E8';
        ctx.font = '11px bold sans-serif';
        ctx.fillText('Quality Preset:', columnX, currentY);
        currentY += lineHeight + 4;

        const presets = ['low', 'medium', 'high', 'ultra'];
        ctx.font = '10px sans-serif';

        for (const preset of presets) {
            const isSelected = preset === this.qualitySettings.qualityLevel;
            ctx.fillStyle = isSelected ? '#E8D8A8' : '#B8D8E8';
            ctx.fillText(
                `${isSelected ? '●' : '○'} ${preset}`,
                columnX,
                currentY
            );
            currentY += 12;
        }

        // Right column - Controls
        currentY = y + 40;

        ctx.fillStyle = '#D8C5E8';
        ctx.font = '11px bold sans-serif';
        ctx.fillText('Effects & Performance:', column2X, currentY);
        currentY += lineHeight + 4;

        ctx.fillStyle = '#B8D8E8';
        ctx.font = '10px sans-serif';

        // Effect intensity slider
        ctx.fillText('Effect Intensity:', column2X, currentY);
        currentY += 12;
        this.renderSlider(ctx, column2X, currentY, 150, 8, this.qualitySettings.effectIntensity, 150);
        ctx.fillText(`${this.qualitySettings.effectIntensity.toFixed(0)}%`, column2X + 155, currentY + 8);
        currentY += 20;

        // Particle limit slider
        ctx.fillText('Particle Limit:', column2X, currentY);
        currentY += 12;
        this.renderSlider(ctx, column2X, currentY, 150, 8, this.qualitySettings.particleLimit, 300);
        ctx.fillText(`${this.qualitySettings.particleLimit}`, column2X + 155, currentY + 8);
        currentY += 20;

        // Multi-zone toggle
        ctx.fillText('Multi-Zone:', column2X, currentY);
        ctx.fillText(this.qualitySettings.multiZoneEnabled ? '[ON]' : '[OFF]', column2X + 80, currentY);
        currentY += lineHeight + 2;

        // Warnings section
        if (this.metrics.warnings && this.metrics.warnings.length > 0) {
            currentY += lineHeight;
            ctx.fillStyle = '#E8A8A8';
            ctx.font = '11px bold sans-serif';
            ctx.fillText('⚠ Warnings:', columnX, currentY);
            currentY += lineHeight;

            ctx.fillStyle = '#D8B8B8';
            ctx.font = '9px sans-serif';

            for (const warning of this.metrics.warnings.slice(0, 3)) {
                ctx.fillText(`• ${warning.message}`, columnX, currentY);
                currentY += lineHeight - 2;
            }
        }

        // Recommendations
        const recommendations = this.profiler.getOptimizationRecommendations();
        if (recommendations.length > 0) {
            currentY += lineHeight;
            ctx.fillStyle = '#D8C8A8';
            ctx.font = '11px bold sans-serif';
            ctx.fillText('💡 Recommendations:', columnX, currentY);
            currentY += lineHeight;

            ctx.fillStyle = '#D8D8A8';
            ctx.font = '9px sans-serif';

            for (const rec of recommendations.slice(0, 2)) {
                ctx.fillText(`• ${rec.message}`, columnX, currentY);
                currentY += lineHeight - 2;
            }
        }
    }

    /**
     * Render a horizontal slider
     */
    renderSlider(ctx, x, y, width, height, value, max) {
        // Background
        ctx.fillStyle = 'rgba(100, 100, 120, 0.5)';
        ctx.fillRect(x, y, width, height);

        // Fill
        const fillWidth = (value / max) * width;
        ctx.fillStyle = '#B8D8E8';
        ctx.fillRect(x, y, fillWidth, height);

        // Border
        ctx.strokeStyle = 'rgba(216, 197, 232, 0.3)';
        ctx.lineWidth = 1;
        ctx.strokeRect(x, y, width, height);
    }

    /**
     * Handle mouse click on dashboard
     */
    handleClick(mouseX, mouseY) {
        const buttonX = this.position.x + 370;
        const buttonY = this.position.y + 6;
        const buttonSize = 12;

        // Check if clicking expand/collapse button
        if (Math.abs(mouseX - buttonX) < buttonSize && Math.abs(mouseY - buttonY) < buttonSize) {
            return this.toggleExpanded();
        }

        // Dragging support (would be implemented in full version)
        return false;
    }

    /**
     * Set dashboard position
     */
    setPosition(x, y) {
        this.position = { x, y };
    }

    /**
     * Get dashboard dimensions when expanded
     */
    getExpandedDimensions() {
        return { width: 400, height: 500 };
    }

    /**
     * Get dashboard dimensions when compact
     */
    getCompactDimensions() {
        return { width: 250, height: 120 };
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = PerformanceDashboard;
}
