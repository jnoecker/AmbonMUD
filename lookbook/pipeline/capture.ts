// AmbonMUD lookbook capture driver.
// Usage: bun capture.ts <script.json>
// Steps: {login}, {cmd}, {shot}, {clip}, {sleep}, {click}, {clickxy}, {press},
//        {fill}, {waitfor}, {js}. See pipeline/README.md.
import { chromium } from "playwright-core";
import { readFileSync, existsSync } from "fs";

// All env-overridable. By default the lookbook captures the LIVE demo at
// mud.ambon.dev, logged in as the staff lookbook character (which carries the
// "animae akathavae illustrator" skin). Point LOOKBOOK_URL at a local
// `./gradlew demo` web port to capture from a local server instead.
const macChrome = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1208/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;
const EXEC = process.env.CHROMIUM_BIN || (existsSync(macChrome) ? macChrome : undefined);
const URL = process.env.LOOKBOOK_URL || "https://mud.ambon.dev";
const NAME = process.env.LOOKBOOK_NAME || "Claude";
const PASS = process.env.LOOKBOOK_PASS || "ClaudeFable5";

const steps: any[] = JSON.parse(readFileSync(process.argv[2], "utf8"));

const browser = await chromium.launch({
  executablePath: EXEC,
  headless: true,
  args: [
    "--enable-unsafe-swiftshader",
    "--disable-features=Vulkan,WebGPU",
    "--use-angle=swiftshader",
    "--mute-audio",
  ],
});
// Bump LOOKBOOK_DSF (device scale factor) when capturing tight element close-ups
// (e.g. the vitals bar or room-name sign) so the clipped crop is hi-res enough
// to print at plate width without upscaling blur. Full-page shots stay at 1.
const DSF = Number(process.env.LOOKBOOK_DSF || "1");
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: DSF });
page.on("crash", () => console.log("!! PAGE CRASHED"));
page.on("pageerror", (e) => console.log("pageerror:", String(e).slice(0, 200)));

// The in-game vitals HUD only renders once a character is in the world, so it
// is the reliable "logged in" signal — unlike .canvas-command-input, which is
// present in the DOM (behind the login art) before login too.
const INGAME = ".canvas-vitals-skin, .canvas-vitals-hud";

async function login() {
  // networkidle never settles against the live demo (persistent WebSocket), so
  // wait on the DOM + an explicit app-boot pause instead.
  await page.goto(URL, { waitUntil: "domcontentloaded", timeout: 45000 });
  await page.waitForTimeout(6000);
  const picker = page.locator(".character-picker-btn").first();
  if (await picker.count()) {
    await picker.click();
  } else {
    const inp = page.locator("input.login-art-input").first();
    await inp.fill(NAME);
    await inp.press("Enter");
  }
  await page.waitForTimeout(3000);
  // Password scene: the painted input is re-used and focused. Fill it if we can
  // see it, otherwise type into the focused field.
  const pwInp = page.locator("input.login-art-input").first();
  if (await pwInp.count()) {
    await pwInp.fill(PASS);
    await pwInp.press("Enter");
  } else {
    await page.keyboard.type(PASS);
    await page.keyboard.press("Enter");
  }
  await page.waitForSelector(INGAME, { timeout: 60000 });
  await page.waitForTimeout(5000);
  console.log("LOGGED IN");
}

async function cmd(c: string) {
  await page.evaluate((text) => {
    const inp = document.querySelector(".canvas-command-input") as HTMLInputElement;
    const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value")!.set!;
    set.call(inp, text);
    inp.dispatchEvent(new Event("input", { bubbles: true }));
    (inp.closest("form") as HTMLFormElement).requestSubmit();
  }, c);
  console.log("cmd:", c);
}

async function clickButton(label: string) {
  const ok = await page.evaluate((l) => {
    const btns = [...document.querySelectorAll("button")];
    const b = btns.find((x) => ((x.getAttribute("aria-label") || x.textContent || "").trim()).startsWith(l));
    if (!b) return false;
    (b as HTMLButtonElement).click();
    return true;
  }, label);
  console.log(ok ? `clicked: ${label}` : `NO BUTTON: ${label}`);
}

// Screenshot a single element's bounding box (with optional padding) for
// close-ups of small painted UI like the vitals bar or the room-name sign.
async function clip(selector: string, path: string, pad = 0) {
  const el = page.locator(selector).first();
  if (!(await el.count())) {
    console.log(`NO CLIP TARGET: ${selector}`);
    return;
  }
  const box = await el.boundingBox();
  if (!box) {
    console.log(`NO BOX: ${selector}`);
    return;
  }
  const x = Math.max(0, box.x - pad);
  const y = Math.max(0, box.y - pad);
  await page.screenshot({
    path,
    clip: { x, y, width: box.width + pad * 2, height: box.height + pad * 2 },
  });
  console.log("clip:", selector, "→", path);
}

for (const s of steps) {
  try {
    if (s.login) await login();
    else if (s.cmd) await cmd(s.cmd);
    else if (s.clip) await clip(s.clip, s.shot, s.pad ?? 0);
    else if (s.shot) {
      await page.screenshot({ path: s.shot });
      console.log("shot:", s.shot);
    } else if (s.sleep) await page.waitForTimeout(s.sleep * 1000);
    else if (s.click) await clickButton(s.click);
    else if (s.clickxy) { await page.mouse.click(s.clickxy[0], s.clickxy[1]); console.log("clickxy:", s.clickxy); }
    else if (s.press) await page.keyboard.press(s.press);
    else if (s.fill) {
      await page.locator(s.fill).first().fill(s.value ?? "");
      console.log("fill:", s.fill);
    } else if (s.waitfor) {
      await page.waitForSelector(s.waitfor, { timeout: (s.timeout ?? 30) * 1000 });
      console.log("waitfor:", s.waitfor);
    } else if (s.js) console.log("js:", await page.evaluate(s.js));
  } catch (e) {
    console.log("STEP FAILED:", JSON.stringify(s).slice(0, 100), "—", String(e).slice(0, 200));
  }
}
await browser.close();
console.log("DONE");
