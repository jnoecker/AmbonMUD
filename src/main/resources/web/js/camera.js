/**
 * AmbonMUD Camera System
 * Phase 4: Camera Control and Viewport Management
 *
 * Handles smooth camera following, zoom controls, and viewport transforms.
 */

class Camera {
    constructor(canvas) {
        this.canvas = canvas;
        this.x = 0;
        this.y = 0;
        this.zoom = 1;

        // Target values for smooth transitions
        this.targetX = 0;
        this.targetY = 0;
        this.targetZoom = 1;

        // Configuration
        this.minZoom = 0.5;
        this.maxZoom = 3;
        this.followSpeed = 0.1; // Lower = smoother
        this.zoomSpeed = 0.05;
    }

    /**
     * Set the camera target position (what to follow)
     */
    setTarget(x, y) {
        this.targetX = x - this.canvas.width / 2;
        this.targetY = y - this.canvas.height / 2;
    }

    /**
     * Update camera position with smooth easing
     */
    update() {
        // Smooth camera follow
        this.x += (this.targetX - this.x) * this.followSpeed;
        this.y += (this.targetY - this.y) * this.followSpeed;

        // Smooth zoom
        this.zoom += (this.targetZoom - this.zoom) * this.zoomSpeed;

        // Clamp zoom
        this.zoom = Math.max(this.minZoom, Math.min(this.maxZoom, this.zoom));
    }

    /**
     * Zoom in
     */
    zoomIn(amount = 0.2) {
        this.targetZoom = Math.min(this.maxZoom, this.targetZoom + amount);
    }

    /**
     * Zoom out
     */
    zoomOut(amount = 0.2) {
        this.targetZoom = Math.max(this.minZoom, this.targetZoom - amount);
    }

    /**
     * Reset zoom to 1.0
     */
    resetZoom() {
        this.targetZoom = 1;
    }

    /**
     * Apply camera transform to canvas context
     */
    apply(ctx) {
        ctx.translate(this.canvas.width / 2, this.canvas.height / 2);
        ctx.scale(this.zoom, this.zoom);
        ctx.translate(-this.x - this.canvas.width / 2, -this.y - this.canvas.height / 2);
    }

    /**
     * Reset camera transform
     */
    reset(ctx) {
        ctx.setTransform(1, 0, 0, 1, 0, 0);
    }

    /**
     * Convert screen coordinates to world coordinates
     */
    screenToWorld(screenX, screenY) {
        const rect = this.canvas.getBoundingClientRect();
        const sx = screenX - rect.left;
        const sy = screenY - rect.top;

        const worldX = (sx / this.zoom) + this.x;
        const worldY = (sy / this.zoom) + this.y;

        return { x: worldX, y: worldY };
    }

    /**
     * Convert world coordinates to screen coordinates
     */
    worldToScreen(worldX, worldY) {
        const sx = (worldX - this.x) * this.zoom;
        const sy = (worldY - this.y) * this.zoom;

        const rect = this.canvas.getBoundingClientRect();
        return {
            x: sx + rect.left,
            y: sy + rect.top,
        };
    }

    /**
     * Get current view bounds in world coordinates
     */
    getViewBounds() {
        return {
            left: this.x,
            right: this.x + this.canvas.width / this.zoom,
            top: this.y,
            bottom: this.y + this.canvas.height / this.zoom,
        };
    }

    /**
     * Check if a point is visible in the viewport
     */
    isVisible(x, y, margin = 0) {
        const bounds = this.getViewBounds();
        return (
            x >= bounds.left - margin &&
            x <= bounds.right + margin &&
            y >= bounds.top - margin &&
            y <= bounds.bottom + margin
        );
    }

    /**
     * Get camera state for debugging
     */
    getState() {
        return {
            position: { x: this.x, y: this.y },
            zoom: this.zoom,
            target: { x: this.targetX, y: this.targetY },
            targetZoom: this.targetZoom,
            bounds: this.getViewBounds(),
        };
    }
}

// ========== EXPORT ==========

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { Camera };
}
