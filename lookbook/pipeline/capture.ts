// AmbonMUD lookbook capture driver.
// Usage: bun capture.ts <script.json>
// Script: array of steps: {goto}, {login}, {cmd}, {shot}, {sleep}, {click}, {panel}, {js}
import { chromium } from "playwright-core";
import { readFileSync, existsSync } from "fs";

// All env-overridable. URL is the local `./gradlew demo` web port (demo prints
// the actual port in its startup banner — it varies per worktree). NAME/PASS are
// the throwaway staff character the capture logs in as on that local demo.
const macChrome = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1208/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;
const EXEC = process.env.CHROMIUM_BIN || (existsSync(macChrome) ? macChrome : undefined);
const URL = process.env.LOOKBOOK_URL || "http://localhost:18543";
const NAME = process.env.LOOKBOOK_NAME || "Loremaster";
const PASS = process.env.LOOKBOOK_PASS || "lookbook-pass-1";

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
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
page.on("crash", () => console.log("!! PAGE CRASHED"));
page.on("pageerror", (e) => console.log("pageerror:", String(e).slice(0, 200)));

async function login() {
  await page.goto(URL, { waitUntil: "networkidle" });
  await page.waitForTimeout(3000);
  const picker = page.locator(".character-picker-btn").first();
  if (await picker.count()) {
    await picker.click();
  } else {
    const inp = page.locator("input.login-art-input").first();
    await inp.fill(NAME);
    await inp.press("Enter");
  }
  await page.waitForTimeout(2500);
  // password scene: type into the focused painted input
  await page.keyboard.type(PASS);
  await page.keyboard.press("Enter");
  // wait for the in-game command form
  await page.waitForSelector(".canvas-command-input", { timeout: 60000 });
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

for (const s of steps) {
  try {
    if (s.login) await login();
    else if (s.cmd) await cmd(s.cmd);
    else if (s.shot) {
      await page.screenshot({ path: s.shot });
      console.log("shot:", s.shot);
    } else if (s.sleep) await page.waitForTimeout(s.sleep * 1000);
    else if (s.click) await clickButton(s.click);
    else if (s.press) await page.keyboard.press(s.press);
    else if (s.clickxy) { await page.mouse.click(s.clickxy[0], s.clickxy[1]); console.log("clickxy:", s.clickxy); }
    else if (s.js) console.log("js:", await page.evaluate(s.js));
  } catch (e) {
    console.log("STEP FAILED:", JSON.stringify(s).slice(0, 100), "—", String(e).slice(0, 200));
  }
}
await browser.close();
console.log("DONE");
