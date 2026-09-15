// Populate the Social Board (gossip channel) with a live multi-account
// conversation, then screenshot it from the lookbook character's board.
//
// gossip is server-wide and chat has no backfill, so the lookbook character has
// to be connected and watching while the others post. We log in the lookbook
// character (LOOKBOOK_NAME/PASS), open the Social Board on the Gossip tab, then
// spin up a few one-click guest characters in isolated browser contexts (so
// their sessions don't collide) and have everyone gossip in turn.
//
//   bun social.ts            # writes ../screenshots/subsystems/16-chat.jpg
import { chromium } from "playwright-core";
import { existsSync } from "fs";

const macChrome = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1208/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;
const EXEC = process.env.CHROMIUM_BIN || (existsSync(macChrome) ? macChrome : undefined);
const URL = process.env.LOOKBOOK_URL || "https://mud.ambon.dev";
const NAME = process.env.LOOKBOOK_NAME || "Claude";
const PASS = process.env.LOOKBOOK_PASS || "ClaudeFable5";
const OUT = process.argv[2] || "../screenshots/subsystems/16-chat.jpg";
const INGAME = ".canvas-vitals-skin, .canvas-vitals-hud";

const browser = await chromium.launch({
  executablePath: EXEC,
  headless: true,
  args: ["--enable-unsafe-swiftshader", "--disable-features=Vulkan,WebGPU", "--use-angle=swiftshader", "--mute-audio"],
});

const newCtx = async () => browser.newContext({ viewport: { width: 1600, height: 1000 } });
const newPage = async () => (await newCtx()).newPage();
const wait = (p: any, ms: number) => p.waitForTimeout(ms);

async function clickLabel(p: any, label: string) {
  return p.evaluate((l: string) => {
    const b = [...document.querySelectorAll("button")].find(
      (x) => ((x.getAttribute("aria-label") || x.textContent || "").trim()).startsWith(l),
    );
    if (b) { (b as HTMLButtonElement).click(); return true; }
    return false;
  }, label);
}
async function cmd(p: any, c: string) {
  await p.evaluate((text: string) => {
    const inp = document.querySelector(".canvas-command-input") as HTMLInputElement;
    const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value")!.set!;
    set.call(inp, text);
    inp.dispatchEvent(new Event("input", { bubbles: true }));
    (inp.closest("form") as HTMLFormElement).requestSubmit();
  }, c);
}

async function loginOnce(p: any, name: string, pass: string) {
  await p.goto(URL, { waitUntil: "domcontentloaded", timeout: 45000 });
  // The login input arrives from the server over the WebSocket; under concurrent
  // load it can lag, so wait for it explicitly rather than a fixed sleep.
  await p.waitForSelector("input.login-art-input", { timeout: 45000 });
  await wait(p, 1500);
  let inp = p.locator("input.login-art-input").first();
  await inp.fill(name); await inp.press("Enter");
  await wait(p, 3000);
  await p.waitForSelector("input.login-art-input", { timeout: 30000 });
  inp = p.locator("input.login-art-input").first();
  await inp.fill(pass); await inp.press("Enter");
  await p.waitForSelector(INGAME, { timeout: 60000 });
  await wait(p, 4000);
}
async function loginAs(p: any, name: string, pass: string) {
  try {
    await loginOnce(p, name, pass);
  } catch (e) {
    console.log(`  ${name} login retry (${String(e).slice(0, 50)})`);
    await loginOnce(p, name, pass);
  }
}
async function clickDemo(p: any) {
  return p.evaluate(() => {
    const b = [...document.querySelectorAll("button")].find(
      (x) => (x.getAttribute("aria-label") || "").includes("Start demo") || (x.textContent || "").includes("Start Demo"),
    );
    if (b) { (b as HTMLButtonElement).click(); return true; }
    return false;
  });
}
async function loginGuest(p: any, tag: string) {
  await p.goto(URL, { waitUntil: "domcontentloaded", timeout: 45000 });
  await wait(p, 8000);
  // Retry the demo click a few times: under concurrent load the landing art can
  // still be settling, or the first click lands before the handler is wired.
  for (let attempt = 0; attempt < 4; attempt++) {
    const ok = await clickDemo(p);
    console.log(`  ${tag} demo click #${attempt + 1}: ${ok ? "hit" : "no-button"}`);
    try {
      await p.waitForSelector(INGAME, { timeout: 20000 });
      await wait(p, 4000);
      return;
    } catch {
      await wait(p, 2000);
    }
  }
  await p.screenshot({ path: `/tmp/guestfail-${tag}.jpg` });
  throw new Error(`guest ${tag} never reached in-game`);
}

// Guests can't use global chat (gossip requires a claimed character), and the
// live demo only allows one guest per IP at a time — so the "extra accounts"
// were created once, up front, by spawning a one-click guest and `claim`ing a
// real name (the live demo prints "Your character has been saved as <name>").
// createHelper() below is that one-time recipe, kept for reference; the live
// conversation just logs the persistent named accounts back in (no guest cap,
// and reruns are idempotent).
async function createHelper(name: string, password: string) {
  const ctx = await newCtx();
  const g = await ctx.newPage();
  await loginGuest(g, name);
  await cmd(g, `claim ${name} ${password}`);
  await wait(g, 4000);
  await ctx.close();
}
void createHelper;

const HELPERS = [
  { name: "Tindrel", pass: "tindrel-lb-2026" },
  { name: "Marisel", pass: "marisel-lb-2026" },
];

// Log everyone in first (the lookbook character plus the named helpers, each in
// its own context), then open the board — so the helper logins aren't competing
// with the lookbook character's running canvas for the login WebSocket.
const claude = await newPage();
await loginAs(claude, NAME, PASS);
console.log("lookbook character in");

const helpers: any[] = [];
for (const h of HELPERS) {
  try {
    const p = await newPage();
    await loginAs(p, h.name, h.pass);
    helpers.push(p);
    console.log(`${h.name} in`);
  } catch (e) {
    console.log(`helper ${h.name} failed: ${String(e).slice(0, 80)}`);
  }
}

// Now open the lookbook character's Social Board on the Gossip tab.
await clickLabel(claude, "Show services"); await wait(claude, 600);
await clickLabel(claude, "Chat"); await wait(claude, 1500);
await clickLabel(claude, "Gossip"); await wait(claude, 800);

const actors = [claude, ...helpers];
const A = (i: number) => actors[Math.min(i, actors.length - 1)]; // degrade gracefully

// The conversation. [who, text]; who 0 = the illustrator, 1 = Tindrel, 2 = Marisel.
const script: Array<[number, string]> = [
  [0, "Set my easel up in the Academy Quad — dusk light on the clocktower is unreal today. Anyone about?"],
  [1, "ooh that's where everyone's drifting? omw"],
  [2, "is the catacombs group still forming, or did it set off without me?"],
  [1, "still forming — meet at the Chapel of Patient Rest?"],
  [2, "bring potions, the dream wisps hit hard after midnight"],
  [0, "Save me a spot by the entrance and I'll paint the boss room once you clear it. ✦"],
];
for (const [who, text] of script) {
  await cmd(A(who), `gossip ${text}`);
  await wait(claude, 1700);
}
await wait(claude, 1800);

await claude.screenshot({ path: OUT });
const feed = await claude.evaluate(() => document.querySelector("#cb-channel-feed")?.textContent?.slice(0, 500));
console.log("FEED:", feed);
await browser.close();
console.log("DONE →", OUT);
