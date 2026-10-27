// Academy screen-recorder. Drives the console through a lesson's scenes and
// records one clip per scene with Playwright's built-in video capture.
//
//   cd tests/e2e && node video/record.mjs <lessonDir>
//
//   <lessonDir>/script.mjs  exports { title, slug, voice?, scenes: [{say, do:[...]}] }
//   clips land in <lessonDir>/dist/scene-NN.webm
//
// Action vocabulary (all selectors are Playwright locators):
//   ["goto", "/agents"]            page.goto
//   ["click", "text=New agent"]    click + settle
//   ["fill", "#name", "Editor"]    fill
//   ["hover", "css=.x"]            hover
//   ["highlight", "css=.x", ms?]   draws a pulsing outline around the element
//   ["waitFor", "css=.x"]          wait for visible
//   ["scroll", 400]                scroll the main scrollable area
//   ["wait", 800]                  plain sleep
//   ["keys", "Control+K"]          keyboard press
//
// Env: ACADEMY_BASE_URL (default http://localhost:3000)
//      ACADEMY_EMAIL / ACADEMY_PASSWORD  (local-auth owner sign-in; the
//      personal stack's `scripts/vegaduta reset-password` prints a one-time
//      setup link - POST /api/local-auth/setup {token, password} mints it)
import { chromium } from "@playwright/test";
import { mkdirSync, readdirSync, renameSync } from "node:fs";
import { join, resolve } from "node:path";

const lessonDir = resolve(process.argv[2] || "");
const BASE = process.env.ACADEMY_BASE_URL || "http://localhost:3000";
const EMAIL = process.env.ACADEMY_EMAIL || "owner@vegaduta.local";
const PASSWORD = process.env.ACADEMY_PASSWORD || "";
const script = (await import(join(lessonDir, "script.mjs"))).default;
const outDir = join(lessonDir, "dist", "clips");
mkdirSync(outDir, { recursive: true });

const SETTLE = 600; // let layout/animations finish so frames aren't blurry

async function signIn(context) {
  const res = await context.request.post(`${BASE}/api/local-auth/login`, {
    data: { identifier: EMAIL, password: PASSWORD },
  });
  if (!res.ok()) throw new Error(`local-auth login failed: ${res.status()} — owner password set?`);
}

async function highlight(page, selector, ms = 1400) {
  const box = await page.locator(selector).first().boundingBox();
  if (!box) return console.warn(`highlight: ${selector} not visible`);
  await page.evaluate(({ x, y, width, height }) => {
    const el = document.createElement("div");
    el.id = "academy-highlight";
    el.style.cssText = `position:fixed;left:${x - 8}px;top:${y - 8}px;width:${width + 16}px;height:${height + 16}px;` +
      "border:3px solid #3fe6cf;border-radius:10px;box-shadow:0 0 24px rgba(63,230,207,.55);" +
      "z-index:2147483647;pointer-events:none;animation:academypulse 0.9s ease-in-out infinite alternate";
    const style = document.createElement("style");
    style.textContent = "@keyframes academypulse{from{opacity:.55}to{opacity:1}}";
    document.head.append(style);
    document.body.append(el);
  }, box);
  await page.waitForTimeout(ms);
  await page.evaluate(() => document.getElementById("academy-highlight")?.remove());
}

async function act(page, [verb, ...args]) {
  switch (verb) {
    case "goto":
      await page.goto(BASE + args[0], { waitUntil: "load" });
      // each scene is a fresh browser profile, so the onboarding modal and
      // tour toasts re-appear on every page - they mount async, so settle
      // first, then sweep them before acting
      await page.waitForTimeout(900);
      for (const sel of ["text=Skip for now", "role=dialog >> role=button[name='Close']", "button:has-text('Not now')"])
        await page.locator(sel).first().click({ timeout: 1500 }).catch(() => {});
      break;
    case "click":     await page.locator(args[0]).first().click(); break;
    case "tryClick":  // dismissible UI (onboarding modals, toasts): click only if it appears
      await page.locator(args[0]).first().click({ timeout: 3000 }).catch(() => {}); break;
    case "fill":      await page.locator(args[0]).first().fill(args[1]); break;
    case "hover":     await page.locator(args[0]).first().hover(); break;
    case "highlight": await highlight(page, args[0], args[1] ?? 1400); break;
    case "waitFor":   await page.locator(args[0]).first().waitFor({ state: "visible", timeout: 15_000 }); break;
    case "scroll":    await page.mouse.wheel(0, args[0]); break;
    case "keys":      await page.keyboard.press(args[0]); break;
    case "wait":      await page.waitForTimeout(args[0]); break;
    default: throw new Error(`unknown action verb: ${verb}`);
  }
  await page.waitForTimeout(SETTLE);
}

const browser = await chromium.launch({ headless: true });
// Sign in inside EACH scene's context: the refresh cookie lands in the
// jar that minted it (a copied storageState loses session state the SPA
// bootstrap needs), and the local-auth login is cheap and unthrottled at
// this cadence.
const filesBefore = () => new Set(readdirSync(outDir));
for (const [i, scene] of script.scenes.entries()) {
  const before = filesBefore();
  const context = await browser.newContext({
    viewport: { width: 1280, height: 720 },
    recordVideo: { dir: outDir, size: { width: 1280, height: 720 } },
  });
  await signIn(context);
  const page = await context.newPage();
  try {
    for (const action of scene.do ?? [{ goto: "/" }].map(o => ["goto", o.goto])) {
      await act(page, Array.isArray(action) ? action : [Object.keys(action)[0], ...Object.values(action)]);
    }
  } finally {
    await context.close(); // flush the clip
  }
  const fresh = [...filesBefore()].filter((f) => !before.has(f) && f.endsWith(".webm"));
  if (fresh.length) renameSync(join(outDir, fresh[0]), join(outDir, `scene-${String(i + 1).padStart(2, "0")}.webm`));
  console.log(`scene ${i + 1}/${script.scenes.length} recorded`);
}
await browser.close();
console.log(`clips in ${outDir}`);
