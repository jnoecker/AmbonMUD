// Drive the new-character creation flow to the race + class selection screens,
// reshoot them, and harvest each option's individual portrait URL (opt.image,
// rendered as .login-art-cell-img). No account is created — we stop after the
// class screen. Writes the two screenshots and /tmp/portraits.json.
import { chromium } from "playwright-core";
import { writeFileSync } from "fs";
const EXEC = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1208/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;
const URL = "https://mud.ambon.dev";
const NAME = "Lookbookportrait";
const b = await chromium.launch({ executablePath: EXEC, headless: true, args: ["--enable-unsafe-swiftshader", "--use-angle=swiftshader", "--mute-audio", "--disable-features=Vulkan,WebGPU"] });
const p = await (await b.newContext({ viewport: { width: 1280, height: 720 } })).newPage();
const has = async (sel) => p.evaluate((s) => !!document.querySelector(s), sel);
const cells = async () => p.evaluate(() => [...document.querySelectorAll(".login-art-cell")].map((c) => ({ name: c.getAttribute("aria-label"), img: (c.querySelector(".login-art-cell-img") as HTMLImageElement)?.src ?? null })));

await p.goto(URL, { waitUntil: "domcontentloaded", timeout: 45000 });
await p.waitForTimeout(6000);
// name entry
{ const i = p.locator("input.login-art-input").first(); await i.fill(NAME); await i.press("Enter"); }

let race = null, klass = null;
for (let step = 0; step < 40; step++) {
  await p.waitForTimeout(1200);
  if (await has(".lcf-yes")) { await p.click(".lcf-yes"); continue; }            // confirmCreate
  if (await has(".lsp-input")) { await p.fill(".lsp-input", "Lookbook5pass"); await p.keyboard.press("Enter"); continue; } // newPassword
  if (await has(".lrc-c1") && !race) {
    await p.waitForTimeout(1500);
    await p.screenshot({ path: "../screenshots/subsystems/04-race-select.jpg" });
    race = await cells();
    console.log("RACE cells:", race.length);
    // select first race and advance to class
    await p.click(".lrc-c1");
    await p.waitForTimeout(400);
    await p.click(".lrc-choose");
    continue;
  }
  if (await has(".lcl-c1") && !klass) {
    await p.waitForTimeout(1500);
    await p.screenshot({ path: "../screenshots/subsystems/05-class-select.jpg" });
    klass = await cells();
    console.log("CLASS cells:", klass.length);
    break;
  }
}
writeFileSync("/tmp/portraits.json", JSON.stringify({ race, klass }, null, 2));
console.log("DONE", race ? race.map((r) => r.name).join(",") : "no-race", "|", klass ? klass.map((k) => k.name).join(",") : "no-class");
await b.close();
