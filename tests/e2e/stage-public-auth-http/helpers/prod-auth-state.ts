import * as fs from "fs";
import * as path from "path";
import type { BrowserContext, Page } from "@playwright/test";
import { APP_URL } from "./prod-env";

/**
 * Signed-in session reuse for the production suite.
 *
 * Why this exists (2026-09-25, observed live): every spec that signs in does
 * a FULL Keycloak hosted-form login - realm page load, form POST, redirect
 * chain, token exchange, plus keycloak-js's silent-SSO iframe probe. With
 * even 2-4 parallel workers that is dozens of auth-host hits per run, which
 * tripped Cloudflare rate limiting on the production auth host (Error 1015 IP ban)
 * mid-suite and turned every sign-in-dependent test red for the ban window.
 * Real users behind a shared NAT IP hit the same wall, so the fix belongs in
 * the suite's posture, not just in reruns.
 *
 * What a fresh context actually needs to restore a console session:
 *   - sessionStorage["vd_kc_session"]   (auth.ts fast-restore: one refresh
 *     POST, no iframe, no redirect - see TOKEN_CACHE_KEY in lib/auth.ts)
 *   - localStorage["vd_authed"] = "1"   (gates the fast-restore read)
 *   - the auth-host SSO cookies  (what a refresh ultimately leans on)
 *
 * storageState files persist cookies + localStorage but NOT sessionStorage,
 * so the token blob is captured separately here and re-seeded with
 * addInitScript (origin-scoped: it runs on app.<domain> before any page
 * script, which is exactly where the real app wrote it).
 *
 * Lifecycle: the FIRST sign-in in a run does the real hosted-form login and
 * captures state to disk; later sign-ins on the same machine reuse it. If a
 * reused session fails to reach the console (expired refresh token, revoked
 * SSO session), the caller falls back to a fresh form login and re-captures.
 *
 * The file is deliberately NOT shared across machines/runs (gitignored
 * pattern name) - it holds a live bearer token for the e2e tenant.
 */
// Per-WORKER state files: Keycloak rotates refresh tokens on every use, so
// two parallel workers sharing one captured session race - whichever context
// refreshes second replays a spent token and the app dies on
// "Login failed: undefined". One login per worker keeps each rotation chain
// single-threaded while still preserving parallelism.
const WORKER_INDEX = process.env.TEST_WORKER_INDEX ?? "0";
const STATE_FILE = path.join(__dirname, "..", `.e2e-prod-auth-state.worker-${WORKER_INDEX}.json`);
/**
 * Keycloak's SSO idle window is short (a capture 5h old restored to a signed-OUT landing page whose
 * header still rendered, and the stale sessionStorage token then 401'd "Jwt expired" on every API
 * call). Re-login past this instead of trusting an old capture.
 */
const MAX_STATE_AGE_MS = 20 * 60 * 1000;

interface CapturedAuthState {
  capturedAt: number;
  /** sessionStorage["vd_kc_session"] verbatim - {token, refreshToken, idToken}. */
  kcSession: string | null;
  /** localStorage["vd_authed"] verbatim. */
  vdAuthed: string | null;
  /** All cookies (app + auth domains) from the signed-in context. */
  cookies: Array<Parameters<BrowserContext["addCookies"]>[0][number]>;
}

/**
 * Pre-arm the console's first-visit overlays as "seen" for the full-form
 * login path too (the restore path seeds these inside its own init script).
 * Same reason: the async "Choose your language" backdrop otherwise swallows
 * real clicks mid-test.
 */
/**
 * Every page-guide id (web/app/components/pageguide/pageGuides.ts). The
 * PageGuideButton auto-opens a guide dialog the FIRST time a console page is
 * visited per browser - e.g. "Build your first workflow" sat over the
 * workflow canvas's Share button and swallowed the click (observed live
 * 2026-09-25). One key, JSON array of ids; seeding them all marks every
 * guide as already-shown.
 */
const PAGE_GUIDE_IDS = [
  "home", "agent-templates", "agents", "workflow-templates", "workflow-runs",
  "workflow-canvas", "workflows", "templates", "knowledge", "quick-task",
  "jobs", "studio", "shared", "channels", "tools", "secrets", "code-intel",
  "build", "edge", "code", "playground", "files", "usage", "billing",
  "guardrails", "mcp-servers", "branding", "admin", "help",
];

export async function seedConsoleSeenFlags(page: Page): Promise<void> {
  await page
    .addInitScript((guideIds: string[]) => {
      try {
        window.localStorage.setItem("agentic_language_choice_seen_v1", "1");
        window.localStorage.setItem("vd_lang_prompt_seen", "1");
        window.localStorage.setItem("vd_tour_status", "skipped");
        window.localStorage.setItem("vd_agents_guide_seen_v1", "1");
        window.localStorage.setItem("vd_demo_tour_seen_v1", "1");
        window.localStorage.setItem("floatingNav.hintSeen.v1", "1");
        window.localStorage.setItem("vd_page_guides_seen", JSON.stringify(guideIds));
      } catch {
        /* storage blocked */
      }
    }, PAGE_GUIDE_IDS)
    .catch(() => {});
}

/** Snapshot the session artifacts from a page that is KNOWN to be signed in. */
export async function captureAuthState(page: Page): Promise<void> {
  const kcSession = await page.evaluate(() => window.sessionStorage.getItem("vd_kc_session"));
  const vdAuthed = await page.evaluate(() => window.localStorage.getItem("vd_authed"));
  const cookies = await page.context().cookies();
  const state: CapturedAuthState = { capturedAt: Date.now(), kcSession, vdAuthed, cookies };
  try {
    fs.writeFileSync(STATE_FILE, JSON.stringify(state), { mode: 0o600 });
  } catch {
    // Non-fatal: a failed write just means the next sign-in pays full price.
  }
}

/** True when a fresh-enough captured state exists on disk. */
export function hasReusableAuthState(): boolean {
  try {
    const raw = fs.readFileSync(STATE_FILE, "utf8");
    const state = JSON.parse(raw) as CapturedAuthState;
    return Boolean(state.kcSession) && Date.now() - state.capturedAt < MAX_STATE_AGE_MS;
  } catch {
    return false;
  }
}

/**
 * Seed a fresh page's context with the captured session and land on the
 * console. Returns true when the app shell is up; false means the cached
 * session is stale and the caller should do a full login instead.
 */
export async function tryRestoreConsoleSession(page: Page): Promise<boolean> {
  let state: CapturedAuthState;
  try {
    state = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
  } catch {
    return false;
  }
  if (!state.kcSession || Date.now() - state.capturedAt >= MAX_STATE_AGE_MS) {
    return false;
  }

  await page.context().addCookies(state.cookies).catch(() => {});
  await page.addInitScript(
    ([kcSession, vdAuthed, guideIds]) => {
      try {
        // Only seed when empty: after a failed restore falls back to a real login, this script still
        // runs on every navigation and must not overwrite the fresh post-login session with the stale one.
        if (!window.sessionStorage.getItem("vd_kc_session")) window.sessionStorage.setItem("vd_kc_session", kcSession);
        if (vdAuthed) window.localStorage.setItem("vd_authed", vdAuthed);
        // Every fresh Playwright context re-fires the console's first-visit
        // overlays (browser-local "seen" flags), and the language prompt in
        // particular mounts ASYNC after geo resolution - too late for the
        // post-login dismiss pass, and its backdrop intercepts real clicks.
        // Mark them all seen up front - including every page-guide id, whose
        // auto-open dialog sits over real controls (observed live: "Build
        // your first workflow" swallowed the canvas Share click).
        window.localStorage.setItem("agentic_language_choice_seen_v1", "1");
        window.localStorage.setItem("vd_lang_prompt_seen", "1");
        window.localStorage.setItem("vd_tour_status", "skipped");
        window.localStorage.setItem("vd_agents_guide_seen_v1", "1");
        window.localStorage.setItem("vd_demo_tour_seen_v1", "1");
        window.localStorage.setItem("floatingNav.hintSeen.v1", "1");
        window.localStorage.setItem("vd_page_guides_seen", JSON.stringify(guideIds));
      } catch {
        /* storage blocked - the caller will see the false return below */
      }
    },
    [state.kcSession, state.vdAuthed, PAGE_GUIDE_IDS]
  );

  await page.goto(APP_URL, { waitUntil: "domcontentloaded" });
  // Signed-in proof: the console shell's header. A stale seed leaves the
  // landing page (no <header> with sign-in) or the error splash behind.
  const headerVisible = await page
    .locator("header")
    .waitFor({ state: "visible", timeout: 30_000 })
    .then(() => true)
    .catch(() => false);
  if (!headerVisible) return false;
  const body = await page.locator("body").innerText().catch(() => "");
  if (/login failed/i.test(body)) return false;
  // A signed-out landing page also has a <header>; its "Sign in" button is the tell.
  if (await page.locator("header").getByRole("button", { name: /^sign in$/i }).first().isVisible().catch(() => false)) {
    return false;
  }
  // The console can render its shell optimistically and THEN die: keycloak-js
  // finishes init/refresh asynchronously, and a rejection flips the page to
  // the "Login failed: undefined" splash seconds after the header mounted
  // (observed live 2026-09-25 - restores looked healthy, tests then ran on a
  // dead page). Settle briefly and re-check so a dying session falls back to
  // the real login instead of poisoning the whole test.
  await page.waitForTimeout(2_500);
  const settled = await page.locator("body").innerText().catch(() => "");
  return !/login failed/i.test(settled);
}

/** Drop the cached state - e.g. after a restore failure. */
export function clearAuthState(): void {
  try {
    fs.unlinkSync(STATE_FILE);
  } catch {
    /* absent is fine */
  }
}
