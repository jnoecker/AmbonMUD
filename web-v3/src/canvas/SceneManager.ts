import type { Application } from "pixi.js";
import { WorldScene } from "./scenes/WorldScene";

export type SceneName = "world";

export class SceneManager {
  private app: Application;
  private worldScene: WorldScene;
  private currentScene: SceneName = "world";

  constructor(app: Application) {
    this.app = app;
    this.worldScene = new WorldScene();
    this.app.stage.addChild(this.worldScene.container);
    this.resize(this.app.screen.width, this.app.screen.height);
  }

  resize(width: number, height: number) {
    this.worldScene.resize(width, height);
  }

  update(deltaMs: number) {
    if (this.currentScene === "world") {
      this.worldScene.update(deltaMs);
    }
  }

  get scene(): SceneName {
    return this.currentScene;
  }

  destroy() {
    this.worldScene.destroy();
  }
}
