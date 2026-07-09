import { ColorMatrixFilter, Container, Graphics, Sprite, Text, Texture } from "pixi.js";
import { gameStateRef } from "../GameStateBridge";
import { canvasEvents } from "../CanvasEventBus";
import { GainPopupSystem } from "../systems/GainPopup";
import { loadTexture } from "../textureLoader";
import { parseHexTint, makeVariantColorize } from "../variantTint";
import type { ArcanumSketchEvent } from "../../types";

const SUBJECT_MAX = 260;
const PLAYER_MAX = 170;

const PLAYER_TINT = 0x81a2be;
const SUBJECT_TINT = 0xf0c674;
const PAGE_COLOR = 0xe9dcc0;
const PAGE_EDGE = 0xb8a67e;
const INK = 0x2e2418;
const GOLD = 0xf0c674;
const RUIN = 0x8a3b34;
const BAR_TRACK = 0x3a3226;
const LABEL_COLOR = "#d8dcef";

/** Deterministic-enough pseudo-random for blot/hatch layout (no gameplay meaning). */
function jitter(seed: number): number {
  const x = Math.sin(seed * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}

/** Sepia ink-wash treatment for the on-page drawing of the subject's own art. */
function makeInkWash(): ColorMatrixFilter {
  const filter = new ColorMatrixFilter();
  filter.desaturate();
  filter.sepia(true);
  filter.brightness(0.85, true);
  return filter;
}

interface InkMote {
  t: number; // 0..1 along its flight
  speed: number;
  drift: number;
}

type ResultKind = "success" | "fail" | "cancel" | null;

/**
 * The sketching sequence — the pacifist twin of BattleScene. While an
 * Arcanum sketch is in flight (`state.activeSketch`), the view becomes a
 * quiet study: the subject poses on the right, the player stands at their
 * journal on the left, and the subject's own portrait art materializes on
 * the parchment page as an ink-wash drawing, revealed top-to-bottom behind
 * a mask wipe as the sketch progresses (a quill rides the wipe edge, ink
 * motes drift off the subject into the book, and a slim progress bar under
 * the page tracks the server-declared duration). The outcome event then
 * plays a stamp flourish ("RECORDED", plus a star burst for world-firsts),
 * an ink-blot ruin, or a quick fade for a cancel. A failure that angers the
 * subject hands off to the battle scene, which takes scene priority the
 * moment combat starts.
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

  /** The subject's art re-rendered on the page as an ink drawing. */
  private revealSprite: Sprite | null = null;
  private revealMask = new Graphics();
  private revealLoadToken = 0;
  private revealLoaded = false;

  private uiGraphics = new Graphics();
  private pageGraphics = new Graphics();
  private strokeGraphics = new Graphics();
  private effectGraphics = new Graphics();
  private subjectLabel: Text;
  private hintLabel: Text;
  private resultText: Text | null = null;
  private resultSubText: Text | null = null;
  private gainPopups = new GainPopupSystem();

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
    // The reveal sprite is inserted above pageGraphics when it loads; its
    // mask must live in the display list for Pixi to apply it (Pixi skips a
    // Graphics used as a mask during normal rendering — do NOT set
    // visible=false, which disables the mask and hides the sprite).
    this.container.addChild(this.revealMask);
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
    this.removeResultText();
    this.gainPopups.clear();
    // Stale drawings from the previous sketch must not haunt the new page —
    // the effect layer in particular holds fail-state ink blots.
    this.effectGraphics.clear();
    this.strokeGraphics.clear();
    this.removeRevealSprite();
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
    if (sketch && sketch.mobId !== this.lastSubjectKey) {
      this.lastSubjectKey = sketch.mobId;
      const mob = state.mobs.find((m) => m.id === sketch.mobId);
      const image = mob?.image ?? state.serverAssets[`default_mob_${mob?.category ?? "humanoid"}`] ?? null;
      this.loadSubjectSprite(image, mob?.tint ?? null);
      this.loadRevealSprite(image);
      this.subjectLabel.text = sketch.mobName;
    }

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

  private updateMotes(deltaMs: number, progress: number) {
    // Spawn a gentle stream of ink motes while actively sketching.
    if (progress > 0 && progress < 1) {
      this.moteSpawnAccum += deltaMs;
      while (this.moteSpawnAccum > 120) {
        this.moteSpawnAccum -= 120;
        this.motes.push({ t: 0, speed: 0.55 + jitter(this.elapsedTotal) * 0.4, drift: (jitter(this.elapsedTotal + 7) - 0.5) * 70 });
      }
    }
    for (const mote of this.motes) mote.t += (deltaMs / 1600) * mote.speed;
    this.motes = this.motes.filter((m) => m.t < 1);
  }

  /** The game canvas can be a short letterbox strip — everything derives from height. */
  private subjectSize() {
    return Math.min(SUBJECT_MAX, this.height * 0.62);
  }

  private playerSize() {
    return Math.min(PLAYER_MAX, this.height * 0.42);
  }

  private subjectPos() {
    return { x: this.width * 0.72, y: this.height * 0.42 };
  }

  private pageRect() {
    const h = Math.max(100, Math.min(340, this.height * 0.58));
    const w = h * (5 / 6);
    return { x: this.width * 0.36, y: this.height * 0.5, w, h };
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
    const page = this.pageRect();

    // Subject sways almost imperceptibly — a patient model holding a pose.
    const subjectSize = this.subjectSize();
    const sway = Math.sin(this.elapsedTotal / 900) * 4;
    if (this.subjectSprite) {
      this.subjectSprite.x = subject.x + sway;
      this.subjectSprite.y = subject.y;
      this.subjectSprite.width = subjectSize;
      this.subjectSprite.height = subjectSize;
    }
    this.subjectLabel.x = subject.x;
    this.subjectLabel.y = Math.min(subject.y + subjectSize / 2 + 10, h - 24);

    // Player at their journal, lower-left, with a slight working bob.
    const playerSize = this.playerSize();
    const bob = Math.sin(this.elapsedTotal / 350) * 2;
    if (this.playerSprite) {
      this.playerSprite.x = page.x - page.w / 2 - playerSize * 0.55;
      this.playerSprite.y = page.y + page.h / 2 - playerSize / 2 + bob;
      this.playerSprite.width = playerSize;
      this.playerSprite.height = playerSize;
    }

    // The journal page.
    this.pageGraphics.clear();
    this.pageGraphics.roundRect(page.x - page.w / 2, page.y - page.h / 2, page.w, page.h, 8);
    this.pageGraphics.fill({ color: PAGE_COLOR, alpha: 0.96 });
    this.pageGraphics.roundRect(page.x - page.w / 2, page.y - page.h / 2, page.w, page.h, 8);
    this.pageGraphics.stroke({ color: PAGE_EDGE, width: 2, alpha: 0.9 });
    // Spine shadow on the left edge.
    this.pageGraphics.rect(page.x - page.w / 2, page.y - page.h / 2, 10, page.h);
    this.pageGraphics.fill({ color: PAGE_EDGE, alpha: 0.35 });

    // The drawing: the subject's own art in ink wash, revealed top-to-bottom.
    const pageTop = page.y - page.h / 2;
    const wipeY = pageTop + page.h * progress;
    if (this.revealSprite) {
      const inset = Math.min(page.w, page.h) * 0.82;
      this.revealSprite.x = page.x + 4; // nudge clear of the spine shadow
      this.revealSprite.y = page.y;
      this.revealSprite.width = inset;
      this.revealSprite.height = inset;
      this.revealSprite.alpha = 0.35 + 0.55 * progress;
      this.revealMask.clear();
      this.revealMask.rect(page.x - page.w / 2, pageTop, page.w, page.h * progress);
      this.revealMask.fill(0xffffff);
    }

    // Quill nib riding the wipe edge, wandering left-right like a working hand.
    this.strokeGraphics.clear();
    if (this.result === null && this.revealLoaded && progress > 0 && progress < 1) {
      const qx = page.x + Math.sin(this.elapsedTotal / 260) * page.w * 0.32;
      const qy = Math.min(wipeY, page.y + page.h / 2 - 8);
      this.strokeGraphics.poly([qx, qy, qx + 7, qy - 16, qx + 13, qy - 24, qx + 9, qy - 12]);
      this.strokeGraphics.fill({ color: 0xd8dcef, alpha: 0.95 });
      // A faint working line at the wipe edge, as if the row is being inked.
      this.strokeGraphics.moveTo(page.x - page.w / 2 + 14, qy);
      this.strokeGraphics.lineTo(page.x + page.w / 2 - 14, qy);
      this.strokeGraphics.stroke({ color: INK, width: 1, alpha: 0.25 });
    }

    // Fallback while the subject art hasn't loaded: journal ruling being
    // written line by line, so the page never sits empty.
    if (this.result === null && !this.revealLoaded && progress > 0) {
      const lines = 7;
      const shown = progress * lines;
      for (let i = 0; i < lines; i++) {
        const frac = Math.max(0, Math.min(1, shown - i));
        if (frac <= 0) break;
        const ly = pageTop + page.h * 0.16 + i * page.h * 0.11;
        const lw = (page.w - 48) * frac * (0.75 + jitter(i * 17) * 0.25);
        this.strokeGraphics.moveTo(page.x - page.w / 2 + 24, ly);
        this.strokeGraphics.lineTo(page.x - page.w / 2 + 24 + lw, ly);
        this.strokeGraphics.stroke({ color: INK, width: 2, alpha: 0.55 });
      }
    }

    // Ink motes drifting subject → the wipe edge of the drawing.
    if (this.result === null) {
      for (const mote of this.motes) {
        const mx = subject.x + (page.x - subject.x) * mote.t;
        const my = subject.y + (wipeY - subject.y) * mote.t + Math.sin(mote.t * Math.PI) * mote.drift;
        this.strokeGraphics.circle(mx, my, 2.5 * (1 - mote.t * 0.5));
        this.strokeGraphics.fill({ color: GOLD, alpha: 0.7 * (1 - mote.t) });
      }
    }

    // Slim progress bar under the page (replaces the old oversized ring).
    const barW = page.w;
    const barY = Math.min(page.y + page.h / 2 + 10, h - 30);
    if (this.result === null) {
      this.pageGraphics.roundRect(page.x - barW / 2, barY, barW, 7, 3);
      this.pageGraphics.fill({ color: BAR_TRACK, alpha: 0.9 });
      if (progress > 0) {
        this.pageGraphics.roundRect(page.x - barW / 2, barY, Math.max(7, barW * progress), 7, 3);
        this.pageGraphics.fill({ color: GOLD, alpha: 0.95 });
      }
    }
    this.hintLabel.text = this.result !== null ? "" : "Sketching… (moving or fighting abandons the page)";
    this.hintLabel.x = page.x;
    this.hintLabel.y = Math.min(barY + 12, h - 18);
  }

  private drawResult() {
    const page = this.pageRect();
    const t = Math.min(1, this.resultElapsed / this.resultDuration);
    this.effectGraphics.clear();

    if (this.result === "success") {
      // Page glow that blooms and settles.
      const glow = Math.sin(Math.min(1, t * 2) * Math.PI);
      this.effectGraphics.roundRect(page.x - page.w / 2 - 8, page.y - page.h / 2 - 8, page.w + 16, page.h + 16, 10);
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
        const bx = page.x + (jitter(b * 13 + 5) - 0.5) * page.w * 0.7;
        const by = page.y + (jitter(b * 29 + 11) - 0.5) * page.h * 0.7;
        const r = 12 + jitter(b * 7) * 22;
        this.effectGraphics.circle(bx, by, r);
        this.effectGraphics.fill({ color: INK, alpha: 0.8 });
        this.effectGraphics.circle(bx + r * 0.7, by + r * 0.4, r * 0.35);
        this.effectGraphics.fill({ color: RUIN, alpha: 0.6 });
      }
    }

    if (this.resultText) {
      this.resultText.x = this.width / 2;
      this.resultText.y = this.height * 0.42 - t * 8;
      this.resultText.alpha = Math.min(1, this.resultElapsed / 200);
    }
    if (this.resultSubText) {
      this.resultSubText.x = this.width / 2;
      this.resultSubText.y = this.height * 0.42 + 30;
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
    sprite.width = PLAYER_MAX;
    sprite.height = PLAYER_MAX;
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
    sprite.width = SUBJECT_MAX;
    sprite.height = SUBJECT_MAX;
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

  /** Loads the subject's art again as the on-page ink drawing (masked reveal). */
  private async loadRevealSprite(imagePath: string | null) {
    const token = ++this.revealLoadToken;
    this.removeRevealSprite();
    if (!imagePath) return;
    try {
      const texture = await loadTexture(imagePath);
      if (token !== this.revealLoadToken) return;
      const sprite = new Sprite(texture);
      sprite.anchor.set(0.5);
      sprite.filters = [makeInkWash()];
      sprite.mask = this.revealMask;
      // Above the page fill, below the quill/effect layers.
      this.container.addChildAt(sprite, this.container.getChildIndex(this.revealMask));
      this.revealSprite = sprite;
      this.revealLoaded = true;
    } catch {
      this.revealLoaded = false; // fall back to the written-lines animation
    }
  }

  private removeRevealSprite() {
    if (this.revealSprite) {
      this.container.removeChild(this.revealSprite);
      this.revealSprite.destroy();
      this.revealSprite = null;
    }
    this.revealLoaded = false;
    this.revealMask.clear();
  }

  destroy() {
    this.gainPopups.clear();
    this.container.destroy({ children: true });
  }
}
