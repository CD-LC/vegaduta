import { type Page, type TestInfo, expect } from "@playwright/test";
import { APP_URL, ADMIN_URL, DOMAIN, USER_EMAIL, USER_PASSWORD, ADMIN_EMAIL, ADMIN_PASSWORD } from "./prod-env";
import {
  captureAuthState,
  withLoginLock,
  hasReusableAuthState,
  tryRestoreConsoleSession,
  clearAuthState,
  seedConsoleSeenFlags,
  seedUserScopedTourFlags,
  subjectOfToken,
  sawSignInControl,
} from "./prod-auth-state";
import { trackBearerToken, peekBearerToken } from "./prod-api";

/**
 * Domain-aware session helpers for the production suite.
 *
 * Deliberately NOT reusing helpers/keycloak-login.ts: that one hardcodes
 * the local-suite auth/app hosts in its waitForURL regexes, so it
 * would hang forever against .ai. Everything here derives its hosts from
 * DOMAIN instead.
 */
const HOST_RE = (host: string) => new RegExp(host.replace(/\./g, "\\."));

const AUTH_HOST_RE = HOST_RE(`auth.${DOMAIN}`);
const APP_HOST_RE = HOST_RE(`app.${DOMAIN}`);
const ADMIN_HOST_RE = HOST_RE(`admin.${DOMAIN}`);

/**
 * Fills and submits Keycloak's hosted login form. Field ids are Keycloak's
 * own FreeMarker template variables (#username / #password / #kc-login), so
 * they survive theme restyles.
 */
async function submitKeycloakForm(page: Page, email: string, password: string) {
  await page.waitForURL(AUTH_HOST_RE, { timeout: 45_000 });

  const username = page.locator("#username");
  const current = await username.inputValue().catch(() => "");
  if (current !== email) {
    await username.fill(email);
  }
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();

  // Under parallel workers the hosted form sometimes answers "Invalid username or password" for a correct
  // password (observed on the kind stage with 4 concurrent sign-ins; the same login passes alone). Retry ONCE
  // with fresh field values: a single extra attempt, so a genuinely wrong password cannot trip Keycloak's
  // brute-force protection.
  const invalid = page.getByText(/invalid username or password/i);
  const outcome = await Promise.race([
    page.waitForURL((u) => !AUTH_HOST_RE.test(u.hostname), { waitUntil: "commit", timeout: 20_000 }).then(() => "left" as const),
    invalid.waitFor({ state: "visible", timeout: 20_000 }).then(() => "invalid" as const),
  ]).catch(() => "pending" as const);
  if (outcome === "invalid") {
    await page.waitForTimeout(1_500);
    await page.locator("#username").fill(email);
    await page.locator("#password").fill(password);
    await page.locator("#kc-login").click();
  }
}

// Playwright gives every test a brand-new browser context, so this throwaway
// account's console onboarding (never dismissed in THIS context before)
// fires every single time: a "Choose your language" dialog first, then a
// "How agents work" tour card, occasionally a "Welcome to VegaDūta" modal
// too - all three verified live against a deployed instance. None of
// them block a plain content read, but any of them sits on top of the page
// and swallows pointer events, so a click-driven test (sign out, create an
// agent) fails downstream with "<dialog> intercepts pointer events" - a
// false negative unrelated to whatever the test actually checks. Dismissed
// once here, right after sign-in, rather than per test.
async function dismissOnboardingModals(page: Page) {
  const dismissLabels = [
    /^not now$/i,
    /^skip, i'll explore$/i,
    /^continue in english$/i, // LanguageChoicePrompt's primary
    /got it/i,
    /^skip$/i,
  ];
  for (let round = 0; round < 4; round++) {
    let dismissed = false;
    for (const label of dismissLabels) {
      const btn = page.getByRole("button", { name: label }).first();
      if (await btn.isVisible({ timeout: 1_500 }).catch(() => false)) {
        await btn.click().catch(() => {});
        dismissed = true;
        await page.waitForTimeout(300);
      }
    }
    // LanguageChoicePrompt also closes on Escape; it can mount seconds late
    // (async geo check), so Escape is the safety net for a prompt that
    // appeared after its buttons were already probed.
    // Also the welcome tour and the "security" (2FA) catch-up card, which mount late and carry these markers.
    const backdrop = page
      .locator('.hud-tour-backdrop[aria-modal="true"], [data-tour-blocker], [role="dialog"][aria-label="Product tour"]')
      .first();
    if (await backdrop.isVisible().catch(() => false)) {
      await page.keyboard.press("Escape").catch(() => {});
      dismissed = true;
      await page.waitForTimeout(300);
    }
    if (!dismissed) break;
  }
}

/**
 * Walks the real public sign-in entry up to the Keycloak hosted form WITHOUT typing credentials, for handoff
 * specs. Works on both public-site shapes (see beginSignIn); the email typed into the OSS email step is a
 * throwaway address that never exists as a user.
 */
export async function openKeycloakLoginFromApp(page: Page, email = `e2e-handoff-${Date.now()}@example.invalid`) {
  await beginSignIn(page, email);
  await page.waitForURL(AUTH_HOST_RE, { timeout: 45_000 });
}

/** Signs an existing tenant user into the console at app.<domain>. */
export async function signInToConsole(page: Page) {
  if (!USER_EMAIL || !USER_PASSWORD) {
    throw new Error("signInToConsole called without E2E_USER_EMAIL/E2E_USER_PASSWORD set");
  }

  // Capture the app's Authorization header from the first request (tokens are memory-only now; see
  // prod-api.ts). Must be registered before any navigation.
  trackBearerToken(page);
  const email = USER_EMAIL;
  const password = USER_PASSWORD;

  // Session reuse (2026-09-25): the per-test full hosted-form login was the suite's dominant flake source -
  // enough parallel sign-ins trip Cloudflare rate limiting on auth.<domain> (Error 1015 ban observed live). A
  // captured set of SSO cookies restores in one check-sso instead. A stale capture falls back to the real
  // login below and re-captures.
  if (hasReusableAuthState() && (await tryRestoreConsoleSession(page))) {
    await finishSignIn(page);
    return;
  }
  clearAuthState();
  // A failed restore leaves the replayed cookies in this context; start the real login from a clean jar.
  await page.context().clearCookies();

  // One full login at a time across this run's workers (see withLoginLock). Workers that waited re-check for the
  // session the winner captured before logging in themselves.
  await withLoginLock(async () => {
    if (hasReusableAuthState() && (await tryRestoreConsoleSession(page))) return;
    await page.context().clearCookies();

    await seedConsoleSeenFlags(page);
    await beginSignIn(page, email);
    await submitKeycloakForm(page, email, password);

    await page.waitForURL((u) => APP_HOST_RE.test(u.hostname) && !u.hash.includes("state="), {
      waitUntil: "commit",
      timeout: 45_000,
    });
    await expect(page.locator("header")).toBeVisible({ timeout: 30_000 });

    // Confirm we really landed in the console, not back on the marketing landing page (what a failed silent-SSO
    // looks like): the signed-out chrome has a Sign in control.
    await expect
      .poll(async () => (await page.locator("body").innerText()).length, { timeout: 30_000 })
      .toBeGreaterThan(0);
    await expect.poll(() => sawSignInControl(page), { timeout: 30_000, message: "still showing the Sign in control" }).toBe(false);

    // Capture while still holding the lock so the next worker in line can restore from it.
    await finishSignIn(page);
  });
}

/**
 * Common tail of every sign-in: wait for the first authenticated API call, seed the USER-scoped tour keys from
 * the token's subject (the bare keys do not apply once the token has a subject), dismiss whatever overlay still
 * opened, and persist the session for the next sign-in of this worker.
 */
async function finishSignIn(page: Page) {
  const deadline = Date.now() + 20_000;
  while (!peekBearerToken(page) && Date.now() < deadline) await page.waitForTimeout(250);
  await seedUserScopedTourFlags(page, subjectOfToken(peekBearerToken(page)));
  await dismissOnboardingModals(page);
  await captureAuthState(page).catch(() => {});
}

/**
 * Starts the sign-in from the app and ends on the Keycloak form. Handles both public-site shapes:
 *   - OSS / current console: /login (email step, "Continue to secure sign-in") then the Keycloak form; the header
 *     "Sign in" is a LINK to /login.
 *   - classic: a header "Sign in" BUTTON that goes to Keycloak directly.
 * Going to /login directly covers the first shape without depending on the header control at all; the header
 * (button or link) is the fallback when /login shows neither an email step nor a redirect.
 */
async function beginSignIn(page: Page, email: string) {
  const continueBtn = page.getByRole("button", { name: /continue to secure/i });
  await page.goto(`${APP_URL}/login`, { waitUntil: "domcontentloaded" });

  const first = await Promise.race([
    page.waitForURL(AUTH_HOST_RE, { timeout: 20_000 }).then(() => "auth" as const),
    continueBtn.waitFor({ state: "visible", timeout: 20_000 }).then(() => "email" as const),
  ]).catch(() => "none" as const);

  if (first === "email") {
    await submitEmailStep(page, email);
    return;
  }
  if (first === "auth") return;

  // /login gave us nothing: fall back to the header control (button on the classic site, link on OSS).
  await page.goto(APP_URL, { waitUntil: "domcontentloaded" });
  const header = page.locator("header");
  await header
    .getByRole("button", { name: /^sign in$/i })
    .or(header.getByRole("link", { name: /^sign in$/i }))
    .first()
    .click();
  if (await continueBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await submitEmailStep(page, email);
  }
}

/**
 * The /login email step is server-rendered, so its input and button are visible BEFORE React hydrates: a fill
 * made in that window is wiped by hydration and the click does nothing (observed on the stage: the page sat on
 * an empty email field until the 45s Keycloak wait expired). Fill, verify the value stuck, click, and retry
 * until the browser actually leaves for Keycloak.
 */
async function submitEmailStep(page: Page, email: string) {
  const input = page.getByLabel(/email/i).first();
  const continueBtn = page.getByRole("button", { name: /continue to secure/i });
  for (let attempt = 0; attempt < 4; attempt++) {
    await input.fill(email);
    if ((await input.inputValue().catch(() => "")) !== email) {
      await page.waitForTimeout(500);
      continue;
    }
    await continueBtn.click();
    const left = await page
      .waitForURL(AUTH_HOST_RE, { timeout: 10_000 })
      .then(() => true)
      .catch(() => false);
    if (left) return;
  }
}

/** Signs the platform admin into the merged console at admin.<domain>. */
export async function signInToAdmin(page: Page) {
  if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
    throw new Error("signInToAdmin called without E2E_ADMIN_EMAIL/E2E_ADMIN_PASSWORD set");
  }

  await page.goto(ADMIN_URL);
  await submitKeycloakForm(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  await page.waitForURL(ADMIN_HOST_RE, { timeout: 45_000 });
  await expect(page.locator("header")).toBeVisible({ timeout: 30_000 });
}

/**
 * Expands the workbench's "Reasoning & autonomy" accordion on /agents.
 *
 * The autonomy tier and reasoning depth pills live inside a CollapsibleSection
 * that renders collapsed by default (QA-05), and while the agents list is
 * still loading the workbench shows a SavedOnlyStub with the SAME label -
 * not a button - so waiting on the trigger doubles as "a saved agent is
 * selected and its real section is mounted". Found the hard way 2026-09-25:
 * specs that looked for the "Supervised" pill straight after goto("/agents")
 * raced the agents list and never found it.
 */
export async function expandReasoningAutonomySection(page: Page): Promise<void> {
  const trigger = page.getByRole("button", { name: /reasoning & autonomy/i }).first();
  await expect(trigger, "Reasoning & autonomy section never mounted (workbench still in draft state?)").toBeVisible({
    timeout: 30_000,
  });
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click();
  }
}

/** True once the console shell (not the logged-out landing page) is on screen. */
export async function isSignedIn(page: Page): Promise<boolean> {
  const body = await page.locator("body").innerText();
  return /sign out|workspace|dashboard/i.test(body);
}

/**
 * Skips (loudly, with the real reason) a test that needs a hosted-model chat
 * turn when the tenant has used up its DAILY platform-pool allowance.
 *
 * Found the hard way on 2026-09-23: after a day of test runs the throwaway
 * tenant sat at 93/90 messages, the console showed "You've hit your plan's AI
 * allowance. Agent chats on the platform AI pool are paused until the window
 * resets", and the MCP spec then failed with "no tool call was recorded for
 * this turn" - a misleading message for what is an environmental limit, not
 * a product defect. This reads the same banner/counter a user sees.
 */
export async function skipIfHostedAllowanceExhausted(page: Page, testInfo: TestInfo): Promise<void> {
  await page.getByText(/\d+\s*\/\s*\d+\s*today/i).first().waitFor({ timeout: 8_000 }).catch(() => {});
  const body = await page.locator("body").innerText();
  const counter = body.match(/(\d+)\s*\/\s*(\d+)\s*today/i);
  const exhausted =
    /you've hit your plan's AI allowance/i.test(body) || (counter !== null && Number(counter[1]) >= Number(counter[2]));
  testInfo.skip(
    exhausted,
    `this tenant's daily hosted-AI allowance is used up (${counter ? `${counter[1]}/${counter[2]}` : "banner shown"}) - ` +
      "agent chats are paused until it resets; environmental, not a product failure"
  );
}
