import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import type { BrowserContext, Page } from "@playwright/test";
import { APP_URL } from "./prod-env";

/**
 * Signed-in session reuse and first-visit overlay suppression for the deployed-instance suite.
 *
 * Why session reuse exists (2026-09-25, observed live): every spec that signs in does a FULL Keycloak hosted-form
 * login - realm page load, form POST, redirect chain, token exchange, plus keycloak-js's silent-SSO iframe probe.
 * With even 2-4 parallel workers that is dozens of auth-host hits per run, which tripped Cloudflare rate limiting
 * on the production auth host (Error 1015 IP ban) mid-suite. Real users behind a shared NAT IP hit the same wall,
 * so the fix belongs in the suite's posture, not just in reruns.
 *
 * What a fresh context needs to restore a console session TODAY: only the Keycloak SSO cookies (auth host + app
 * host) and the `vd_authed` localStorage hint. The console keeps its tokens in memory (web/app/lib/auth.ts only
 * REMOVES the legacy sessionStorage["vd_kc_session"]), so a reload re-authenticates through Keycloak's own SSO
 * cookie (check-sso). The legacy kcSession blob is still captured and replayed when a build writes it.
 *
 * Lifecycle: the FIRST sign-in of a worker does the real hosted-form login and captures state to disk; later
 * sign-ins reuse it. If a reused session fails to reach the console (expired SSO session, revoked), the caller
 * falls back to a fresh form login and re-captures.
 */
// One state file per RUN, shared by that run's workers. Two parallel RUNS used to share the fixed name
// `.e2e-prod-auth-state.worker-N.json` and delete each other's state; now the file is keyed by run id
// (E2E_RUN_ID, else the runner pid, which is a Playwright worker's parent) and lives outside the repo
// (E2E_STATE_DIR, else the OS temp dir) because it holds live session cookies for the e2e tenant. Workers of one
// run may share it: what is saved is the Keycloak SSO session cookie, which Keycloak does not rotate on use (the
// old per-worker split existed because the console used to cache rotating refresh tokens in sessionStorage).
// Full logins are serialised across workers with withLoginLock() below.
const RUN_ID = process.env.E2E_RUN_ID || String(process.ppid || process.pid);
const STATE_DIR = process.env.E2E_STATE_DIR || path.join(os.tmpdir(), "vd-e2e-auth-state");
const STATE_FILE = path.join(STATE_DIR, `.e2e-prod-auth-state.run-${RUN_ID}.json`);
const LOGIN_LOCK = path.join(STATE_DIR, `.e2e-prod-login.run-${RUN_ID}.lock`);
const LOGIN_LOCK_STALE_MS = 3 * 60 * 1000;

/**
 * Runs `fn` while holding a run-wide lock, so only one worker performs a full hosted-form login at a time. Several
 * simultaneous first logins of the same user made Keycloak answer "Invalid username or password" for a correct
 * password on the kind stage, and on production they are what trips the auth host's rate limiting. The workers
 * that wait find the winner's captured session and restore from it instead of logging in again. A lock older than
 * three minutes is treated as abandoned (a crashed worker) and taken over.
 */
export async function withLoginLock<T>(fn: () => Promise<T>): Promise<T> {
  fs.mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 });
  const deadline = Date.now() + 5 * 60 * 1000;
  for (;;) {
    try {
      fs.mkdirSync(LOGIN_LOCK);
      break;
    } catch {
      try {
        if (Date.now() - fs.statSync(LOGIN_LOCK).mtimeMs > LOGIN_LOCK_STALE_MS) {
          fs.rmdirSync(LOGIN_LOCK);
          continue;
        }
      } catch {
        continue; // lock vanished between the two calls: retry immediately
      }
      if (Date.now() > deadline) throw new Error("timed out waiting for the shared login lock " + LOGIN_LOCK);
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  try {
    return await fn();
  } finally {
    try {
      fs.rmdirSync(LOGIN_LOCK);
    } catch {
      /* already gone */
    }
  }
}

/**
 * Keycloak's SSO idle window is short (a capture 5h old restored to a signed-OUT landing page whose
 * header still rendered, and the stale sessionStorage token then 401'd "Jwt expired" on every API
 * call). Re-login past this instead of trusting an old capture.
 */
const MAX_STATE_AGE_MS = 20 * 60 * 1000;

/** Keycloak cookies scoped to a single in-flight login flow; never persisted or replayed. */
const TRANSIENT_AUTH_COOKIES = /^(AUTH_SESSION_ID(_LEGACY)?|KC_RESTART|KC_AUTH_SESSION_HASH)$/i;

interface CapturedAuthState {
  capturedAt: number;
  /**
   * Legacy: sessionStorage["vd_kc_session"] verbatim. Current builds keep tokens in memory only, so this is
   * null there and a restore relies on the SSO cookies below.
   */
  kcSession: string | null;
  /** localStorage["vd_authed"] verbatim. */
  vdAuthed: string | null;
  /** All cookies (app + auth domains) from the signed-in context. */
  cookies: Array<Parameters<BrowserContext["addCookies"]>[0][number]>;
}

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

/**
 * Pre-arm the console's first-visit overlays as "seen" (language prompt, agents guide, demo tour, nav hint, page
 * guides, and the BARE tour keys). The async "Choose your language" backdrop otherwise swallows real clicks
 * mid-test. User-scoped tour keys need the user's subject, which is only known after sign-in: see
 * seedUserScopedTourFlags().
 */
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

/** Reads the `sub` claim of a JWT without verifying it (the token is only ever the test user's own). */
export function subjectOfToken(token: string | null | undefined): string | null {
  if (!token) return null;
  try {
    const payload = token.split(".")[1];
    const json = JSON.parse(Buffer.from(payload.replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8"));
    return typeof json.sub === "string" ? json.sub : null;
  } catch {
    return null;
  }
}

/**
 * The tour keys are USER-scoped (web/app/components/TourProvider.tsx userScoped(): `vd_tour_status:<sub>`),
 * so the bare keys seeded above do nothing once the token has a subject: the welcome modal and the "security"
 * catch-up card re-open on every fresh context and swallow clicks. Seeds them for this user, both for the
 * current document and for every later navigation of this context.
 */
export async function seedUserScopedTourFlags(page: Page, subject: string | null): Promise<void> {
  if (!subject) return;
  const apply = (id: string) => {
    try {
      window.localStorage.setItem(`vd_tour_status:${id}`, "skipped");
      window.localStorage.setItem(`vd_tour_seen_security:${id}`, "1");
    } catch {
      /* storage blocked */
    }
  };
  await page.addInitScript(apply, subject).catch(() => {});
  await page.evaluate(apply, subject).catch(() => {});
}

/** Snapshot the session artifacts from a page that is KNOWN to be signed in. */
export async function captureAuthState(page: Page): Promise<void> {
  const kcSession = await page.evaluate(() => window.sessionStorage.getItem("vd_kc_session"));
  const vdAuthed = await page.evaluate(() => window.localStorage.getItem("vd_authed"));
  // Keep only what restores an SSO session. The per-login-attempt cookies (AUTH_SESSION_ID*, KC_RESTART,
  // KC_AUTH_SESSION_HASH) belong to ONE Keycloak authentication flow; replaying them into a new context makes a
  // later login resume that dead flow ("execution=<same id>") and answer "Invalid username or password".
  const cookies = (await page.context().cookies()).filter((c) => !TRANSIENT_AUTH_COOKIES.test(c.name));
  const state: CapturedAuthState = { capturedAt: Date.now(), kcSession, vdAuthed, cookies };
  try {
    fs.mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 });
    fs.writeFileSync(STATE_FILE, JSON.stringify(state), { mode: 0o600 });
  } catch {
    // Non-fatal: a failed write just means the next sign-in pays full price.
  }
}

function readState(): CapturedAuthState | null {
  try {
    return JSON.parse(fs.readFileSync(STATE_FILE, "utf8")) as CapturedAuthState;
  } catch {
    return null;
  }
}

/** True when a fresh-enough captured state exists on disk. */
export function hasReusableAuthState(): boolean {
  const state = readState();
  return Boolean(state) && state!.cookies.length > 0 && Date.now() - state!.capturedAt < MAX_STATE_AGE_MS;
}

/** True when the signed-out chrome is showing: header "Sign in" as a button (classic) or a link (OSS). */
export async function sawSignInControl(page: Page): Promise<boolean> {
  const header = page.locator("header");
  const control = header.getByRole("button", { name: /^sign in$/i }).or(header.getByRole("link", { name: /^sign in$/i })).first();
  return control.isVisible().catch(() => false);
}

/**
 * Seed a fresh page's context with the captured session and land on the
 * console. Returns true when the app shell is up; false means the cached
 * session is stale and the caller should do a full login instead.
 */
export async function tryRestoreConsoleSession(page: Page): Promise<boolean> {
  const state = readState();
  if (!state || state.cookies.length === 0 || Date.now() - state.capturedAt >= MAX_STATE_AGE_MS) {
    return false;
  }

  await page.context().addCookies(state.cookies).catch(() => {});
  await seedConsoleSeenFlags(page);
  await page.addInitScript(
    ([kcSession, vdAuthed]) => {
      try {
        // Only seed when empty: after a failed restore falls back to a real login, this script still
        // runs on every navigation and must not overwrite the fresh post-login session with the stale one.
        if (kcSession && !window.sessionStorage.getItem("vd_kc_session")) window.sessionStorage.setItem("vd_kc_session", kcSession);
        if (vdAuthed) window.localStorage.setItem("vd_authed", vdAuthed);
      } catch {
        /* storage blocked - the caller will see the false return below */
      }
    },
    [state.kcSession, state.vdAuthed]
  );

  await page.goto(APP_URL, { waitUntil: "domcontentloaded" });
  // Signed-in proof: the console shell's header. A stale seed leaves the landing page (header with a Sign in
  // control) or the error splash behind.
  const headerVisible = await page
    .locator("header")
    .waitFor({ state: "visible", timeout: 30_000 })
    .then(() => true)
    .catch(() => false);
  if (!headerVisible) return false;
  const body = await page.locator("body").innerText().catch(() => "");
  if (/login failed/i.test(body)) return false;
  if (await sawSignInControl(page)) return false;
  // The console can render its shell optimistically and THEN die: keycloak-js finishes init/refresh
  // asynchronously, and a rejection flips the page to the "Login failed: undefined" splash seconds after the
  // header mounted (observed live 2026-09-25). Settle briefly and re-check so a dying session falls back to the
  // real login instead of poisoning the whole test.
  await page.waitForTimeout(2_500);
  const settled = await page.locator("body").innerText().catch(() => "");
  if (/login failed/i.test(settled)) return false;
  return !(await sawSignInControl(page));
}

/** Drop the cached state - e.g. after a restore failure. */
export function clearAuthState(): void {
  try {
    fs.unlinkSync(STATE_FILE);
  } catch {
    /* absent is fine */
  }
}
