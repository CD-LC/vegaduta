// Shared helpers for the landing-media generators. Zero paid APIs; everything runs locally.
import { createRequire } from "node:module";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const HERE = dirname(fileURLToPath(import.meta.url));

// web/ root: these scripts live in web/scripts/landing-media/. WEB_ROOT overrides it
// (e.g. to use another checkout's node_modules).
const WEB_CANDIDATES = [process.env.WEB_ROOT, resolve(HERE, "..", "..")].filter(Boolean);
export const WEB = WEB_CANDIDATES.find((p) => existsSync(join(p, "node_modules", "playwright")));
if (!WEB) throw new Error("Cannot find web/ with node_modules/playwright: run `npm ci` in web/, or set WEB_ROOT.");

const require = createRequire(pathToFileURL(join(WEB, "package.json")));
export const playwright = require("playwright");
export const sharp = (() => { try { return require("sharp"); } catch { return null; } })();

// ffmpeg binary: FFMPEG, else `ffmpeg` on PATH.
export const FFMPEG = process.env.FFMPEG || "ffmpeg";

export const LOGO = process.env.LOGO || join(WEB, "public", "logo-mark.png");
export const logoDataUri = () => "data:image/png;base64," + readFileSync(LOGO).toString("base64");

export const OUT = resolve(process.env.OUT || join(HERE, "out"));

export async function launch() {
  const { chromium } = playwright;
  try {
    return await chromium.launch({ channel: "chromium", headless: true });
  } catch (e) {
    console.warn("[launch] headless chromium channel failed, retrying headed:", e.message.split("\n")[0]);
    return await chromium.launch({ channel: "chromium", headless: false });
  }
}

/** Open an HTML file from this folder, inject the logo, wait for fonts. */
export async function openPage(context, htmlFile) {
  const page = await context.newPage();
  await page.goto(pathToFileURL(join(HERE, htmlFile)).href, { waitUntil: "load" });
  await page.evaluate((src) => { document.querySelectorAll("img.logo").forEach((i) => (i.src = src)); }, logoDataUri());
  // Google Fonts may be slow or offline; never block forever.
  await Promise.race([
    page.evaluate(() => document.fonts.ready.then(() => true)),
    new Promise((r) => setTimeout(() => r(false), 8000)),
  ]).then((ok) => { if (!ok) console.warn("[fonts] timed out waiting for document.fonts.ready; continuing with fallbacks"); });
  await page.evaluate(() => Promise.all([...document.images].map((i) => i.decode().catch(() => {}))));
  const missing = await page.evaluate(async () => {
    const specs = ['700 20px Sora', '400 16px Inter', '400 14px "Noto Sans Devanagari"', '400 14px "JetBrains Mono"'];
    await Promise.all(specs.map((f) => document.fonts.load(f).catch(() => [])));   // force lazy faces to load
    return specs.filter((f) => !document.fonts.check(f));
  });
  if (missing.length) console.warn(`[fonts] NOT loaded (system fallback will be used): ${missing.join(", ")} — run 'node fetch-fonts.mjs' to self-host them`);
  await page.waitForTimeout(250);
  return page;
}
