// Renders og.html -> og-image.png (1200x630, <=350 KB) and hero-still.html -> hero-still.png (1600x1000, <=500 KB).
//   node render-images.mjs [og|hero]      (default: both)
import { mkdirSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { launch, openPage, OUT, sharp } from "./common.mjs";

const which = process.argv[2];
const jobs = [
  { key: "og",   html: "og.html",         out: "og-image.png",  w: 1200, h: 630,  maxKB: 350 },
  { key: "hero", html: "hero-still.html", out: "hero-still.png", w: 1600, h: 1000, maxKB: 500 },
].filter((j) => !which || j.key === which);

mkdirSync(OUT, { recursive: true });
const browser = await launch();
for (const j of jobs) {
  const ctx = await browser.newContext({ viewport: { width: j.w, height: j.h }, deviceScaleFactor: 1, colorScheme: "dark" });
  const page = await openPage(ctx, j.html);
  const raw = await page.screenshot({ type: "png", clip: { x: 0, y: 0, width: j.w, height: j.h } });
  await ctx.close();
  const file = join(OUT, j.out);
  if (!sharp) { writeFileSync(file, raw); console.log("[warn] sharp missing, wrote unoptimised", file); continue; }
  // Try lossless first, then palette PNG with decreasing quality until under the budget.
  const attempts = [
    { compressionLevel: 9, effort: 10 },
    { palette: true, quality: 95, colors: 256, dither: 0.6, compressionLevel: 9, effort: 10 },
    { palette: true, quality: 85, colors: 256, dither: 0.6, compressionLevel: 9, effort: 10 },
    { palette: true, quality: 70, colors: 192, dither: 0.5, compressionLevel: 9, effort: 10 },
    { palette: true, quality: 55, colors: 128, dither: 0.5, compressionLevel: 9, effort: 10 },
  ];
  let done = false;
  for (const opt of attempts) {
    const buf = await sharp(raw).png(opt).toBuffer();
    if (buf.length <= j.maxKB * 1024 || opt === attempts.at(-1)) {
      writeFileSync(file, buf);
      const meta = await sharp(buf).metadata();
      console.log(`[out] ${file}  ${meta.width}x${meta.height}  ${(buf.length / 1024).toFixed(0)} KB  (${opt.palette ? "palette q" + opt.quality : "lossless"})${buf.length > j.maxKB * 1024 ? "  OVER BUDGET" : ""}`);
      done = true; break;
    }
  }
  if (!done) console.log("[warn] no attempt succeeded for", file);
}
await browser.close();
