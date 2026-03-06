import type { Application } from "pixi.js";
import { gameStateRef } from "./GameStateBridge";
import { WorldScene } from "./scenes/WorldScene";
import { BattleScene } from "./scenes/BattleScene";
import { DialogueOverlay } from "./systems/DialogueOverlay";

export type SceneName = "world" | "battle";

export class SceneManager {
  private app: Application;
  private worldScene: WorldScene;
  private battleScene: BattleScene;
  private dialogueOverlay: DialogueOverlay;
  private currentScene: SceneName = "world";
  private wasInCombat = false;

  constructor(app: Application) {
    this.app = app;
    this.worldScene = new WorldScene();
    this.battleScene = new BattleScene();
    this.dialogueOverlay = new DialogueOverlay();

    this.app.stage.addChild(this.worldScene.container);
    this.battleScene.container.visible = false;
    this.app.stage.addChild(this.battleScene.container);
    // Dialogue overlay renders on top of both scenes
    this.app.stage.addChild(this.dialogueOverlay.container);

    this.resize(this.app.screen.width, this.app.screen.height);
  }

  resize(width: number, height: number) {
    this.worldScene.resize(width, height);
    this.battleScene.resize(width, height);
    this.dialogueOverlay.resize(width);
  }

  update(deltaMs: number) {
    const state = gameStateRef.current;
    const inCombat = state.inCombat;

    // Auto-transition based on combat state
    if (inCombat && !this.wasInCombat) {
      this.switchTo("battle");
    } else if (!inCombat && this.wasInCombat) {
      this.switchTo("world");
    }
    this.wasInCombat = inCombat;

    if (this.currentScene === "world") {
      this.worldScene.update(deltaMs);
    } else {
      this.battleScene.update(deltaMs);
    }

    // Dialogue overlay updates regardless of scene
    this.dialogueOverlay.update();
  }

  private switchTo(scene: SceneName) {
    if (scene === this.currentScene) return;

    if (scene === "battle") {
      this.worldScene.container.visible = false;
      this.battleScene.container.visible = true;
      this.battleScene.enter();
    } else {
      this.battleScene.container.visible = false;
      this.worldScene.container.visible = true;
    }

    this.currentScene = scene;
  }

  get scene(): SceneName {
    return this.currentScene;
  }

  destroy() {
    this.worldScene.destroy();
    this.battleScene.destroy();
    this.dialogueOverlay.destroy();
  }
}
