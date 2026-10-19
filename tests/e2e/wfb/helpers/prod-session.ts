import { type Page, type TestInfo, expect } from "@playwright/test";
import { APP_URL, ADMIN_URL, DOMAIN, USER_EMAIL, USER_PASSWORD, ADMIN_EMAIL, ADMIN_PASSWORD } from "./prod-env";
import { captureAuthState, hasReusableAuthState, tryRestoreConsoleSession, clearAuthState, seedConsoleSeenFlags } from "./prod-auth-state";

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
    const backdrop = page.locator('.hud-tour-backdrop[aria-modal="true"]').first();
    if (await backdrop.isVisible().catch(() => false)) {
      await page.keyboard.press("Escape").catch(() => {});
      dismissed = true;
      await page.waitForTimeout(300);
    }
    if (!dismissed) break;
  }
}

/** Signs an existing tenant user into the console at app.<domain>. */
export async function signInToConsole(page: Page) {
  if (!USER_EMAIL || !USER_PASSWORD) {
    throw new Error("signInToConsole called without E2E_USER_EMAIL/E2E_USER_PASSWORD set");
  }

  // Session reuse (2026-09-25): the per-test full hosted-form login was the
  // suite's dominant flake source - enough parallel sign-ins trip Cloudflare
  // rate limiting on auth.<domain> (Error 1015 ban observed live), taking
  // every subsequent sign-in-dependent test down with it. A captured
  // vd_kc_session + SSO cookies restore in one refresh POST instead. A stale
  // capture falls back to the real login below and re-captures.
  if (hasReusableAuthState() && (await tryRestoreConsoleSession(page))) {
    await dismissOnboardingModals(page);
    // Keycloak rotates refresh tokens and auth.ts persists them back into
    // vd_kc_session on every refresh (onAuthRefreshSuccess -> persistTokens),
    // so the on-disk capture is stale the moment this context refreshed.
    // Re-capture NOW, while this page holds the rotated tokens, or the next
    // test's restore replays an already-spent refresh token and the app dies
    // on "Login failed: undefined".
    await captureAuthState(page).catch(() => {});
    return;
  }
  clearAuthState();

  await seedConsoleSeenFlags(page);
  await page.goto(APP_URL);
  await page.getByRole("link", { name: /^sign in$/i }).first().click();
  await page.getByRole("textbox", { name: "Email" }).fill(USER_EMAIL);
  await page.getByRole("button", { name: /continue to secure sign-in/i }).click();
  await submitKeycloakForm(page, USER_EMAIL, USER_PASSWORD);

  await page.waitForURL(APP_HOST_RE, { timeout: 45_000 });
  await expect(page.locator("header")).toBeVisible({ timeout: 30_000 });

  // Confirm we really landed in the console, not back on the marketing
  // landing page (which is what a failed silent-SSO looks like).
  await expect
    .poll(async () => (await page.locator("body").innerText()).length, { timeout: 30_000 })
    .toBeGreaterThan(0);

  await dismissOnboardingModals(page);
  // Persist for the next sign-in in this run - see prod-auth-state.ts.
  await captureAuthState(page).catch(() => {});
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
