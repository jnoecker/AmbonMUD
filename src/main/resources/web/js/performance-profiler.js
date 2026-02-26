/**
 * Performance Profiler (Phase 5c)
 * Collects detailed performance metrics and statistics
 * Tracks frame timing, memory, rendering, and network latency
 */

class PerformanceProfiler {
    constructor() {
        // Frame timing
        this.frameTimeHistory = []; // Last 60 frames
        this.frameTimeMax = 60;
        this.currentFrameTime = 0;
        this.lastFrameTime = performance.now();

        // FPS tracking
        this.fps = 60;
        this.fpsHistory = []; // Last 30 FPS samples
        this.fpsMax = 30;
        this.lastFpsUpdate = performance.now();
        this.frameCount = 0;

        // Memory tracking
        this.memoryUsage = 0;
        this.memoryHistory = []; // Last 30 samples
        this.memoryMax = 30;

        // Rendering stats
        this.renderStats = {
            layersRendered: 0,
            tilesRendered: 0,
            entitiesRendered: 0,
            particlesActive: 0,
            particlesEmitted: 0,
        };

        // Performance phases
        this.phaseTimings = {
            'camera.update': 0,
            'effects.update': 0,
            'render': 0,
            'canvas.clear': 0,
            'canvas.render': 0,
            'particle.update': 0,
            'gmcp.process': 0,
        };

        // Network latency
        this.networkLatency = 0;
        this.networkLatencyHistory = [];

        // Warnings
        this.warnings = [];
        this.warningThresholds = {
            fps: 50,           // FPS < 50
            frameTime: 20,     // Frame time > 20ms
            memory: 10 * 1024 * 1024, // Memory > 10MB
            particles: 100,    // Particles > 100
        };
    }

    /**
     * Update frame timing (call once per frame)
     */
    updateFrameTiming() {
        const now = performance.now();
        this.currentFrameTime = now - this.lastFrameTime;
        this.lastFrameTime = now;

        this.frameTimeHistory.push(this.currentFrameTime);
        if (this.frameTimeHistory.length > this.frameTimeMax) {
            this.frameTimeHistory.shift();
        }

        this.frameCount++;

        // Update FPS every 500ms
        if (now - this.lastFpsUpdate > 500) {
            this.fps = Math.round((this.frameCount * 1000) / (now - this.lastFpsUpdate));
            this.fpsHistory.push(this.fps);
            if (this.fpsHistory.length > this.fpsMax) {
                this.fpsHistory.shift();
            }

            this.frameCount = 0;
            this.lastFpsUpdate = now;

            this.checkWarnings();
        }
    }

    /**
     * Update memory usage (periodically, not every frame)
     */
    updateMemoryUsage() {
        if (performance.memory) {
            this.memoryUsage = performance.memory.usedJSHeapSize;
            this.memoryHistory.push(this.memoryUsage);
            if (this.memoryHistory.length > this.memoryMax) {
                this.memoryHistory.shift();
            }
        }
    }

    /**
     * Record phase timing (in milliseconds)
     */
    recordPhaseTiming(phaseName, duration) {
        this.phaseTimings[phaseName] = duration;
    }

    /**
     * Update render statistics
     */
    updateRenderStats(stats) {
        this.renderStats = { ...this.renderStats, ...stats };
    }

    /**
     * Set network latency (ping time from server)
     */
    setNetworkLatency(latencyMs) {
        this.networkLatency = latencyMs;
        this.networkLatencyHistory.push(latencyMs);
        if (this.networkLatencyHistory.length > 30) {
            this.networkLatencyHistory.shift();
        }
    }

    /**
     * Check for performance warnings
     */
    checkWarnings() {
        this.warnings = [];

        if (this.fps < this.warningThresholds.fps) {
            this.warnings.push({
                level: 'warning',
                message: `Low FPS: ${this.fps} (target: ${this.warningThresholds.fps}+)`,
                type: 'fps',
            });
        }

        const avgFrameTime = this.getAverageFrameTime();
        if (avgFrameTime > this.warningThresholds.frameTime) {
            this.warnings.push({
                level: 'warning',
                message: `Slow frames: ${avgFrameTime.toFixed(1)}ms (target: <${this.warningThresholds.frameTime}ms)`,
                type: 'frameTime',
            });
        }

        if (this.memoryUsage > this.warningThresholds.memory) {
            this.warnings.push({
                level: 'warning',
                message: `High memory: ${this.formatBytes(this.memoryUsage)} (target: <${this.formatBytes(this.warningThresholds.memory)})`,
                type: 'memory',
            });
        }

        if (this.renderStats.particlesActive > this.warningThresholds.particles) {
            this.warnings.push({
                level: 'info',
                message: `Many particles: ${this.renderStats.particlesActive} (consider reducing)`,
                type: 'particles',
            });
        }
    }

    /**
     * Get current metrics summary
     */
    getMetrics() {
        return {
            fps: this.fps,
            fpsMin: this.fpsHistory.length > 0 ? Math.min(...this.fpsHistory) : 0,
            fpsMax: this.fpsHistory.length > 0 ? Math.max(...this.fpsHistory) : 0,
            fpsAvg: this.getFpsAverage(),
            frameTime: this.currentFrameTime,
            frameTimeAvg: this.getAverageFrameTime(),
            memory: this.memoryUsage,
            memoryFormatted: this.formatBytes(this.memoryUsage),
            renderStats: this.renderStats,
            networkLatency: this.networkLatency,
            warnings: this.warnings,
            phaseTimings: this.phaseTimings,
        };
    }

    /**
     * Get metrics as formatted strings for display
     */
    getMetricsFormatted() {
        const metrics = this.getMetrics();
        return {
            fps: `${metrics.fps}`,
            fpsRange: `${metrics.fpsMin}-${metrics.fpsMax}`,
            fpsAvg: `${metrics.fpsAvg.toFixed(0)}`,
            frameTime: `${metrics.frameTime.toFixed(1)}ms`,
            frameTimeAvg: `${metrics.frameTimeAvg.toFixed(1)}ms`,
            memory: metrics.memoryFormatted,
            particles: `${metrics.renderStats.particlesActive}/${metrics.renderStats.particlesEmitted}`,
            entities: metrics.renderStats.entitiesRendered,
            tiles: metrics.renderStats.tilesRendered,
            latency: `${metrics.networkLatency}ms`,
        };
    }

    /**
     * Get frame time graph data (for rendering)
     */
    getFrameTimeGraphData(width = 200, height = 60) {
        if (this.frameTimeHistory.length === 0) return [];

        const maxTime = 33; // ~30fps = 33ms per frame
        const pointWidth = width / this.frameTimeMax;
        const data = [];

        for (let i = 0; i < this.frameTimeHistory.length; i++) {
            const time = this.frameTimeHistory[i];
            const y = height - (time / maxTime) * height;
            data.push({
                x: i * pointWidth,
                y: Math.max(0, y),
                time,
                isWarning: time > this.warningThresholds.frameTime,
            });
        }

        return data;
    }

    /**
     * Get FPS graph data (for rendering)
     */
    getFpsGraphData(width = 200, height = 60) {
        if (this.fpsHistory.length === 0) return [];

        const maxFps = 120;
        const pointWidth = width / this.fpsMax;
        const data = [];

        for (let i = 0; i < this.fpsHistory.length; i++) {
            const fps = this.fpsHistory[i];
            const y = height - (fps / maxFps) * height;
            data.push({
                x: i * pointWidth,
                y,
                fps,
                isWarning: fps < this.warningThresholds.fps,
            });
        }

        return data;
    }

    /**
     * Get memory usage graph data (for rendering)
     */
    getMemoryGraphData(width = 200, height = 60) {
        if (this.memoryHistory.length === 0) return [];

        const maxMemory = 20 * 1024 * 1024; // 20MB
        const pointWidth = width / this.memoryMax;
        const data = [];

        for (let i = 0; i < this.memoryHistory.length; i++) {
            const mem = this.memoryHistory[i];
            const y = height - (mem / maxMemory) * height;
            data.push({
                x: i * pointWidth,
                y,
                memory: mem,
                isWarning: mem > this.warningThresholds.memory,
            });
        }

        return data;
    }

    /**
     * Get recommendations based on current performance
     */
    getOptimizationRecommendations() {
        const recommendations = [];

        if (this.fps < 50) {
            recommendations.push({
                priority: 'high',
                message: 'Reduce particle effects or lower quality settings',
            });
            recommendations.push({
                priority: 'high',
                message: 'Disable multi-zone rendering if enabled',
            });
        }

        if (this.renderStats.particlesActive > 100) {
            recommendations.push({
                priority: 'medium',
                message: 'Consider reducing particle emission rates',
            });
        }

        if (this.memoryUsage > 10 * 1024 * 1024) {
            recommendations.push({
                priority: 'medium',
                message: 'Memory usage is high; restart browser if available',
            });
        }

        if (this.networkLatency > 200) {
            recommendations.push({
                priority: 'low',
                message: 'Network latency is high; check connection quality',
            });
        }

        return recommendations;
    }

    /**
     * Reset all statistics
     */
    reset() {
        this.frameTimeHistory = [];
        this.fpsHistory = [];
        this.memoryHistory = [];
        this.networkLatencyHistory = [];
        this.warnings = [];
    }

    // ========== UTILITY METHODS ==========

    getFpsAverage() {
        if (this.fpsHistory.length === 0) return 60;
        return this.fpsHistory.reduce((a, b) => a + b) / this.fpsHistory.length;
    }

    getAverageFrameTime() {
        if (this.frameTimeHistory.length === 0) return 16;
        return this.frameTimeHistory.reduce((a, b) => a + b) / this.frameTimeHistory.length;
    }

    formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
    }
}

// Export for use in other modules
if (typeof module !== 'undefined' && module.exports) {
    module.exports = PerformanceProfiler;
}
