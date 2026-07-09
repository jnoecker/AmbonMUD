import type { Application } from "pixi.js";
import { gameStateRef } from "./GameStateBridge";
import { canvasEvents } from "./CanvasEventBus";
import { WorldScene } from "./scenes/WorldScene";
import { BattleScene } from "./scenes/BattleScene";
import { SketchScene } from "./scenes/SketchScene";
import { DialogueOverlay } from "./systems/DialogueOverlay";

export type SceneName = "world" | "battle" | "sketch";

export class SceneManager {
  private app: Application;
  private worldScene: WorldScene;
  private battleScene: BattleScene;
  private sketchScene: SketchScene;
  private dialogueOverlay: DialogueOverlay;
  private currentScene: SceneName = "world";
  private wasInCombat = false;
  private pendingWorldTransition = false;

  constructor(app: Application) {
    this.app = app;
    this.worldScene = new WorldScene();
    this.battleScene = new BattleScene();
    this.sketchScene = new SketchScene();
    this.dialogueOverlay = new DialogueOverlay();

    this.app.stage.addChild(this.worldScene.container);
    this.app.stage.addChild(this.worldScene.overlayContainer);
    this.battleScene.container.visible = false;
    this.app.stage.addChild(this.battleScene.container);
    this.sketchScene.container.visible = false;
    this.app.stage.addChild(this.sketchScene.container);
    // Dialogue overlay renders on top of all scenes
    this.app.stage.addChild(this.dialogueOverlay.container);

    this.resize(this.app.screen.width, this.app.screen.height);
  }

  resize(width: number, height: number) {
    this.worldScene.resize(width, height);
    this.battleScene.resize(width, height);
    this.sketchScene.resize(width, height);
    this.dialogueOverlay.resize(width, height);
  }

  update(deltaMs: number) {
    const state = gameStateRef.current;
    const inCombat = state.inCombat;
    const sketching = state.activeSketch != null;

    // Auto-transition based on combat state. Combat outranks sketching: a
    // failed sketch that angers the subject flows straight into the battle
    // scene the moment the server engages combat.
    if (inCombat && !this.wasInCombat) {
      this.pendingWorldTransition = false;
      this.switchTo("battle");
    } else if (!inCombat && this.wasInCombat) {
      // Don't switch immediately — let the death animation play out
      if (this.battleScene.isDeathAnimating) {
        this.pendingWorldTransition = true;
      } else {
        this.switchTo(sketching ? "sketch" : "world");
      }
    }
    this.wasInCombat = inCombat;

    // Complete deferred transition once death animation finishes
    if (this.pendingWorldTransition && !this.battleScene.isDeathAnimating) {
      this.pendingWorldTransition = false;
      this.switchTo(sketching ? "sketch" : "world");
    }

    // Sketch scene: enter when a sketch starts, hold through the result
    // flourish (mirrors the battle scene's death-animation deferral).
    if (!inCombat && this.currentScene === "world" && sketching) {
      this.switchTo("sketch");
    }
    if (this.currentScene === "sketch" && !inCombat && !sketching && !this.sketchScene.isResultAnimating) {
      this.switchTo("world");
    }

    if (this.currentScene === "world") {
      this.worldScene.update(deltaMs);
    } else if (this.currentScene === "battle") {
      this.battleScene.update(deltaMs);
    } else {
      this.sketchScene.update(deltaMs);
    }

    // Dialogue overlay updates regardless of scene
    this.dialogueOverlay.update();
  }

  private switchTo(scene: SceneName) {
    if (scene === this.currentScene) return;

    this.worldScene.container.visible = scene === "world";
    this.worldScene.overlayContainer.visible = scene === "world";
    this.battleScene.container.visible = scene === "battle";
    this.sketchScene.container.visible = scene === "sketch";

    if (scene === "battle") {
      this.battleScene.enter();
      // A sketch interrupted by combat never resolves in-scene — drop its
      // queued events so they don't replay on the next sketch.
      canvasEvents.drainSketches();
    } else if (scene === "sketch") {
      this.sketchScene.enter();
    } else {
      // Discard any combat/gain/sketch events still queued from the scene
      // that just ended, so they don't replay when the next one starts.
      canvasEvents.drain();
      canvasEvents.drainSketches();
    }

    this.currentScene = scene;
  }

  get scene(): SceneName {
    return this.currentScene;
  }

  destroy() {
    this.worldScene.destroy();
    this.battleScene.destroy();
    this.sketchScene.destroy();
    this.dialogueOverlay.destroy();
  }
}
