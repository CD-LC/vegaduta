// Renders a lesson's title/end cards as PNGs via a headless browser page -
// real fonts and brand colors, no ImageMagick/drawtext needed.
//   cd tests/e2e && node video/cards.mjs <lessonDir>
import { chromium } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { join, resolve } from "node:path";

const lessonDir = resolve(process.argv[2] || "");
const script = (await import(join(lessonDir, "script.mjs"))).default;
const out = join(lessonDir, "dist", "cards");
mkdirSync(out, { recursive: true });

const page1 = (title, sub) => `<!doctype html><body style="margin:0;width:1280px;height:720px;
  background:#0b0e14;display:flex;flex-direction:column;align-items:center;justify-content:center;
  font-family:-apple-system,Helvetica,Arial,sans-serif">
  <div style="width:64px;height:4px;background:#3fe6cf;border-radius:2px;margin-bottom:36px"></div>
  <div style="color:#3fe6cf;font-size:15px;letter-spacing:4px;text-transform:uppercase;margin-bottom:18px">VegaDūta Academy</div>
  <div style="color:#eef1f8;font-size:46px;font-weight:700;max-width:1000px;text-align:center;line-height:1.25">${title}</div>
  ${sub ? `<div style="color:#a4a9b8;font-size:22px;margin-top:22px">${sub}</div>` : ""}
</body>`;

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
await page.setContent(page1(script.title, script.subtitle || ""));
await page.screenshot({ path: join(out, "title.png") });
await page.setContent(page1("End of lesson", "Next: " + (script.next || "the next episode")));
await page.screenshot({ path: join(out, "end.png") });
await browser.close();
console.log(`cards in ${out}`);
