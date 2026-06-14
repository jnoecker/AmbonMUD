// AmbonMUD lookbook PDF — explicit page units, safe vertical centering,
// fairy-border background, cardstock-friendly covers.
import { chromium } from "playwright-core";
import { marked } from "marked";
import { readFileSync, writeFileSync, existsSync } from "fs";
import { resolve } from "path";

// Data lives one level up from this script (lookbook/), which holds lookbook.md,
// codex.json, the screenshots/ tree, and the cover/page-background art.
const dir = resolve(import.meta.dir, "..");
const md = readFileSync(`${dir}/lookbook.md`, "utf8");
let html = marked.parse(md) as string;

// ---- Codex (classes / races / characters): rendered directly, NOT via marked
// (marked strips the nested closing </div> tags). Each "page" of items becomes
// one printed page; the section header + lead ride on the first page. ----
const codex = JSON.parse(readFileSync(`${dir}/codex.json`, "utf8"));
function codexEntry(item: any[]): string {
  // ['entry', img, name, meta, desc]  (meta optional → 4 elements)
  const [, img, name, a, b] = item;
  const meta = b === undefined ? "" : a;
  const desc = b === undefined ? a : b;
  const metaHtml = meta ? `<div class="codex-meta">${meta}</div>` : "";
  return `<div class="codex-entry"><img class="codex-portrait" src="screenshots/portraits/${img}">`
    + `<div class="codex-text"><div class="codex-name">${name}</div>${metaHtml}`
    + `<div class="codex-desc">${desc}</div></div></div>`;
}
function codexItem(item: any[]): string {
  if (item[0] === "origin") {
    return `<h3 class="codex-origin">${item[1]}</h3><p class="codex-origin-note">${item[2]}</p>`;
  }
  return codexEntry(item);
}
function codexPages(sec: { title: string; lead: string; pages: any[][] }): string[] {
  return sec.pages.map((items, i) => {
    const head = i === 0 ? `<h2>${sec.title}</h2><p class="codex-lead">${sec.lead}</p>` : "";
    return `<div class="page codexpage"><div class="inner">${head}${items.map(codexItem).join("")}</div></div>`;
  });
}

// Wrap each h3 + image (+ italic caption) into a plate unit.
html = html.replace(
  /<h3>([^<]*)<\/h3>\s*<p><img([^>]*?)>\s*(?:<em>([\s\S]*?)<\/em>)?\s*<\/p>/g,
  (_m, h, imgAttrs, cap) =>
    `<section class="plate"><h3>${h}</h3><img${imgAttrs}>` +
    (cap ? `<p class="cap">${cap}</p>` : "") + `</section>`,
);
html = html.replace(/<\/?div>/g, "");

type Block = { kind: "plate" | "figure" | "text"; html: string };
const parts = html.split(/(?=<h2>)/);
const intro = parts[0];
const sections = parts.slice(1).map((s) => {
  const title = s.match(/<h2>([\s\S]*?)<\/h2>/)![1];
  const rest = s.replace(/<h2>[\s\S]*?<\/h2>/, "");
  const codex = /<!--CODEX-->|class="codex-group"/.test(rest);
  const blocks: Block[] = [];
  const re = /<section class="plate">[\s\S]*?<\/section>|<figure[\s\S]*?<\/figure>|<p>[\s\S]*?<\/p>/g;
  for (const m of rest.match(re) ?? []) {
    blocks.push({
      kind: m.startsWith("<section") ? "plate" : m.startsWith("<figure") ? "figure" : "text",
      html: m,
    });
  }
  return { title, blocks, rest, codex };
});

const pages: string[] = [];
const page = (cls: string, inner: string) =>
  pages.push(`<div class="page ${cls}"><div class="inner">${inner}</div></div>`);

pages.push(`<div class="page coverpage"><img src="cover.jpg"></div>`);
page("blankpage", ""); // page 2: blank back of the cover cardstock

for (const sec of sections) {
  const plates = sec.blocks.filter((b) => b.kind === "plate");
  const figures = sec.blocks.filter((b) => b.kind === "figure");
  const texts = sec.blocks.filter((b) => b.kind === "text");
  const h2 = `<h2>${sec.title}</h2>`;

  // Insert the codex (classes → races → figures) just before the login/creation
  // section, so the world primer sits up front with the gameplay intro.
  if (sec.title === "Login &amp; Character Creation" || sec.title === "Login & Character Creation") {
    for (const key of ["classes", "races", "creatures", "chars"]) {
      for (const p of codexPages(codex[key])) pages.push(p);
    }
  }

  if (!plates.length && !figures.length) {
    // text section: balance across the fewest pages that fit the budget
    const lead = sec === sections[0] ? intro : "";
    const budget = 2350;
    const total = lead.length + texts.reduce((n, t) => n + t.html.length, 0);
    const nPages = Math.max(1, Math.ceil(total / budget));
    const per = total / nPages;
    let cur: string[] = [];
    let len = lead.length;
    let emitted = 0;
    for (const t of texts) {
      if (len + t.html.length / 2 > per && cur.length && emitted < nPages - 1) {
        page("textpage", (emitted === 0 ? lead + h2 : "") + cur.join(""));
        emitted++; cur = []; len = 0;
      }
      cur.push(t.html); len += t.html.length;
    }
    if (cur.length) page("textpage", (emitted === 0 ? lead + h2 : "") + cur.join(""));
  } else if (figures.length) {
    // academy grid: 2-up figures, opener gets header + 6, then 8 per page
    const txt = texts.map((t) => t.html).join("");
    page("gridpage", h2 + txt + figures.slice(0, 6).map((f) => f.html).join(""));
    for (let i = 6; i < figures.length; i += 8) {
      page("gridpage", figures.slice(i, i + 8).map((f) => f.html).join(""));
    }
  } else {
    // plate section: opener = header + intro + 1 plate; then 2 per page
    const txt = texts.map((t) => t.html).join("");
    page("platepage", h2 + txt + plates[0].html);
    for (let i = 1; i < plates.length; i += 2) {
      page("platepage", plates.slice(i, i + 2).map((p) => p.html).join(""));
    }
  }
}

// closing cardstock sheet: blank odd page, back cover on the even page
page("blankpage", "");
if ((pages.length + 1) % 2 !== 0) page("blankpage", "");
pages.push(`<div class="page coverpage"><img src="back_cover.jpg"></div>`);
console.log(`pages: ${pages.length} (back cover on page ${pages.length})`);

const doc = `<!doctype html><html><head><meta charset="utf-8"><style>
  @page { size: letter; margin: 0; }
  html, body { margin: 0; }
  body { font-family: Iowan Old Style, Palatino, Georgia, serif; color: #160f04; font-weight: 600; }
  .page {
    width: 8.5in; height: 11in; box-sizing: border-box;
    padding: 1.05in 1.1in;
    display: flex; flex-direction: column;
    page-break-after: always; overflow: hidden;
    background-image: url("page_bg.jpg"); background-size: 8.5in 11in;
  }
  /* Safe centering: auto margins center when content fits and degrade to
     top-aligned (clipping only the bottom) instead of cropping both ends. */
  .inner { margin-top: auto; margin-bottom: auto; min-height: 0; }
  .coverpage { padding: 0; display: block; }
  .coverpage img { width: 8.5in; height: 11in; display: block; }
  h1 { font-size: 25pt; color: #2a1c08; font-weight: 800; margin: 0 0 8pt; }
  h2 { font-size: 20pt; color: #2a1c08; font-weight: 800; margin: 0 0 10pt;
       border-bottom: 2px solid #8a7045; padding-bottom: 4pt; }
  .textpage p { font-size: 12.5pt; line-height: 1.5; font-weight: 700; color: #120c03; }
  .textpage strong { color: #000000; font-weight: 800; }
  .platepage p { font-size: 12pt; line-height: 1.5; font-weight: 700; color: #120c03; }
  .plate { text-align: center; margin: 0.12in 0; }
  .plate h3 { font-size: 13.5pt; color: #2a1c08; font-weight: 800; margin: 0 0 5pt; }
  .plate img { width: 5.6in; border-radius: 3px; border: 1px solid #8a7045; }
  .plate .cap { font-size: 10pt; font-style: italic; font-weight: 700; color: #3a2710; margin: 7pt 0 0 !important; }
  .gridpage .inner { text-align: center; width: 100%; }
  .gridpage h2, .gridpage p { text-align: left; }
  .gridpage p { font-size: 12pt; font-weight: 700; color: #120c03; }
  .gridpage figure { width: 46% !important; margin: 0.06in 1% !important; }
  .gridpage figure img { border-radius: 2px; border: 1px solid #8a7045; }
  .gridpage figcaption { font-size: 9.5pt !important; font-weight: 700 !important; color: #2a1c08 !important; margin-top: 3pt; }
  .codexpage { padding: 1.05in 1.25in 1in 1.3in; }
  .codexpage .inner { width: 100%; }
  .codex-lead { font-size: 11.5pt; font-weight: 700; color: #120c03; margin: 0 0 0.14in; }
  .codex-entry { display: flex; gap: 0.2in; align-items: center; margin: 0 0 0.12in;
    page-break-inside: avoid; }
  .codex-portrait { width: 1.1in; height: 1.1in; object-fit: cover; object-position: center 18%;
    flex-shrink: 0; border: 1px solid #8a7045; border-radius: 4px; }
  .codex-text { flex: 1; }
  .codex-name { font-size: 14pt; font-weight: 800; color: #2a1c08; margin-bottom: 1pt; }
  .codex-meta { font-size: 9.5pt; font-style: italic; font-weight: 700; color: #5a4628; margin-bottom: 2pt; }
  .codex-desc { font-size: 11pt; font-weight: 700; line-height: 1.4; color: #120c03; }
  .codex-origin { font-size: 15pt; font-weight: 800; color: #2a1c08; margin: 0.06in 0 0;
    border-bottom: 1.5px solid #a8916a; padding-bottom: 2pt; }
  .codex-origin-note { font-size: 9.5pt !important; font-style: italic; font-weight: 700 !important;
    color: #5a4628 !important; margin: 2pt 0 0.1in !important; }
</style></head><body>${pages.join("\n")}</body></html>`;
writeFileSync(`${dir}/lookbook.html`, doc);

// Chromium for the headless print. Override with CHROMIUM_BIN; otherwise try the
// macOS Playwright cache, then fall back to whatever playwright-core resolves.
function chromeBin(): string | undefined {
  if (process.env.CHROMIUM_BIN) return process.env.CHROMIUM_BIN;
  const mac = `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1208/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`;
  return existsSync(mac) ? mac : undefined;
}
const browser = await chromium.launch({ executablePath: chromeBin(), headless: true });
const pg = await browser.newPage();
await pg.goto(`file://${dir}/lookbook.html`, { waitUntil: "networkidle", timeout: 120000 });
await pg.pdf({ path: `${dir}/lookbook.pdf`, format: "letter",
  margin: { top: "0", bottom: "0", left: "0", right: "0" }, printBackground: true });
await browser.close();
console.log("PDF written");
