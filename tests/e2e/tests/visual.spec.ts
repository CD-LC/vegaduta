import { test, expect, type Page } from "@playwright/test";
import { collectConsoleErrors } from "../helpers/console-errors";
import { AUTH_HOST_RE } from "../helpers/e2e-env";

// WeatherThemeEngine.tsx's two background fetches (get.geojs.io,
// api.open-meteo.com) are deliberately aborted client-side on 'pagehide' so
// they don't outlive a real navigation - but Chromium can still log
// "Failed to load resource: net::ERR_NAME_NOT_RESOLVED" for a request killed
// by the navigation itself, racing our own abort() (confirmed live,
// 2026-08-30: the abort is real and correct - the log is a browser-level
// artifact of the unload race, not something JS can reliably suppress).
// Tried scoping this precisely via page.on("requestfailed") (which DOES
// carry the URL, unlike the console message text) - doesn't work: a request
// killed by a full-page cross-origin navigation tears down the page's CDP
// session before that event reliably fires, confirmed by direct
// reproduction (allowance stayed 0 every run). console-errors.ts's shared
// IGNORED_PATTERNS is deliberately NOT touched for this - it applies to
// every spec in the suite, and this exact generic message could otherwise
// mask an unrelated real DNS failure somewhere else. Scoped instead to
// EXACTLY this one test: allow up to 2 occurrences (the geo call and the
// weather call, each logs its own) of this exact known message, nothing
// else - a real, different error still fails this test.
const WEATHER_RACE_MESSAGE = "Failed to load resource: net::ERR_NAME_NOT_RESOLVED";
const WEATHER_RACE_MAX_OCCURRENCES = 2;

function filterOutWeatherFetchRaceNoise(errors: string[]): string[] {
  let allowance = WEATHER_RACE_MAX_OCCURRENCES;
  const filtered: string[] = [];
  for (const err of errors) {
    if (err === WEATHER_RACE_MESSAGE && allowance > 0) {
      allowance -= 1;
      continue;
    }
    filtered.push(err);
  }
  return filtered;
}

// Visual + "did anything actually break" smoke checks ahead of the staged
// user-test release. Screenshot assertions are pixel-diff snapshots (see
// README.md - the FIRST run on a machine/CI image must create baselines
// with `--update-snapshots` before this can catch real regressions).
//
// NOTE: these tests set their own explicit viewport via test.use() rather
// than relying on whichever Playwright project runs them, so "desktop
// 1280x800" and "mobile 375x812" are exactly what's requested regardless of
// which project (chromium / mobile-chrome) executes the file.

/**
 * Neutralizes the landing page's continuous-motion demo widgets so a
 * full-page screenshot is actually deterministic, without deleting
 * coverage of the static parts of the page.
 *
 * What was actually going on (confirmed by reading the components, not
 * guessing): almost every "live demo" on the landing page
 * (HeroLanguageDemo, SmbSolutions' auto-cycling persona carousel,
 * WhatsAppDemo, UpiWorkflowDemo) already special-cases
 * `useReducedMotion()` from `motion/react` to skip its stagger timers and
 * render its FINAL state immediately - that's a real, existing product
 * behavior for accessibility, not something added for this test. Calling
 * `page.emulateMedia({ reducedMotion: "reduce" })` before navigating gets
 * all of those for free.
 *
 * The one holdout is VoiceCallDemo ("Never miss a call again"): its call
 * phase / transcript-reveal timers do NOT check reduced-motion (only its
 * purely decorative waveform-bar tick does), so it runs a real ~27s
 * one-shot call simulation regardless, during which its own section
 * height keeps growing (its transcript panel is `max-h-[340px]
 * overflow-y-auto` and grows toward that cap as lines are revealed) -
 * that growing height is exactly what made "two consecutive stable
 * screenshots" fail before. Rather than pad every run with a ~30s wait,
 * this hides that one section for the screenshot (it has its own
 * dedicated coverage need, if any, in a component-level test - not this
 * page-level visual smoke check) so the rest of the page can be verified
 * pixel-for-pixel.
 */
async function stabilizeLandingForScreenshot(page: Page): Promise<void> {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");

  // Scroll all the way down and back so any `whileInView` (IntersectionObserver-
  // gated, `viewport: { once: true }`) reveal further down the page - e.g. the
  // closing CTA section - has genuinely fired at least once, rather than
  // possibly being sampled mid-reveal (or not yet triggered) depending on how
  // the full-page screenshot's virtual viewport interacts with scroll-linked
  // observers.
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await page.waitForTimeout(300);
  await page.evaluate(() => window.scrollTo(0, 0));

  // Hide the one demo that keeps animating/growing regardless of reduced
  // motion (see docstring above). Found by its heading text rather than a
  // structural selector, since the component has neither an id nor a
  // data-testid and DOM order is not a stable enough thing to key off.
  await page.evaluate(() => {
    const heading = [...document.querySelectorAll("h2")].find((h) => h.textContent?.trim() === "Never miss a call again");
    const section = heading?.closest("section");
    if (section instanceof HTMLElement) section.style.display = "none";
  });

  // Give in-flight network calls kicked off on mount (e.g. the first-visit
  // geolocation/language-prompt lookup) time to actually resolve or fail
  // before anything downstream inspects collected console/page errors -
  // otherwise a genuine, consistently-reproducible failure can appear to
  // come and go simply because this function now finishes faster than it
  // used to (reduced motion skips most of the staggered-reveal waits).
  await page.waitForTimeout(1200);
}

test.describe("Landing page - desktop", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test("desktop screenshot + no uncaught console errors", async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await stabilizeLandingForScreenshot(page);

    await expect(page).toHaveScreenshot("landing-desktop.png", {
      fullPage: true,
      maxDiffPixelRatio: 0.02,
      animations: "disabled",
    });

    // KNOWN, real, currently-failing app bugs (see e2e/README.md and the
    // fix-up report for this suite) - deliberately NOT added to
    // helpers/console-errors.ts's ignore list, because they are not
    // third-party noise: (1) the page calls https://ipapi.co/json/
    // directly from the browser and that call is CORS-blocked, and (2)
    // every load attempts a silent-SSO iframe check against
    // the Keycloak host that Keycloak's own frame-ancestors CSP rejects
    // (when its web-origins list does not include
    // the real app domain). Leaving this assertion in place -
    // rather than widening the ignore list - is what makes it keep
    // surfacing those two real bugs instead of going quietly green.
    expect(errors.errors, `Unexpected console/page errors:\n${errors.errors.join("\n")}`).toEqual([]);
  });
});

test.describe("Landing page - mobile", () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test("mobile screenshot, no horizontal overflow, no uncaught console errors", async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await stabilizeLandingForScreenshot(page);

    await expect(page).toHaveScreenshot("landing-mobile.png", {
      fullPage: true,
      maxDiffPixelRatio: 0.02,
      animations: "disabled",
    });

    // A page that scrolls sideways on a phone is a real, user-visible bug
    // (usually an element with a fixed width or an unwrapped flex row) -
    // +1px tolerance for subpixel rounding, not to mask a real overflow.
    const { scrollWidth, viewportWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      viewportWidth: window.innerWidth,
    }));
    expect(scrollWidth).toBeLessThanOrEqual(viewportWidth + 1);

    // See the desktop test above for why these two specific errors are
    // deliberately not ignored - they are real, reproducible app bugs.
    expect(errors.errors, `Unexpected console/page errors:\n${errors.errors.join("\n")}`).toEqual([]);
  });
});

test.describe("Keycloak login page", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test("login page screenshot + no uncaught console errors", async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/");
    // Sign-in only lives in the header now (see auth.spec.ts) - the footer's
    // "Company" column that used to hold this button was removed.
    await page.locator("header").getByRole("button", { name: "Sign in" }).click();
    await page.waitForURL(AUTH_HOST_RE, { timeout: 30_000 });
    await page.waitForLoadState("networkidle");

    await expect(page).toHaveScreenshot("keycloak-login.png", {
      fullPage: true,
      maxDiffPixelRatio: 0.02,
      animations: "disabled",
    });

    // See the desktop landing test above for why these two specific errors
    // (ipapi.co CORS + Keycloak frame-ancestors CSP) are deliberately not
    // ignored - they are real, reproducible app bugs, not third-party noise.
    // WeatherThemeEngine's own navigation-race noise (see this file's top
    // comment) is filtered separately and narrowly, not blanket-ignored.
    const realErrors = filterOutWeatherFetchRaceNoise(errors.errors);
    expect(realErrors, `Unexpected console/page errors:\n${realErrors.join("\n")}`).toEqual([]);
  });
});
