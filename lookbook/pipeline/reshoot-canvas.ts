// Canvas-dependent reshoots that need real mouse clicks on the Pixi scene:
//   - NPC dialogue with Krioshaeu (krioshaeu_cabin)
//   - Combat vs the boss "A Dream of Maelsang" (tempest_between_worlds)
// Writes /tmp/D-dialogue.jpg, /tmp/D-combat1.jpg, /tmp/D-combat2.jpg
import { chromium } from "playwright-core";
const EXEC = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1208/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;
const URL = "https://mud.ambon.dev";
const INGAME = ".canvas-vitals-skin, .canvas-vitals-hud";
const b = await chromium.launch({ executablePath: EXEC, headless: true, args: ["--enable-unsafe-swiftshader", "--use-angle=swiftshader", "--mute-audio", "--disable-features=Vulkan,WebGPU"] });
const p = await (await b.newContext({ viewport: { width: 1600, height: 1000 } })).newPage();
const cmd = async (c) => p.evaluate((t) => { const i = document.querySelector(".canvas-command-input") as HTMLInputElement; const s = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value")!.set!; s.call(i, t); i.dispatchEvent(new Event("input", { bubbles: true })); (i.closest("form") as HTMLFormElement).requestSubmit(); }, c);
const mmOpen = async () => p.evaluate(() => !!document.querySelector('.mm-card'));
const mmText = async () => p.evaluate(() => (document.querySelector('.mm-card')?.textContent || ''));
const closeMM = async () => p.evaluate(() => { const t = [...document.querySelectorAll('.mm-card button')].find(x => (x.textContent || '').trim() === '✕'); if (t) (t as HTMLButtonElement).click(); });
const clickBtn = async (label) => p.evaluate((l) => { const t = [...document.querySelectorAll('.mm-card button')].find(x => (x.textContent || '').trim().startsWith(l)); if (t) { (t as HTMLButtonElement).click(); return true; } return false; }, label);

await p.goto(URL, { waitUntil: "domcontentloaded", timeout: 45000 }); await p.waitForTimeout(6000);
{ const i = p.locator("input.login-art-input").first(); await i.fill("Claude"); await i.press("Enter"); } await p.waitForTimeout(3000);
{ const i = p.locator("input.login-art-input").first(); await i.fill("ClaudeFable5"); await i.press("Enter"); }
await p.waitForSelector(INGAME, { timeout: 60000 }); await p.waitForTimeout(3000);

const ys = [300, 330, 360, 390, 270, 420, 450];
const xs = [800, 880, 720, 960, 640, 1040, 560, 1120, 1200];

// ---- Dialogue: Krioshaeu ----
await cmd("goto krioshaeu_cabin"); await p.waitForTimeout(3500);
let talked = false;
outerD: for (const y of ys) {
  for (const x of xs) {
    await p.mouse.click(x, y); await p.waitForTimeout(350);
    if (await mmOpen()) {
      const txt = await mmText();
      if (/krio/i.test(txt)) {
        await clickBtn("Talk"); // no-op if it auto-opened the dialog view
        talked = true; console.log("opened Krioshaeu at", x, y); break outerD;
      }
      await closeMM(); await p.waitForTimeout(150);
    }
  }
}
console.log("talked:", talked);
// Let the first dialogue node stream in, then advance one choice to show the
// branching mid-conversation if choices are present.
await p.waitForTimeout(2500);
await p.evaluate(() => { const c = document.querySelector('.mm-dialog-choice') as HTMLButtonElement; if (c) c.click(); });
await p.waitForTimeout(2500);
await p.screenshot({ path: "/tmp/D-dialogue.jpg" });
console.log("dialogue shot. choices:", await p.evaluate(() => document.querySelectorAll('.mm-dialog-choice').length));
await closeMM(); await p.waitForTimeout(500);

// ---- Combat: A Dream of Maelsang ----
await cmd("goto tempest_between_worlds"); await p.waitForTimeout(3500);
let attacked = false;
outerC: for (const y of ys) {
  for (const x of xs) {
    await p.mouse.click(x, y); await p.waitForTimeout(350);
    if (await mmOpen()) {
      if (await clickBtn("Attack")) { attacked = true; console.log("attacked at", x, y, "→", (await mmText()).slice(0, 40)); break outerC; }
      await closeMM(); await p.waitForTimeout(150);
    }
  }
}
console.log("attacked:", attacked);
await p.waitForTimeout(2800);
await p.screenshot({ path: "/tmp/D-combat1.jpg" });
await p.keyboard.press("1"); await p.waitForTimeout(1300); await p.keyboard.press("2"); await p.waitForTimeout(1800);
await p.screenshot({ path: "/tmp/D-combat2.jpg" });
await b.close(); console.log("DONE");
