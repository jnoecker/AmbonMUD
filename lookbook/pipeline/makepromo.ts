// AmbonMUD promo — a 2-page (double-sided Letter) print handout.
// The painted front/back art (promo-front.png, promo-back.png) carries the whole
// design; this script only drops a QR code into the empty cream box on each page:
//   front → mud.ambon.dev   ·   back → the web lookbook   → ../promo.pdf
import { chromium } from "playwright-core";
import { writeFileSync, existsSync } from "fs";
import { resolve } from "path";
import QRCode from "qrcode";

const dir = resolve(import.meta.dir, "..");
const PAGE_W = 8.5;
const PAGE_H = 11;

async function qr(data: string, px = 900): Promise<string> {
  return QRCode.toDataURL(data, {
    margin: 1, width: px, errorCorrectionLevel: "H",
    color: { dark: "#241a0a", light: "#0000" }, // dark modules on the bare parchment
  });
}

// Cream-box inner bounds (fraction of page), measured from the painted art.
// The QR is sized to the box's smaller side (with a small inset) and centred.
function place(box: { l: number; r: number; t: number; b: number }, inset = 0.9) {
  const wIn = (box.r - box.l) * PAGE_W;
  const hIn = (box.b - box.t) * PAGE_H;
  const s = Math.min(wIn, hIn) * inset;
  const cx = ((box.l + box.r) / 2) * PAGE_W;
  const cy = ((box.t + box.b) / 2) * PAGE_H;
  return { s, left: cx - s / 2, top: cy - s / 2 };
}

const frontBox = { l: 0.6972, r: 0.8976, t: 0.7307, b: 0.8822 };
const backBox = { l: 0.7121, r: 0.8971, t: 0.7077, b: 0.8894 };
const fp = place(frontBox);
const bp = place(backBox, 0.88);
// Nudge the back QR right + down a touch to sit dead-centre in its painted box.
bp.left += 0.03;
bp.top += 0.07;

const qrPlay = await qr("https://mud.ambon.dev");
const qrBook = await qr("https://ambon.dev/lookbook.html");

const doc = `<!doctype html><html><head><meta charset="utf-8"><style>
  @page { size: letter; margin: 0; }
  html, body { margin: 0; }
  .page { position: relative; width: 8.5in; height: 11in; overflow: hidden; page-break-after: always; }
  .page:last-child { page-break-after: auto; }
  .bg { position: absolute; inset: 0; width: 8.5in; height: 11in; display: block; }
  .qr { position: absolute; }
</style></head><body>
  <div class="page">
    <img class="bg" src="promo-front.png" alt="AmbonMUD — A Call for Heroes">
    <img class="qr" src="${qrPlay}" alt="Scan to play at mud.ambon.dev"
      style="left:${fp.left.toFixed(3)}in; top:${fp.top.toFixed(3)}in; width:${fp.s.toFixed(3)}in; height:${fp.s.toFixed(3)}in;">
  </div>
  <div class="page">
    <img class="bg" src="promo-back.png" alt="AmbonMUD — the world map">
    <img class="qr" src="${qrBook}" alt="Scan for the AmbonMUD lookbook"
      style="left:${bp.left.toFixed(3)}in; top:${bp.top.toFixed(3)}in; width:${bp.s.toFixed(3)}in; height:${bp.s.toFixed(3)}in;">
  </div>
</body></html>`;

writeFileSync(`${dir}/promo.html`, doc);

function chromeBin(): string | undefined {
  if (process.env.CHROMIUM_BIN) return process.env.CHROMIUM_BIN;
  const mac = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1208/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;
  return existsSync(mac) ? mac : undefined;
}
const browser = await chromium.launch({ executablePath: chromeBin(), headless: true });
const pg = await browser.newPage();
await pg.goto(`file://${dir}/promo.html`, { waitUntil: "networkidle", timeout: 120000 });
await pg.pdf({ path: `${dir}/promo.pdf`, format: "letter", margin: { top: "0", bottom: "0", left: "0", right: "0" }, printBackground: true });
await browser.close();
console.log("promo.pdf written");
