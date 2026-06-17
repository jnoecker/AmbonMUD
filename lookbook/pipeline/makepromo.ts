// AmbonMUD promo — a 2-page (double-sided Letter) print handout.
// Page 1 reuses the painted cover (keeping the "AmbonMUD" wordmark) with a
// play-the-demo CTA + QR over the centre plaque. Page 2 is a parchment montage
// of the best captures, a few bullets, and secondary QRs. → ../promo.pdf
import { chromium } from "playwright-core";
import { writeFileSync, existsSync } from "fs";
import { resolve } from "path";
import QRCode from "qrcode";

const dir = resolve(import.meta.dir, "..");

async function qr(data: string, px = 620): Promise<string> {
  return QRCode.toDataURL(data, {
    margin: 1, width: px, errorCorrectionLevel: "M",
    color: { dark: "#1c1407", light: "#f5eed8" },
  });
}
const qrPlay = await qr("https://mud.ambon.dev", 700);
const qrBook = await qr("https://ambon.dev/lookbook.html", 460);
const qrSrc = await qr("https://github.com/jnoecker/AmbonMUD", 460);

const tiles = [
  ["screenshots/academy/academy_quad.jpg", "A painted room"],
  ["screenshots/subsystems/44-dialogue.jpg", "Branching NPC dialogue"],
  ["screenshots/subsystems/16-chat.jpg", "Server-wide chat"],
  ["screenshots/subsystems/47-combat-1.jpg", "Real-time combat"],
  ["screenshots/academy/song_parlor.jpg", "Music, games & more"],
  ["screenshots/ambon-map.jpg", "A continent to explore"],
];
const tileHtml = tiles.map(([src, cap]) =>
  `<figure class="tile"><img src="${src}" alt=""><figcaption>${cap}</figcaption></figure>`).join("");

const bullets = [
  "Nine races &amp; six classes, in a painted creation flow",
  "A 128-room Academy to learn the ropes",
  "Quests, crafting, housing, guilds, pets &amp; PvP",
  "A pacifist &ldquo;illumination&rdquo; path for explorers",
  "Day/night, seasons, weather &amp; live world events",
  "Plays in the browser — or over plain telnet",
].map((b) => `<li>${b}</li>`).join("");

const doc = `<!doctype html><html><head><meta charset="utf-8"><style>
  @page { size: letter; margin: 0; }
  html, body { margin: 0; }
  body { font-family: "Iowan Old Style", Palatino, Georgia, serif; color: #2a1c08; font-weight: 600; }
  .page { position: relative; width: 8.5in; height: 11in; overflow: hidden; page-break-after: always; }
  .page:last-child { page-break-after: auto; }

  /* ---- Page 1: cover + play CTA ---- */
  .cover { width: 8.5in; height: 11in; display: block; }
  .cta {
    position: absolute; left: 1.5in; right: 1.5in; top: 4.55in; bottom: 1.5in;
    background: linear-gradient(180deg, #efe4c4, #e7d8b1);
    border: 1px solid rgb(120 96 50 / 55%); border-radius: 10px;
    box-shadow: 0 4px 16px rgb(40 26 8 / 30%), inset 0 0 0 1px rgb(255 250 230 / 50%);
    display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8pt;
    padding: 16pt 14pt; text-align: center;
  }
  .cta .lead { font-style: italic; font-size: 12.5pt; color: #4a3514; margin: 0; max-width: 22em; }
  .cta .qr { width: 1.85in; height: 1.85in; border-radius: 6px; border: 1px solid rgb(120 96 50 / 40%); }
  .cta .play { font-size: 15pt; font-weight: 800; color: #2a1c08; margin: 0; letter-spacing: 0.01em; }
  .cta .url { font-family: "JetBrains Mono", "Cascadia Mono", monospace; font-size: 12.5pt; font-weight: 700; color: #7a5c16; margin: 0; }
  .cta .fine { font-size: 9.5pt; font-weight: 700; color: #5a4520; margin: 0; }

  /* ---- Page 2: parchment montage ---- */
  .p2 { background-image: url("page_bg.jpg"); background-size: 8.5in 11in; }
  .p2 .inner { padding: 0.85in 0.9in 0.7in; height: 100%; display: flex; flex-direction: column; }
  .p2 h2 { font-size: 27pt; font-weight: 800; color: #2a1c08; margin: 0 0 3pt; letter-spacing: -0.01em; }
  .p2 .sub { font-size: 11.5pt; font-weight: 700; color: #3a2a12; margin: 0 0 14pt; line-height: 1.45; }
  .grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8pt; margin-bottom: 14pt; }
  .tile { margin: 0; }
  .tile img { width: 100%; height: 1.34in; object-fit: cover; border-radius: 4px; border: 1px solid rgb(120 96 50 / 55%); display: block; }
  .tile figcaption { font-size: 8pt; font-weight: 700; color: #4a3514; text-align: center; margin-top: 3pt; }
  .cols { display: grid; grid-template-columns: 1.25fr 0.95fr; gap: 22pt; align-items: start; }
  .feats { margin: 0; padding-left: 1.05em; columns: 1; }
  .feats li { font-size: 10.5pt; font-weight: 700; color: #2f2110; line-height: 1.5; margin-bottom: 3pt; }
  .feats li::marker { color: #b8860b; }
  .links { display: grid; gap: 12pt; }
  .linkrow { display: flex; align-items: center; gap: 11pt; }
  .linkrow img { width: 0.95in; height: 0.95in; border-radius: 5px; border: 1px solid rgb(120 96 50 / 45%); }
  .linkrow .lk { font-size: 10.5pt; font-weight: 800; color: #2a1c08; margin: 0; }
  .linkrow .lu { font-family: "JetBrains Mono", monospace; font-size: 8.5pt; font-weight: 700; color: #7a5c16; margin: 2pt 0 0; }
  .credit { margin-top: auto; padding-top: 12pt; border-top: 1.5px solid rgb(120 96 50 / 40%); font-size: 9.5pt; font-weight: 700; color: #4a3514; text-align: center; }
  .credit strong { color: #2a1c08; }
</style></head><body>

  <div class="page">
    <img class="cover" src="cover.jpg" alt="AmbonMUD">
    <div class="cta">
      <p class="lead">A cozy, hand-painted multiplayer world to get lost in.</p>
      <img class="qr" src="${qrPlay}" alt="QR code to play at mud.ambon.dev">
      <p class="play">Scan to play — free</p>
      <p class="url">mud.ambon.dev</p>
      <p class="fine">In your browser · no install · claim a guest with <b>/claim</b></p>
    </div>
  </div>

  <div class="page p2">
    <div class="inner">
      <h2>Step into the world</h2>
      <p class="sub">A multiplayer fantasy world in the classic MUD tradition, rebuilt for the browser — every room, character, and panel painted by hand, while the same world stays fully playable over plain telnet.</p>
      <div class="grid">${tileHtml}</div>
      <div class="cols">
        <ul class="feats">${bullets}</ul>
        <div class="links">
          <div class="linkrow"><img src="${qrBook}" alt="QR to the lookbook"><div><p class="lk">The full painted lookbook</p><p class="lu">ambon.dev/lookbook</p></div></div>
          <div class="linkrow"><img src="${qrSrc}" alt="QR to the source"><div><p class="lk">Open source &amp; built solo</p><p class="lu">github.com/jnoecker/AmbonMUD</p></div></div>
        </div>
      </div>
      <p class="credit">Built solo by <strong>John Noecker Jr.</strong> — a 50K-line Kotlin game server + React/PixiJS client · ambon.dev</p>
    </div>
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
