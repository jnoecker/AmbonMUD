/**
 * Performance Dashboard (Phase 5c)
 * Real-time performance monitoring and quality control UI
 */
class PerformanceDashboard {
    constructor(profiler, qualitySettings) {
        this.profiler = profiler;
        this.qualitySettings = qualitySettings;
        this.isVisible = false;
        this.isExpanded = false;
        this.container = null;
        this.canvas = document.createElement('canvas');
        this.ctx = this.canvas.getContext('2d');
        this.lastUpdate = performance.now();

        this.setupCanvas();
        this.setupKeyboardShortcut();
    }

    setupCanvas() {
        this.canvas.id = 'performance-dashboard-canvas';
        this.canvas.style.position = 'fixed';
        this.canvas.style.top = '10px';
        this.canvas.style.left = '10px';
        this.canvas.style.zIndex = '10000';
        this.canvas.style.backgroundColor = 'transparent';
        this.canvas.style.cursor = 'default';
        this.canvas.width = 250;
        this.canvas.height = 120;
        this.canvas.addEventListener('click', (e) => this.handleClick(e));
        document.body.appendChild(this.canvas);
    }

    setupKeyboardShortcut() {
        document.addEventListener('keydown', (e) => {
            if (e.altKey && e.key === 'd') {
                this.toggle();
            }
        });
    }

    toggle() {
        this.isVisible = !this.isVisible;
        if (!this.isVisible) {
            this.canvas.style.display = 'none';
        } else {
            this.canvas.style.display = 'block';
            this.render();
        }
    }

    handleClick(e) {
        const rect = this.canvas.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;

        // Check if clicked the expand button (top-right)
        if (x > this.canvas.width - 30 && y < 30) {
            this.isExpanded = !this.isExpanded;
            if (this.isExpanded) {
                this.canvas.width = 400;
                this.canvas.height = 500;
            } else {
                this.canvas.width = 250;
                this.canvas.height = 120;
            }
            this.render();
        }
    }

    render() {
        if (!this.isVisible) return;

        const now = performance.now();
        if (now - this.lastUpdate < 100) return; // Update at most 10 times per second
        this.lastUpdate = now;

        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

        if (this.isExpanded) {
            this.renderExpanded();
        } else {
            this.renderCompact();
        }
    }

    renderCompact() {
        const metrics = this.profiler.getMetrics();
        const formatted = this.profiler.getMetricsFormatted();

        // Background
        this.ctx.fillStyle = 'rgba(20, 30, 50, 0.8)';
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

        // Border
        this.ctx.strokeStyle = 'rgba(216, 197, 232, 0.4)';
        this.ctx.lineWidth = 1;
        this.ctx.strokeRect(0, 0, this.canvas.width, this.canvas.height);

        // Title
        this.ctx.fillStyle = 'rgba(216, 197, 232, 0.8)';
        this.ctx.font = 'bold 12px monospace';
        this.ctx.fillText('Performance', 5, 15);

        // Metrics
        this.ctx.font = '10px monospace';
        this.ctx.fillStyle = 'rgba(200, 220, 240, 0.9)';
        let y = 30;
        this.ctx.fillText(`FPS: ${metrics.fps}`, 5, y);
        y += 15;
        this.ctx.fillText(`Frame: ${formatted.frameTime}`, 5, y);
        y += 15;
        this.ctx.fillText(`Memory: ${formatted.memory}`, 5, y);
        y += 15;
        this.ctx.fillText(`Quality: ${this.qualitySettings.getQualityLevelName()}`, 5, y);

        // Expand button
        this.ctx.fillStyle = 'rgba(216, 197, 232, 0.6)';
        this.ctx.fillRect(this.canvas.width - 25, 5, 20, 20);
        this.ctx.fillStyle = 'rgba(20, 30, 50, 0.9)';
        this.ctx.font = 'bold 12px monospace';
        this.ctx.textAlign = 'center';
        this.ctx.fillText('[+]', this.canvas.width - 15, 18);
        this.ctx.textAlign = 'left';
    }

    renderExpanded() {
        const metrics = this.profiler.getMetrics();
        const formatted = this.profiler.getMetricsFormatted();

        // Background
        this.ctx.fillStyle = 'rgba(20, 30, 50, 0.95)';
        this.ctx.fillRect(0, 0, this.canvas.width, this.canvas.height);

        // Border
        this.ctx.strokeStyle = 'rgba(216, 197, 232, 0.5)';
        this.ctx.lineWidth = 2;
        this.ctx.strokeRect(0, 0, this.canvas.width, this.canvas.height);

        // Title
        this.ctx.fillStyle = 'rgba(216, 197, 232, 0.9)';
        this.ctx.font = 'bold 14px monospace';
        this.ctx.fillText('Performance Dashboard', 10, 25);

        // Left column: Metrics
        this.ctx.font = '11px monospace';
        this.ctx.fillStyle = 'rgba(200, 220, 240, 0.9)';
        let y = 50;
        const lineHeight = 18;

        this.ctx.fillText(`FPS: ${metrics.fps} (avg: ${metrics.fpsAvg})`, 10, y);
        y += lineHeight;
        this.ctx.fillText(`Frame: ${formatted.frameTime}`, 10, y);
        y += lineHeight;
        this.ctx.fillText(`Memory: ${formatted.memory}`, 10, y);
        y += lineHeight;
        this.ctx.fillText(`Particles: ${metrics.renderStats.particlesActive}/${this.qualitySettings.particleLimit}`, 10, y);
        y += lineHeight;
        this.ctx.fillText(`Entities: ${metrics.renderStats.entitiesRendered}`, 10, y);
        y += lineHeight;
        this.ctx.fillText(`Tiles: ${metrics.renderStats.tilesRendered}`, 10, y);
        y += lineHeight;
        this.ctx.fillText(`Latency: ${metrics.networkLatency}ms`, 10, y);

        // Right column: Controls
        y = 50;
        const controlX = this.canvas.width - 180;

        // Quality selector
        this.ctx.fillStyle = 'rgba(216, 197, 232, 0.7)';
        this.ctx.font = 'bold 11px monospace';
        this.ctx.fillText('Quality:', controlX, y);
        y += lineHeight;

        const qualityLevels = ['Low', 'Med', 'High', 'Ultra'];
        for (let i = 0; i < qualityLevels.length; i++) {
            const levelKey = ['low', 'medium', 'high', 'ultra'][i];
            const isSelected = this.qualitySettings.qualityLevel === levelKey;
            this.ctx.fillStyle = isSelected ? 'rgba(120, 216, 167, 0.9)' : 'rgba(200, 220, 240, 0.6)';
            this.ctx.fillRect(controlX, y - 10, 35, 14);
            this.ctx.fillStyle = isSelected ? 'rgba(20, 30, 50, 0.9)' : 'rgba(200, 220, 240, 0.8)';
            this.ctx.font = isSelected ? 'bold 9px monospace' : '9px monospace';
            this.ctx.textAlign = 'center';
            this.ctx.fillText(qualityLevels[i], controlX + 17, y);
            this.ctx.textAlign = 'left';
            y += 16;
        }

        // Warnings
        y = 230;
        if (metrics.warnings.length > 0) {
            this.ctx.fillStyle = 'rgba(232, 165, 165, 0.8)';
            this.ctx.font = 'bold 11px monospace';
            this.ctx.fillText('Warnings:', 10, y);
            y += lineHeight;
            this.ctx.font = '10px monospace';
            for (const warning of metrics.warnings) {
                this.ctx.fillText(`• ${warning.message}`, 10, y);
                y += lineHeight;
            }
        }

        // Recommendations
        y += 10;
        const recs = this.profiler.getOptimizationRecommendations();
        if (recs.length > 0) {
            this.ctx.fillStyle = 'rgba(184, 216, 232, 0.8)';
            this.ctx.font = 'bold 11px monospace';
            this.ctx.fillText('Suggestions:', 10, y);
            y += lineHeight;
            this.ctx.font = '10px monospace';
            for (const rec of recs) {
                this.ctx.fillText(`• ${rec}`, 10, y);
                y += lineHeight;
            }
        }

        // Collapse button
        this.ctx.fillStyle = 'rgba(216, 197, 232, 0.6)';
        this.ctx.fillRect(this.canvas.width - 25, 10, 20, 20);
        this.ctx.fillStyle = 'rgba(20, 30, 50, 0.9)';
        this.ctx.font = 'bold 12px monospace';
        this.ctx.textAlign = 'center';
        this.ctx.fillText('[-]', this.canvas.width - 15, 23);
        this.ctx.textAlign = 'left';
    }

    updateFps(fps) {
        this.profiler.updateFps(fps);
        this.render();
    }

    show() {
        this.isVisible = true;
        this.canvas.style.display = 'block';
        this.render();
    }

    hide() {
        this.isVisible = false;
        this.canvas.style.display = 'none';
    }
}
