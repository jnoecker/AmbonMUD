/**
 * Performance Profiler (Phase 5c)
 * Collects real-time performance metrics for monitoring
 */
class PerformanceProfiler {
    constructor() {
        this.fps = 60;
        this.frameTime = 0;
        this.memory = 0;
        this.fpsHistory = [];
        this.frameTimeHistory = [];
        this.memoryHistory = [];
        this.warnings = [];
        this.phaseTimings = {};
        this.renderStats = {
            layersRendered: 0,
            tilesRendered: 0,
            entitiesRendered: 0,
            particlesActive: 0,
        };
        this.networkLatency = 0;
        this.lastFrameTime = performance.now();
    }

    updateFrameTiming() {
        const now = performance.now();
        this.frameTime = now - this.lastFrameTime;
        this.lastFrameTime = now;
        this.frameTimeHistory.push(this.frameTime);
        if (this.frameTimeHistory.length > 60) {
            this.frameTimeHistory.shift();
        }
        this.updateWarnings();
    }

    updateMemoryUsage() {
        if (performance.memory) {
            this.memory = performance.memory.usedJSHeapSize / (1024 * 1024); // MB
            this.memoryHistory.push(this.memory);
            if (this.memoryHistory.length > 30) {
                this.memoryHistory.shift();
            }
        }
    }

    recordPhaseTiming(phase, duration) {
        this.phaseTimings[phase] = duration;
    }

    updateRenderStats(stats) {
        this.renderStats = { ...this.renderStats, ...stats };
    }

    setNetworkLatency(latency) {
        this.networkLatency = latency;
    }

    updateFps(fps) {
        this.fps = fps;
        this.fpsHistory.push(fps);
        if (this.fpsHistory.length > 30) {
            this.fpsHistory.shift();
        }
        this.updateWarnings();
    }

    updateWarnings() {
        this.warnings = [];
        if (this.fps < 50) {
            this.warnings.push({ type: 'fps', message: `FPS: ${this.fps}`, severity: 'warning' });
        }
        const avgFrameTime = this.frameTimeHistory.length > 0
            ? this.frameTimeHistory.reduce((a, b) => a + b) / this.frameTimeHistory.length
            : 0;
        if (avgFrameTime > 20) {
            this.warnings.push({ type: 'frame', message: `Frame: ${avgFrameTime.toFixed(1)}ms`, severity: 'warning' });
        }
        if (this.memory > 10) {
            this.warnings.push({ type: 'memory', message: `Memory: ${this.memory.toFixed(1)}MB`, severity: 'warning' });
        }
    }

    getMetrics() {
        const avgFps = this.fpsHistory.length > 0
            ? Math.round(this.fpsHistory.reduce((a, b) => a + b) / this.fpsHistory.length)
            : 60;
        const minFps = this.fpsHistory.length > 0 ? Math.min(...this.fpsHistory) : 60;
        const maxFps = this.fpsHistory.length > 0 ? Math.max(...this.fpsHistory) : 60;
        const avgFrameTime = this.frameTimeHistory.length > 0
            ? this.frameTimeHistory.reduce((a, b) => a + b) / this.frameTimeHistory.length
            : 0;
        return {
            fps: Math.round(this.fps),
            fpsMin: minFps,
            fpsMax: maxFps,
            fpsAvg: avgFps,
            frameTime: this.frameTime.toFixed(1),
            frameTimeAvg: avgFrameTime.toFixed(1),
            memory: this.memory,
            memoryFormatted: this.memory.toFixed(1) + ' MB',
            renderStats: this.renderStats,
            networkLatency: this.networkLatency,
            warnings: this.warnings,
            phaseTimings: this.phaseTimings,
        };
    }

    getMetricsFormatted() {
        const metrics = this.getMetrics();
        return {
            fps: `${metrics.fps} fps`,
            frameTime: `${metrics.frameTime}ms`,
            memory: metrics.memoryFormatted,
        };
    }

    getFpsGraphData(width, height) {
        if (this.fpsHistory.length === 0) return [];
        const maxFps = Math.max(...this.fpsHistory, 60);
        return this.fpsHistory.map((fps, i) => ({
            x: (i / this.fpsHistory.length) * width,
            y: height - (fps / maxFps) * height,
        }));
    }

    getFrameTimeGraphData(width, height) {
        if (this.frameTimeHistory.length === 0) return [];
        const maxTime = Math.max(...this.frameTimeHistory, 20);
        return this.frameTimeHistory.map((time, i) => ({
            x: (i / this.frameTimeHistory.length) * width,
            y: height - (time / maxTime) * height,
        }));
    }

    getMemoryGraphData(width, height) {
        if (this.memoryHistory.length === 0) return [];
        const maxMemory = Math.max(...this.memoryHistory, 10);
        return this.memoryHistory.map((mem, i) => ({
            x: (i / this.memoryHistory.length) * width,
            y: height - (mem / maxMemory) * height,
        }));
    }

    getOptimizationRecommendations() {
        const recs = [];
        if (this.fps < 50) {
            recs.push('Lower quality settings to improve performance');
        }
        if (this.memory > 10) {
            recs.push('Consider reducing particle count and effect intensity');
        }
        if (this.renderStats.tilesRendered > 500) {
            recs.push('Reduce zoom level or enable LOD to improve tile rendering');
        }
        return recs;
    }
}
