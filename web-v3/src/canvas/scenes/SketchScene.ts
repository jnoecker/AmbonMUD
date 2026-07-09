import { Container, Graphics, Sprite, Text, Texture } from "pixi.js";
import { gameStateRef } from "../GameStateBridge";
import { canvasEvents } from "../CanvasEventBus";
import { GainPopupSystem } from "../systems/GainPopup";
import { loadTexture } from "../textureLoader";
import { parseHexTint, makeVariantColorize } from "../variantTint";
import type { ArcanumSketchEvent } from "../../types";

const SUBJECT_SIZE = 260;
const PLAYER_SIZE = 170;
const PAGE_W = 300;
const PAGE_H = 360;

const PLAYER_TINT = 0x81a2be;
const SUBJECT_TINT = 0xf0c674;
const PAGE_COLOR = 0xe9dcc0;
const PAGE_EDGE = 0xb8a67e;
const INK = 0x2e2418;
const GOLD = 0xf0c674;
const RUIN = 0x8a3b34;
const LABEL_COLOR = "#d8dcef";

/** Deterministic-enough pseudo-random for stroke layout (no gameplay meaning). */
function jitter(seed: number): number {
  const x = Math.sin(seed * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}

interface InkMote {
  x: number;
  y: number;
  t: number; // 0..1 along its flight
  speed: number;
  drift: number;
}

type ResultKind = "success" | "fail" | "cancel" | null;

/**
 * The sketching sequence — the pacifist twin of BattleScene. While an
 * Arcanum sketch is in flight (`state.activeSketch`), the view becomes a
 * quiet study: the subject poses on the right, the player stands at their
 * journal on the left, and the creature's likeness draws itself onto a
 * parchment page stroke by stroke as ink motes drift off the subject into
 * the book. A progress ring around the page fills over the server-declared
 * duration; the outcome event then plays a stamp flourish ("RECORDED", plus
 * a star burst for world-firsts), an ink-blot ruin, or a quick fade for a
 * cancel. A failure that angers the subject hands off to the battle scene,
 * which takes scene priority the moment combat starts.
 */
export class SketchScene {
  readonly container = new Container();

  private background: Sprite | null = null;
  private bgLoadToken = 0;
  private lastRoomImage: string | null | undefined = "\0";

  private playerSprite: Sprite | null = null;
  private lastPlayerSpritePath: string | null = "\0";
  private subjectSprite: Sprite | null = null;
  private lastSubjectKey: string | null = "\0";

  private uiGraphics = new Graphics();
  private pageGraphics = new Graphics();
  private strokeGraphics = new Graphics();
  private effectGraphics = new Graphics();
  private subjectLabel: Text;
  private hintLabel: Text;
  private resultText: Text | null = null;
  private resultSubText: Text | null = null;
  private gainPopups = new GainPopupSystem();

  /** Pre-generated stroke polylines revealed by progress (the drawing "appearing"). */
  private strokes: Array<Array<{ x: number; y: number }>> = [];
  private motes: InkMote[] = [];
  private moteSpawnAccum = 0;

  private result: ResultKind = null;
  private resultElapsed = 0;
  private resultDuration = 0;
  private resultWorldFirst = false;

  private fadeElapsed = 0;
  private fadingIn = true;
  private elapsedTotal = 0;

  private width = 0;
  private height = 0;

  constructor() {
    this.subjectLabel = new Text({
      text: "",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 18, fill: "#f0c674", fontWeight: "bold" },
    });
    this.subjectLabel.anchor.set(0.5, 0);
    this.hintLabel = new Text({
      text: "Sketching…",
      style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 13, fill: LABEL_COLOR },
    });
    this.hintLabel.anchor.set(0.5, 0);

    this.container.addChild(this.uiGraphics);
    this.container.addChild(this.pageGraphics);
    this.container.addChild(this.strokeGraphics);
    this.container.addChild(this.effectGraphics);
    this.container.addChild(this.subjectLabel);
    this.container.addChild(this.hintLabel);
    this.container.addChild(this.gainPopups.container);
  }

  resize(width: number, height: number) {
    this.width = width;
    this.height = height;
    if (this.background) {
      this.background.width = width;
      this.background.height = height;
    }
  }

  /** True while a success/fail/cancel flourish is still playing. */
  get isResultAnimating(): boolean {
    return this.result !== null && this.resultElapsed < this.resultDuration;
  }

  enter() {
    this.fadingIn = true;
    this.fadeElapsed = 0;
    this.elapsedTotal = 0;
    this.container.alpha = 0;
    this.result = null;
    this.resultElapsed = 0;
    this.resultWorldFirst = false;
    this.motes = [];
    this.strokes = [];
    this.removeResultText();
    this.gainPopups.clear();
    this.lastRoomImage = "\0";
    this.lastPlayerSpritePath = "\0";
    this.lastSubjectKey = "\0";
  }

  update(deltaMs: number) {
    this.elapsedTotal += deltaMs;
    if (this.fadingIn) {
      this.fadeElapsed += deltaMs;
      this.container.alpha = Math.min(1, this.fadeElapsed / 300);
      if (this.fadeElapsed >= 300) this.fadingIn = false;
    }

    const state = gameStateRef.current;
    const sketch = state.activeSketch;

    if (state.room.image !== this.lastRoomImage) {
      this.lastRoomImage = state.room.image;
      this.loadBackground(state.room.image ?? null);
    }
    if (state.character.sprite !== this.lastPlayerSpritePath) {
      this.lastPlayerSpritePath = state.character.sprite;
      this.loadPlayerSprite(state.character.sprite);
    }

    // Subject sprite from the live room mob (image/tint), keyed on the mob id
    // captured at sketch start so a departing subject doesn't blank the pose.
    const mobId = sketch?.mobId ?? this.lastSubjectKey;
    if (sketch && sketch.mobId !== this.lastSubjectKey) {
      this.lastSubjectKey = sketch.mobId;
      const mob = state.mobs.find((m) => m.id === sketch.mobId);
      const image = mob?.image ?? state.serverAssets[`default_mob_${mob?.category ?? "humanoid"}`] ?? null;
      this.loadSubjectSprite(image, mob?.tint ?? null);
      this.subjectLabel.text = sketch.mobName;
      this.strokes = this.buildStrokes(sketch.subjectKey);
    }
    void mobId;

    // Drain lifecycle events — a terminal phase starts the result animation.
    for (const event of canvasEvents.drainSketches()) {
      if (event.phase === "start") continue; // scene entry already reflects it
      this.beginResult(event);
    }

    const progress = this.currentProgress(sketch);
    this.layout(progress);

    if (this.result === null) {
      this.updateMotes(deltaMs, progress);
    }
    if (this.result !== null) {
      this.resultElapsed += deltaMs;
      this.drawResult();
    }
    this.gainPopups.update(deltaMs);
  }

  /** 0..1 sketch completion; holds at 1 once a result is playing. */
  private currentProgress(sketch: { durationMs: number; startedAtMs: number } | null): number {
    if (this.result !== null) return 1;
    if (!sketch || sketch.durationMs <= 0) return 0;
    return Math.min(1, (Date.now() - sketch.startedAtMs) / sketch.durationMs);
  }

  private beginResult(event: ArcanumSketchEvent) {
    this.result = event.phase === "success" ? "success" : event.phase === "fail" ? "fail" : "cancel";
    this.resultElapsed = 0;
    this.resultWorldFirst = event.worldFirst === true;
    this.resultDuration = event.phase === "success" ? (this.resultWorldFirst ? 2400 : 1800) : event.phase === "fail" ? 1400 : 500;

    if (event.phase === "success") {
      const headline = event.firstTime === false ? "PAGE RETOUCHED" : "RECORDED ✒";
      this.showResultText(headline, "#f0c674", this.resultWorldFirst ? `★ First ${event.observe ? "account" : "illumination"} in the world` : null);
      if ((event.xpGained ?? 0) > 0) {
        this.gainPopups.spawn(
          { type: "xp", amount: event.xpGained ?? 0, source: "illumination", newLevel: null, hpGained: null, manaGained: null },
          this.width / 2,
          this.height * 0.2,
        );
      }
    } else if (event.phase === "fail") {
      this.showResultText("THE SKETCH IS RUINED", "#d4888a", event.hostile ? `${event.mobName} turns on you!` : null);
    } else if (event.reason) {
      this.showResultText("", "#d8dcef", event.reason);
    }
  }

  private showResultText(headline: string, color: string, sub: string | null) {
    this.removeResultText();
    if (headline) {
      this.resultText = new Text({
        text: headline,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 26, fill: color, fontWeight: "bold", letterSpacing: 5 },
      });
      this.resultText.anchor.set(0.5);
      this.container.addChild(this.resultText);
    }
    if (sub) {
      this.resultSubText = new Text({
        text: sub,
        style: { fontFamily: "JetBrains Mono, Cascadia Mono, monospace", fontSize: 14, fill: color },
      });
      this.resultSubText.anchor.set(0.5);
      this.container.addChild(this.resultSubText);
    }
  }

  private removeResultText() {
    if (this.resultText) {
      this.container.removeChild(this.resultText);
      this.resultText.destroy();
      this.resultText = null;
    }
    if (this.resultSubText) {
      this.container.removeChild(this.resultSubText);
      this.resultSubText.destroy();
      this.resultSubText = null;
    }
  }

  /**
   * Rough gesture-drawing strokes seeded from the subject key: a loose oval
   * body, a head circle, and a few hatching lines. Intentionally abstract —
   * it reads as "an artist blocking in a figure" for any creature.
   */
  private buildStrokes(seedKey: string): Array<Array<{ x: number; y: number }>> {
    let seed = 0;
    for (let i = 0; i < seedKey.length; i++) seed = (seed * 31 + seedKey.charCodeAt(i)) % 100000;
    const strokes: Array<Array<{ x: number; y: number }>> = [];
    const cx = 0;
    const cy = 20;

    // Body oval (two arcs), head, then hatching — in sketching order.
    const oval = (rx: number, ry: number, from: number, to: number, steps: number, wobble: number, s: number) => {
      const pts: Array<{ x: number; y: number }> = [];
      for (let i = 0; i <= steps; i++) {
        const a = from + ((to - from) * i) / steps;
        const w = (jitter(s + i) - 0.5) * wobble;
        pts.push({ x: cx + Math.cos(a) * (rx + w), y: cy + Math.sin(a) * (ry + w) });
      }
      return pts;
    };
    strokes.push(oval(88, 62, Math.PI * 0.15, Math.PI * 1.15, 14, 8, seed + 1));
    strokes.push(oval(88, 62, Math.PI * 1.05, Math.PI * 2.15, 14, 8, seed + 40));
    strokes.push(oval(34, 32, 0, Math.PI * 2, 12, 5, seed + 80).map((p) => ({ x: p.x + 62, y: p.y - 74 })));
    for (let h = 0; h < 5; h++) {
      const hx = cx - 60 + h * 26 + (jitter(seed + 200 + h) - 0.5) * 10;
      const hy = cy - 10 + (jitter(seed + 300 + h) - 0.5) * 30;
      strokes.push([
        { x: hx, y: hy },
        { x: hx + 18, y: hy + 26 },
      ]);
    }
    return strokes;
  }

  private updateMotes(deltaMs: number, progress: number) {
    // Spawn a gentle stream of ink motes while actively sketching.
    if (progress > 0 && progress < 1) {
      this.moteSpawnAccum += deltaMs;
      while (this.moteSpawnAccum > 120) {
        this.moteSpawnAccum -= 120;
        this.motes.push({ x: 0, y: 0, t: 0, speed: 0.55 + jitter(this.elapsedTotal) * 0.4, drift: (jitter(this.elapsedTotal + 7) - 0.5) * 70 });
      }
    }
    for (const mote of this.motes) mote.t += (deltaMs / 1600) * mote.speed;
    this.motes = this.motes.filter((m) => m.t < 1);
  }

  private subjectPos() {
    return { x: this.width * 0.72, y: this.height * 0.42 };
  }

  private pagePos() {
    return { x: this.width * 0.3, y: this.height * 0.46 };
  }

  private layout(progress: number) {
    const w = this.width;
    const h = this.height;
    if (w === 0 || h === 0) return;

    this.uiGraphics.clear();
    // Warm parchment-toned study overlay (the battle scene's red, but calm).
    this.uiGraphics.rect(0, 0, w, h);
    this.uiGraphics.fill({ color: 0x14100a, alpha: 0.6 });

    const subject = this.subjectPos();
    const page = this.pagePos();

    // Subject sways almost imperceptibly — a patient model holding a pose.
    const sway = Math.sin(this.elapsedTotal / 900) * 4;
    if (this.subjectSprite) {
      this.subjectSprite.x = subject.x + sway;
      this.subjectSprite.y = subject.y;
      this.subjectSprite.width = SUBJECT_SIZE;
      this.subjectSprite.height = SUBJECT_SIZE;
    }
    this.subjectLabel.x = subject.x;
    this.subjectLabel.y = subject.y + SUBJECT_SIZE / 2 + 10;

    // Player at their journal, lower-left, with a slight working bob.
    const bob = Math.sin(this.elapsedTotal / 350) * 2;
    if (this.playerSprite) {
      this.playerSprite.x = page.x - PAGE_W / 2 - 70;
      this.playerSprite.y = page.y + PAGE_H / 2 - PLAYER_SIZE / 2 + bob;
      this.playerSprite.width = PLAYER_SIZE;
      this.playerSprite.height = PLAYER_SIZE;
    }

    // The journal page.
    this.pageGraphics.clear();
    this.pageGraphics.roundRect(page.x - PAGE_W / 2, page.y - PAGE_H / 2, PAGE_W, PAGE_H, 8);
    this.pageGraphics.fill({ color: PAGE_COLOR, alpha: 0.96 });
    this.pageGraphics.roundRect(page.x - PAGE_W / 2, page.y - PAGE_H / 2, PAGE_W, PAGE_H, 8);
    this.pageGraphics.stroke({ color: PAGE_EDGE, width: 2, alpha: 0.9 });
    // Spine shadow on the left edge.
    this.pageGraphics.rect(page.x - PAGE_W / 2, page.y - PAGE_H / 2, 10, PAGE_H);
    this.pageGraphics.fill({ color: PAGE_EDGE, alpha: 0.35 });

    // Progress ring around the page (gold arc), plus the hint line.
    const ringR = Math.max(PAGE_W, PAGE_H) / 2 + 26;
    if (this.result === null) {
      this.pageGraphics.arc(page.x, page.y, ringR, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * progress);
      this.pageGraphics.stroke({ color: GOLD, width: 3, alpha: 0.85 });
    }
    this.hintLabel.text = this.result !== null ? "" : "Sketching… (moving or fighting abandons the page)";
    this.hintLabel.x = page.x;
    this.hintLabel.y = page.y + PAGE_H / 2 + 30;

    // Strokes revealed by progress — the drawing appearing on the page.
    this.strokeGraphics.clear();
    const totalStrokes = this.strokes.length;
    const revealed = progress * totalStrokes;
    for (let i = 0; i < totalStrokes; i++) {
      const pts = this.strokes[i];
      const frac = Math.max(0, Math.min(1, revealed - i));
      if (frac <= 0) break;
      const count = Math.max(2, Math.ceil(pts.length * frac));
      this.strokeGraphics.moveTo(page.x + pts[0].x * 0.9, page.y + pts[0].y * 0.9);
      for (let p = 1; p < count; p++) {
        this.strokeGraphics.lineTo(page.x + pts[p].x * 0.9, page.y + pts[p].y * 0.9);
      }
      this.strokeGraphics.stroke({ color: INK, width: 2.4, alpha: 0.85 });
    }

    // Quill nib: a small diamond riding the tip of the newest stroke.
    if (this.result === null && progress > 0 && progress < 1 && totalStrokes > 0) {
      const i = Math.min(totalStrokes - 1, Math.floor(revealed));
      const pts = this.strokes[i];
      const frac = Math.max(0, Math.min(1, revealed - i));
      const tip = pts[Math.max(0, Math.min(pts.length - 1, Math.ceil(pts.length * frac) - 1))];
      const qx = page.x + tip.x * 0.9;
      const qy = page.y + tip.y * 0.9;
      this.strokeGraphics.poly([qx, qy, qx + 7, qy - 16, qx + 13, qy - 24, qx + 9, qy - 12]);
      this.strokeGraphics.fill({ color: 0xd8dcef, alpha: 0.95 });
    }

    // Ink motes drifting subject → page.
    if (this.result === null) {
      for (const mote of this.motes) {
        const mx = subject.x + (page.x - subject.x) * mote.t;
        const my = subject.y + (page.y - subject.y) * mote.t + Math.sin(mote.t * Math.PI) * mote.drift;
        this.strokeGraphics.circle(mx, my, 2.5 * (1 - mote.t * 0.5));
        this.strokeGraphics.fill({ color: GOLD, alpha: 0.7 * (1 - mote.t) });
      }
    }
  }

  private drawResult() {
    const page = this.pagePos();
    const t = Math.min(1, this.resultElapsed / this.resultDuration);
    this.effectGraphics.clear();

    if (this.result === "success") {
      // Page glow that blooms and settles.
      const glow = Math.sin(Math.min(1, t * 2) * Math.PI);
      this.effectGraphics.roundRect(page.x - PAGE_W / 2 - 8, page.y - PAGE_H / 2 - 8, PAGE_W + 16, PAGE_H + 16, 10);
      this.effectGraphics.stroke({ color: GOLD, width: 5, alpha: glow * 0.9 });
      // World-first: a burst of gold star rays from the page center.
      if (this.resultWorldFirst) {
        const rayLen = 60 + t * 160;
        for (let r = 0; r < 8; r++) {
          const a = (Math.PI * 2 * r) / 8 + 0.4;
          this.effectGraphics.moveTo(page.x + Math.cos(a) * 30, page.y + Math.sin(a) * 30);
          this.effectGraphics.lineTo(page.x + Math.cos(a) * rayLen, page.y + Math.sin(a) * rayLen);
          this.effectGraphics.stroke({ color: GOLD, width: 3, alpha: Math.max(0, 1 - t) });
        }
      }
    } else if (this.result === "fail") {
      // Ink blots splatter across the page.
      const blots = 6;
      const shown = Math.ceil(t * blots);
      for (let b = 0; b < shown; b++) {
        const bx = page.x + (jitter(b * 13 + 5) - 0.5) * PAGE_W * 0.7;
        const by = page.y + (jitter(b * 29 + 11) - 0.5) * PAGE_H * 0.7;
        const r = 12 + jitter(b * 7) * 22;
        this.effectGraphics.circle(bx, by, r);
        this.effectGraphics.fill({ color: INK, alpha: 0.8 });
        this.effectGraphics.circle(bx + r * 0.7, by + r * 0.4, r * 0.35);
        this.effectGraphics.fill({ color: RUIN, alpha: 0.6 });
      }
    }

    if (this.resultText) {
      this.resultText.x = this.width / 2;
      this.resultText.y = this.height * 0.24 - t * 8;
      this.resultText.alpha = Math.min(1, this.resultElapsed / 200);
    }
    if (this.resultSubText) {
      this.resultSubText.x = this.width / 2;
      this.resultSubText.y = this.height * 0.24 + 30;
      this.resultSubText.alpha = Math.min(1, this.resultElapsed / 300);
    }
  }

  private async loadBackground(imagePath: string | null) {
    const token = ++this.bgLoadToken;
    if (this.background) {
      this.container.removeChild(this.background);
      this.background.destroy();
      this.background = null;
    }
    if (!imagePath) return;
    try {
      const texture = await loadTexture(imagePath);
      if (token !== this.bgLoadToken) return;
      const sprite = new Sprite(texture);
      sprite.width = this.width;
      sprite.height = this.height;
      sprite.alpha = 0.5;
      this.container.addChildAt(sprite, 0);
      this.background = sprite;
    } catch {
      // No background
    }
  }

  private async loadPlayerSprite(spritePath: string | null) {
    if (this.playerSprite) {
      this.container.removeChild(this.playerSprite);
      this.playerSprite.destroy();
      this.playerSprite = null;
    }
    const sprite = new Sprite(Texture.WHITE);
    sprite.width = PLAYER_SIZE;
    sprite.height = PLAYER_SIZE;
    sprite.anchor.set(0.5);
    sprite.tint = PLAYER_TINT;
    this.container.addChildAt(sprite, 1);
    this.playerSprite = sprite;
    if (spritePath) {
      try {
        const texture = await loadTexture(spritePath);
        sprite.texture = texture;
        sprite.tint = 0xffffff;
      } catch {
        // Keep placeholder
      }
    }
  }

  private async loadSubjectSprite(imagePath: string | null, tint: string | null) {
    if (this.subjectSprite) {
      this.container.removeChild(this.subjectSprite);
      this.subjectSprite.destroy();
      this.subjectSprite = null;
    }
    const sprite = new Sprite(Texture.WHITE);
    sprite.width = SUBJECT_SIZE;
    sprite.height = SUBJECT_SIZE;
    sprite.anchor.set(0.5);
    const variantTint = parseHexTint(tint);
    if (variantTint != null) {
      sprite.filters = [makeVariantColorize(variantTint)];
      sprite.tint = 0xffffff;
    } else {
      sprite.tint = SUBJECT_TINT;
    }
    this.container.addChildAt(sprite, 1);
    this.subjectSprite = sprite;
    if (imagePath) {
      try {
        const texture = await loadTexture(imagePath);
        sprite.texture = texture;
        if (variantTint == null) sprite.tint = 0xffffff;
      } catch {
        // Keep placeholder
      }
    }
  }

  destroy() {
    this.gainPopups.clear();
    this.container.destroy({ children: true });
  }
}
