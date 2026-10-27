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
import { mkdirSync, renameSync } from "node:fs";
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
  // The tour keys are user-scoped (vd_tour_status:<jwt sub>) — decode sub so the
  // init script can pre-set the key that actually gates the welcome blocker.
  try {
    const { accessToken } = await res.json();
    const sub = JSON.parse(Buffer.from(accessToken.split(".")[1], "base64url").toString()).sub;
    await context.addInitScript((s) => {
      try {
        for (const k of ["vd_tour_status", `vd_tour_status:${s}`]) localStorage.setItem(k, "done");
        for (const k of ["vd_tour_seen_security", `vd_tour_seen_security:${s}`, "vd_tour_ended", `vd_tour_ended:${s}`, "vd_agents_guide_seen_v1"])
          localStorage.setItem(k, "1");
      } catch {}
    }, sub);
  } catch {}
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
    case "upload":    // real file pick — works on hidden input[type=file]
      await page.locator(args[0]).first().setInputFiles(args[1]); break;
    case "tryClick":  // dismissible UI (onboarding modals, toasts): click only if it appears
      await page.locator(args[0]).first().click({ timeout: 3000 }).catch(() => {}); break;
    case "fill":      await page.locator(args[0]).first().fill(args[1]); break;
    case "hover":     await page.locator(args[0]).first().hover(); break;
    case "highlight": await highlight(page, args[0], args[1] ?? 1400); break;
    case "waitFor":   await page.locator(args[0]).first().waitFor({ state: "visible", timeout: args[1] ?? 15_000 }); break;
    case "scroll":    await page.mouse.wheel(0, args[0]); break;
    case "keys":      await page.keyboard.press(args[0]); break;
    case "tryHighlight": try { await highlight(page, args[0], args[1]); } catch {} break;
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
for (const [i, scene] of script.scenes.entries()) {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 720 },
    recordVideo: { dir: outDir, size: { width: 1280, height: 720 } },
  });
  // Kill the onboarding tour permanently for this profile. Three layers —
  // the bare seed alone misses: the app reads vd_tour_status scoped by
  // user id (vd_tour_status:<sub>), so (a) seed every scoped variant the
  // token might use, (b) seed the sessionStorage end-marker too, and
  // (c) a MutationObserver deletes any tour DOM that still mounts — the
  // blocker scrim, the welcome hero, step cards — before it can paint a
  // frame (the flicker on every page change). Other first-run modals
  // (AgentsGuide, language prompt, reopen hint) get the same treatment.
  await context.addInitScript(() => {
    try {
      // The app reads vd_tour_status SCOPED per user (vd_tour_status:<sub>) and
      // the sub isn't known until login — intercept the read so every scoped
      // variant answers "done" instead of guessing key names.
      const getItem = Storage.prototype.getItem;
      Storage.prototype.getItem = function (k) {
        if (k && k.startsWith("vd_tour_status")) return "done";
        return getItem.call(this, k);
      };
      localStorage.setItem("vd_tour_status", "done");
      for (const k of ["vd_tour_seen_security", "vd_agents_guide_seen_v1",
                       "vegaduta.studioTour.v1"])
        localStorage.setItem(k, "1");
      sessionStorage.setItem("vd_tour_ended", "1");
    } catch {}
    // CSS hide (not remove): deleting nodes mid-mount can crash React's
    // reconciliation, and a hidden element still satisfies its own lifecycle.
    // Covers the blocker scrims, step cards, the welcome hero dialog, the
    // language prompt, the reopen hint, and the tour's z-[1150]/z-[1152] layers
    // — every surface the tour paints.
    const css =
      "[data-tour-blocker], .hud-tour-card, .lang-choice-card, " +
      "[data-reopen-hint], [role=\"dialog\"][aria-labelledby$=\"-welcome\"], " +
      "[class*=\"z-[1150]\"], [class*=\"z-[1152]\"] " +
      "{ visibility: hidden !important; opacity: 0 !important; " +
      "pointer-events: none !important; }";
    const inject = () => {
      if (document.getElementById("academy-tourkill")) return;
      const st = document.createElement("style");
      st.id = "academy-tourkill";
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    };
    inject();
    new MutationObserver(inject).observe(document.documentElement, {
      childList: true, subtree: true,
    });
  });
  await signIn(context);
  const page = await context.newPage();
  const video = page.video();
  try {
    for (const action of scene.do ?? [{ goto: "/" }].map(o => ["goto", o.goto])) {
      await act(page, Array.isArray(action) ? action : [Object.keys(action)[0], ...Object.values(action)]);
    }
  } finally {
    await context.close(); // flush the clip
  }
  // Rename by the video's OWN path — a dir-diff races when a slow scene's
  // webm lands after the next scene's scan, which shuffles clip numbering.
  try {
    const src = await video.path();
    renameSync(src, join(outDir, `scene-${String(i + 1).padStart(2, "0")}.webm`));
  } catch (e) {
    console.warn(`scene ${i + 1}: no clip produced (${e.message})`);
  }
  console.log(`scene ${i + 1}/${script.scenes.length} recorded`);
}
await browser.close();
console.log(`clips in ${outDir}`);
