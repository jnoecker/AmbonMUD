/**
 * AmbonMUD Canvas Interaction System
 * Phase 4: Mouse/Touch Event Handling
 *
 * Handles clicks on exits, mobs, and other interactive canvas elements.
 */

class CanvasInteraction {
    constructor(canvas, camera, callbacks = {}) {
        this.canvas = canvas;
        this.camera = camera;
        this.callbacks = {
            onExitClick: callbacks.onExitClick || (() => {}),
            onMobClick: callbacks.onMobClick || (() => {}),
            onTileClick: callbacks.onTileClick || (() => {}),
            onHover: callbacks.onHover || (() => {}),
        };

        this.hoveredElement = null;
        this.setupEventListeners();
    }

    setupEventListeners() {
        // Mouse events
        this.canvas.addEventListener('click', (e) => this.handleClick(e));
        this.canvas.addEventListener('mousemove', (e) => this.handleMouseMove(e));
        this.canvas.addEventListener('mouseleave', () => this.handleMouseLeave());

        // Touch events (for mobile)
        this.canvas.addEventListener('touchstart', (e) => this.handleTouchStart(e));
        this.canvas.addEventListener('touchmove', (e) => this.handleTouchMove(e));
        this.canvas.addEventListener('touchend', () => this.handleMouseLeave());

        // Wheel zoom
        this.canvas.addEventListener('wheel', (e) => this.handleWheel(e), { passive: false });
    }

    /**
     * Handle canvas click
     */
    handleClick(e) {
        const pos = this.getEventPosition(e);
        const worldPos = this.camera.screenToWorld(pos.x, pos.y);

        // Check if clicked on exit portal
        const exit = this.findExitAtPosition(worldPos);
        if (exit) {
            this.callbacks.onExitClick(exit);
            return;
        }

        // Check if clicked on mob
        const mob = this.findMobAtPosition(worldPos);
        if (mob) {
            this.callbacks.onMobClick(mob);
            return;
        }

        // Clicked on empty tile
        this.callbacks.onTileClick(worldPos);
    }

    /**
     * Handle mouse movement for hovering
     */
    handleMouseMove(e) {
        const pos = this.getEventPosition(e);
        const worldPos = this.camera.screenToWorld(pos.x, pos.y);

        // Check for hoverable elements
        const exit = this.findExitAtPosition(worldPos);
        const mob = this.findMobAtPosition(worldPos);

        let hoveredElement = null;
        if (exit) {
            hoveredElement = { type: 'exit', data: exit, name: exit.direction };
            this.canvas.style.cursor = 'pointer';
        } else if (mob) {
            hoveredElement = { type: 'mob', data: mob, name: mob.name };
            this.canvas.style.cursor = 'pointer';
        } else {
            this.canvas.style.cursor = 'default';
        }

        if (hoveredElement !== this.hoveredElement) {
            this.hoveredElement = hoveredElement;
            this.callbacks.onHover(hoveredElement);
        }
    }

    /**
     * Handle mouse leave
     */
    handleMouseLeave() {
        this.hoveredElement = null;
        this.callbacks.onHover(null);
        this.canvas.style.cursor = 'default';
    }

    /**
     * Handle touch start
     */
    handleTouchStart(e) {
        if (e.touches.length === 2) {
            // Two-finger zoom (prepare)
            this.touchDistance = this.getTouchDistance(e.touches);
        } else if (e.touches.length === 1) {
            // Single touch (like click)
            const touch = e.touches[0];
            const pos = this.getEventPosition(touch);
            const worldPos = this.camera.screenToWorld(pos.x, pos.y);

            const exit = this.findExitAtPosition(worldPos);
            if (exit) {
                this.callbacks.onExitClick(exit);
            }

            const mob = this.findMobAtPosition(worldPos);
            if (mob) {
                this.callbacks.onMobClick(mob);
            }
        }
    }

    /**
     * Handle touch movement (pinch zoom)
     */
    handleTouchMove(e) {
        if (e.touches.length === 2 && this.touchDistance !== undefined) {
            const newDistance = this.getTouchDistance(e.touches);
            const scale = newDistance / this.touchDistance;

            if (scale > 1.1) {
                this.camera.zoomIn(0.1);
                this.touchDistance = newDistance;
            } else if (scale < 0.9) {
                this.camera.zoomOut(0.1);
                this.touchDistance = newDistance;
            }

            e.preventDefault();
        }
    }

    /**
     * Handle mouse wheel zoom
     */
    handleWheel(e) {
        e.preventDefault();

        if (e.deltaY < 0) {
            this.camera.zoomIn(0.1);
        } else {
            this.camera.zoomOut(0.1);
        }
    }

    /**
     * Get position from mouse or touch event
     */
    getEventPosition(e) {
        if (e.clientX !== undefined) {
            return { x: e.clientX, y: e.clientY };
        }
        if (e.touches && e.touches.length > 0) {
            return { x: e.touches[0].clientX, y: e.touches[0].clientY };
        }
        return { x: 0, y: 0 };
    }

    /**
     * Get distance between two touch points
     */
    getTouchDistance(touches) {
        const dx = touches[0].clientX - touches[1].clientX;
        const dy = touches[0].clientY - touches[1].clientY;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Find exit portal at position
     */
    findExitAtPosition(pos, range = 25) {
        // This will be populated by the renderer
        // For now, return null - actual exits passed from game state
        return null;
    }

    /**
     * Find mob at position
     */
    findMobAtPosition(pos, range = 12) {
        // This will be populated by the renderer
        // For now, return null - actual mobs checked from game state
        return null;
    }

    /**
     * Enable/disable interaction
     */
    setEnabled(enabled) {
        this.canvas.style.pointerEvents = enabled ? 'auto' : 'none';
    }
}

// ========== EXPORT ==========

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { CanvasInteraction };
}
