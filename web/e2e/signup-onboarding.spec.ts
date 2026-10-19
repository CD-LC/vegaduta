import { test, expect, type Page, type TestInfo } from "@playwright/test";

/**
 * Signup & onboarding E2E — the coverage gap that let a total registration
 * outage ship.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * Production once shipped a change that made EVERY registration fail:
 * Keycloak blanked email/firstName/lastName during onboarding, the user was
 * pinned on Keycloak's "Update Account Information" required-action screen,
 * and the post-onboarding reload bounced them back to the marketing landing
 * instead of the authenticated app. Nothing in web/e2e caught it, because
 * nothing in web/e2e ever signed anyone up: academy-platform, admin-panel,
 * axon-redesign, core-api and whatsapp-integration cover other surfaces, and
 * e2e/fixtures.js only ever logs in a pre-existing user (it provisions its
 * tenants by POSTing /api/signup directly, deliberately bypassing the UI).
 *
 * (Note for whoever reads this next: the SEPARATE repo-root suite at
 * ../../e2e does have signup specs — e2e/tests/signup.spec.ts and
 * e2e/tests-prod/signup.spec.ts. They are a different Playwright project
 * with their own config/helpers and are not run by `npx playwright test`
 * from web/. This file is web/e2e's own coverage, and the regression test
 * below asserts things neither of those does: no update-profile screen, no
 * bounce to the marketing landing, and the submitted first name surviving
 * into the authenticated shell.)
 *
 * WHAT IS COVERED
 *   1. Funnel navigation: landing → /try → /signup. READ-ONLY — safe to run
 *      anywhere, including production. Creates nothing.
 *   2. The /signup wizard asks for exactly the documented minimal fields
 *      (workspace, first, last, email, password, confirm) + the consent
 *      gate. A future change that adds friction fails this test.
 *   3. THE REGRESSION TEST: a full, real registration through to an
 *      authenticated shell. DESTRUCTIVE — creates a real tenant + Keycloak
 *      user. Triple-gated (see SAFETY below); skips by default.
 *   4. The social/identity-broker branch — documented, deliberately skipped
 *      (it needs a real Google/GitHub/Microsoft account; see the manual
 *      steps in that test's comment).
 *
 * SAFETY
 * ------
 * Test 3 writes real data. It runs ONLY when all of these hold:
 *   - E2E_ALLOW_DESTRUCTIVE=1              (explicit opt-in)
 *   - BASE_URL is set explicitly           (an explicit target, not the
 *                                           config's localhost default)
 *   - the effective baseURL agrees with BASE_URL (guards against pointing
 *     the gate at one host while Playwright drives another)
 *   - and, if that host is under one of the domains listed in
 *     E2E_PRODUCTION_DOMAINS (comma-separated; your PRODUCTION hosts),
 *     additionally E2E_ALLOW_PRODUCTION_SIGNUP=1
 * Anything missing → test.skip with a message naming what is missing.
 * Every run uses a timestamped identity, so repeat runs never collide.
 *
 * KNOWN: TEST 3 CANNOT CURRENTLY PASS END TO END ANYWHERE.
 * The realm sets verifyEmail=true (deploy/keycloak/agentic-ai-realm.json:6,
 * infra/keycloak/realm-export/vegaduta-ai-realm.json:6) and core stamps
 * VERIFY_EMAIL on every new user (core/admin/.../KeycloakAdminClient.java:252),
 * so a brand-new account is parked on Keycloak's email-verification screen
 * until it clicks a link in a real email. Where SMTP is not configured that
 * email never arrives and the flow stops there, permanently. This spec
 * DETECTS that state explicitly and fails with a message naming SMTP as the
 * cause, instead of a confusing 30s timeout. See
 * docs/E2E-SIGNUP-SPEC-RUNBOOK.md for the mail-readable-inbox workaround the
 * repo-root suite uses (@mailinator.com addresses).
 *
 * Run with:
 *   npx playwright test e2e/signup-onboarding.spec.ts                 (1,2 only)
 *   BASE_URL=https://app.staging.example.com E2E_ALLOW_DESTRUCTIVE=1 \
 *     npx playwright test e2e/signup-onboarding.spec.ts --project=chromium
 */

// ---------------------------------------------------------------------------
// Selector contracts (pinned to source, so a rename is a one-line fix here)
// ---------------------------------------------------------------------------

/** Logged-OUT chrome. AuthProvider.tsx:236 calls this the suite's login contract. */
const MARKETING_LOGIN_BUTTON = '[data-testid="login-landing-button"]';

/** Logged-IN chrome: AppShell.tsx's sidebar-footer sign-out button (aria-label = nav.signOut). */
const APP_SHELL_SIGN_OUT = '[aria-label="Sign out"]';

/** Keycloak's own field ids (stock FreeMarker vars, kept by deploy/keycloak/themes/agentic-ai). */
const KC_USERNAME = "#username";
const KC_PASSWORD = "#password";
const KC_SUBMIT = "#kc-login";

/** login-update-profile.ftl's form id — the screen the outage pinned users to. */
const KC_UPDATE_PROFILE_FORM = "#kc-update-profile-form";

// ---------------------------------------------------------------------------
// Destructive-run gate
// ---------------------------------------------------------------------------

/**
 * Hosts that are PRODUCTION: every domain in E2E_PRODUCTION_DOMAINS
 * (comma-separated, e.g. "example.com") and all of its subdomains.
 */
const PRODUCTION_DOMAINS = (process.env.E2E_PRODUCTION_DOMAINS || "")
  .split(",")
  .map((d) => d.trim().toLowerCase())
  .filter(Boolean);

function isProductionHost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  return PRODUCTION_DOMAINS.some((d) => host === d || host.endsWith(`.${d}`));
}

/**
 * Environment half of the gate, evaluated at MODULE SCOPE (collection time)
 * so the destructive describe can be skipped before Playwright even launches
 * a browser for it — a gate that only fires inside the test body has already
 * spun up a browser pointed at the target.
 *
 * Returns null when a real, tenant-creating signup is allowed, or a
 * human-readable reason to skip. Deliberately verbose: a silent skip on a
 * regression test is how the original outage stayed invisible, so the reason
 * always names the exact missing variable.
 */
function destructiveEnvBlockReason(): string | null {
  if (process.env.E2E_ALLOW_DESTRUCTIVE !== "1") {
    return (
      "E2E_ALLOW_DESTRUCTIVE=1 not set — this test creates a REAL tenant and a REAL " +
      "Keycloak user on whatever BASE_URL points at. Opt in explicitly to run it."
    );
  }

  const declared = process.env.BASE_URL;
  if (!declared) {
    return (
      "BASE_URL is not set — refusing to create an account against the config's default " +
      "target. Set BASE_URL explicitly to the environment you mean to write to."
    );
  }

  let declaredHost: string;
  try {
    declaredHost = new URL(declared).hostname;
  } catch {
    return `BASE_URL (${declared}) is not a valid absolute URL.`;
  }

  if (isProductionHost(declaredHost) && process.env.E2E_ALLOW_PRODUCTION_SIGNUP !== "1") {
    return (
      `${declaredHost} is PRODUCTION (listed in E2E_PRODUCTION_DOMAINS). ` +
      "A second, separate override is required: set E2E_ALLOW_PRODUCTION_SIGNUP=1 as well, and be ready " +
      "to clean the tenant up (see docs/E2E-SIGNUP-SPEC-RUNBOOK.md)."
    );
  }

  return null;
}

const DESTRUCTIVE_ENV_BLOCK = destructiveEnvBlockReason();

/**
 * Second half of the gate, inside the test: the env said "write to host X",
 * but the browser is actually driven by the project's resolved baseURL. If
 * those two disagree, the safety check was applied to the wrong host — refuse
 * rather than guess.
 */
function destructiveTargetMismatch(testInfo: TestInfo): string | null {
  const declared = process.env.BASE_URL;
  const effective = testInfo.project.use.baseURL;
  if (!declared || !effective) {
    return "Could not resolve both BASE_URL and the project baseURL — cannot verify the write target.";
  }
  try {
    const declaredHost = new URL(declared).hostname;
    const effectiveHost = new URL(effective).hostname;
    if (declaredHost !== effectiveHost) {
      return (
        `BASE_URL host (${declaredHost}) does not match the effective Playwright baseURL host ` +
        `(${effectiveHost}). Refusing to write: the safety gate and the browser disagree about the target.`
      );
    }
    if (isProductionHost(effectiveHost) && process.env.E2E_ALLOW_PRODUCTION_SIGNUP !== "1") {
      return `${effectiveHost} is PRODUCTION and E2E_ALLOW_PRODUCTION_SIGNUP=1 is not set.`;
    }
  } catch {
    return `BASE_URL (${declared}) or the project baseURL (${effective}) is not a valid absolute URL.`;
  }
  return null;
}

interface Identity {
  orgName: string;
  firstName: string;
  lastName: string;
  email: string;
  password: string;
}

/**
 * A never-reused identity. The local-part is timestamped + salted so repeat
 * runs (and parallel workers) can never collide on an existing Keycloak user.
 *
 * The domain is configurable because email verification is a hard gate on
 * this realm: E2E_SIGNUP_EMAIL_DOMAIN=mailinator.com gives an inbox the run
 * can actually read (that is what the repo-root suite does). The default is
 * example.test, which can never receive mail — correct for a run that only
 * wants to prove the flow reaches the verification wall.
 */
function freshIdentity(): Identity {
  const stamp = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const domain = process.env.E2E_SIGNUP_EMAIL_DOMAIN || "example.test";
  return {
    orgName: `E2E Signup ${stamp}`,
    firstName: "Playwright",
    lastName: "Regression",
    email: `e2e-signup-${stamp}@${domain}`,
    password: `E2eTest!${Math.random().toString(36).slice(2, 10)}Aa1`,
  };
}

// ---------------------------------------------------------------------------
// Shared page helpers
// ---------------------------------------------------------------------------

/**
 * /signup opens on IntroStep; the fields live one click away on DetailsForm
 * (app/signup/page.tsx: step "intro" → "details"). Idempotent — if the org
 * field is already there, this does nothing.
 */
async function openSignupDetailsStep(page: Page) {
  const orgField = page.getByLabel(/organization name/i);
  if (await orgField.count()) return;
  await page
    .getByRole("button", { name: /create free account|get started|register|continue/i })
    .first()
    .click();
  await expect(orgField).toBeVisible({ timeout: 20_000 });
}

type PostAuthState =
  | "app-shell"
  | "marketing-landing"
  | "keycloak-update-profile"
  | "keycloak-verify-email"
  | "keycloak-login"
  | "keycloak-error"
  | "unknown";

/**
 * Polls until the browser settles into one of the states that matter after
 * submitting Keycloak credentials. Written as a poll rather than a
 * Promise.race of waitFor()s so the caller gets a NAMED state (and therefore
 * a specific failure message) instead of a generic timeout — the whole point
 * of this file is that the original outage presented as a confusing hang.
 */
async function detectPostAuthState(page: Page, timeoutMs = 60_000): Promise<PostAuthState> {
  const deadline = Date.now() + timeoutMs;
  let last: PostAuthState = "unknown";

  while (Date.now() < deadline) {
    const url = page.url();
    const onKeycloak = /\/realms\//.test(url);

    if (onKeycloak) {
      if (await page.locator(KC_UPDATE_PROFILE_FORM).count()) return "keycloak-update-profile";
      const body = (await page.locator("body").innerText().catch(() => "")) || "";
      // login-update-profile.ftl renders Keycloak's stock loginProfileTitle
      // ("Update Account Information") in a themed <div>, not a heading — so
      // match text, not role.
      if (/update account information|update your (account )?profile/i.test(body)) {
        return "keycloak-update-profile";
      }
      // login-verify-email.ftl: stock emailVerifyTitle ("Email verification")
      // + emailVerifyInstruction1.
      if (/email verification|verify your email|verify.{0,20}email address/i.test(body)) {
        return "keycloak-verify-email";
      }
      if (await page.locator(KC_SUBMIT).count()) {
        last = "keycloak-login";
      } else if (/we are sorry|an error occurred|invalid username or password/i.test(body)) {
        return "keycloak-error";
      } else {
        last = "unknown";
      }
    } else {
      if (await page.locator(APP_SHELL_SIGN_OUT).count()) return "app-shell";
      if (await page.locator(MARKETING_LOGIN_BUTTON).count()) return "marketing-landing";
      last = "unknown";
    }

    await page.waitForTimeout(500);
  }

  return last;
}

/** Turns a settled state into the assertion message the outage deserved. */
function explainPostAuthState(state: PostAuthState, page: Page): string {
  switch (state) {
    case "keycloak-update-profile":
      return (
        "REGRESSION: Keycloak is showing the 'Update Account Information' required-action screen " +
        "after signup. This is the exact production outage this test exists for — it means the " +
        "account was created without a usable email/firstName/lastName, so Keycloak refuses to let " +
        `the user through until they retype them. URL: ${page.url()}`
      );
    case "keycloak-verify-email":
      return (
        "BLOCKED at Keycloak's email-verification screen. The realm sets verifyEmail=true " +
        "(deploy/keycloak/agentic-ai-realm.json:6) and core stamps VERIFY_EMAIL on every new user " +
        "(core/admin/.../KeycloakAdminClient.java:252), so signup cannot complete until a real " +
        "verification email is received and clicked. If this environment has no working Keycloak " +
        "SMTP configuration, that email will NEVER arrive and this flow can never complete here — " +
        "that is an environment/SMTP problem, not a bug in the signup UI. Workaround: set " +
        "E2E_SIGNUP_EMAIL_DOMAIN=mailinator.com and read the inbox (see the runbook). " +
        `URL: ${page.url()}`
      );
    case "marketing-landing":
      return (
        "REGRESSION: after signup the browser landed on the MARKETING landing page (the logged-out " +
        `chrome: ${MARKETING_LOGIN_BUTTON}) instead of the authenticated app. This is the second half ` +
        `of the production outage — the post-onboarding reload bounced the user out. URL: ${page.url()}`
      );
    case "keycloak-login":
      return (
        "Still on Keycloak's login form after submitting credentials — the just-created account " +
        `could not sign in with the password it was registered with. URL: ${page.url()}`
      );
    case "keycloak-error":
      return `Keycloak returned an error page after submitting credentials. URL: ${page.url()}`;
    default:
      return (
        "Could not identify the post-signup page: it is neither the authenticated app shell, the " +
        "marketing landing, nor any recognised Keycloak screen. Inspect the trace/screenshot. " +
        `URL: ${page.url()}`
      );
  }
}

// ---------------------------------------------------------------------------
// 1. Funnel navigation — READ-ONLY, safe on production
// ---------------------------------------------------------------------------

test.describe("Signup funnel — navigation (read-only, creates nothing)", () => {
  test("the landing page offers both funnel entry points and they point where they claim", async ({
    page,
  }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    await expect(page.locator(MARKETING_LOGIN_BUTTON).first()).toBeAttached({ timeout: 20_000 });

    // Counted in the DOM, not by visibility: the nav's /signup CTA is hidden
    // on compact viewports (DemoLanding's isCompactViewport branch), and this
    // spec also runs under the mobile-chrome / mobile-safari projects.
    const tryLinks = page.locator('a[href^="/try"]');
    const signupLinks = page.locator('a[href^="/signup"]');

    expect(await tryLinks.count(), "landing has no /try entry point").toBeGreaterThan(0);
    expect(await signupLinks.count(), "landing has no /signup entry point").toBeGreaterThan(0);

    // useJourney() (app/lib/journey.ts) may decorate signup with the demo
    // token (`/signup?demo=<token>`) for a browser that already has a
    // sandbox; anything else on a "signup" CTA is a mis-wired funnel.
    for (const href of await signupLinks.evaluateAll((els) =>
      els.map((el) => el.getAttribute("href") || "")
    )) {
      expect(href, `signup CTA points somewhere unexpected: ${href}`).toMatch(/^\/signup(\?demo=.+)?$/);
    }
  });

  test("both funnel routes resolve (no 404, no client-side crash)", async ({ page, request }) => {
    for (const path of ["/try", "/signup"]) {
      const res = await request.get(path);
      expect(res.status(), `${path} did not resolve`).toBeLessThan(400);
    }

    // /try is safe to open: TrySandbox.boot() with no stored token stops at
    // the persona chooser and provisions NOTHING — the sandbox tenant is only
    // created by begin(), i.e. after a persona chip is tapped
    // (app/components/try/TrySandbox.tsx: boot() → setPhase("choosing")).
    // This test never taps one.
    await page.goto("/try", { waitUntil: "domcontentloaded" });
    expect(new URL(page.url()).pathname).toBe("/try");
    await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
    // Nothing is asked for on the sandbox entry — no credentials, no form.
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
  });

  test("a landing Try CTA navigates to /try", async ({ page }) => {
    await page.goto("/", { waitUntil: "domcontentloaded" });
    const tryLink = page.locator('a[href^="/try"]:visible').first();
    test.skip((await tryLink.count()) === 0, "no visible /try CTA at this viewport");
    await tryLink.click();
    await page.waitForURL(/\/try(\?|$)/, { timeout: 30_000 });
    expect(new URL(page.url()).pathname).toBe("/try");
  });
});

// ---------------------------------------------------------------------------
// 2. The wizard asks for exactly the documented minimal fields
// ---------------------------------------------------------------------------

test.describe("Signup wizard — the fields it asks for (read-only, creates nothing)", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/signup", { waitUntil: "domcontentloaded" });
    await openSignupDetailsStep(page);
  });

  test("collects exactly workspace, first, last, email, password, confirm — and nothing more", async ({
    page,
  }) => {
    await expect(page.getByLabel(/organization name/i)).toBeVisible();
    await expect(page.getByLabel(/first name/i)).toBeVisible();
    await expect(page.getByLabel(/last name/i)).toBeVisible();
    await expect(page.getByLabel(/work email|email/i).first()).toBeVisible();
    await expect(page.getByLabel(/^password$/i)).toBeVisible();
    await expect(page.getByLabel(/confirm password/i)).toBeVisible();

    // The "no added friction" ratchet: app/signup/page.tsx's `fields` array is
    // exactly six entries. If someone adds a phone number, a company size or a
    // "how did you hear about us", this fails and they have to argue for it.
    const nonCheckboxInputs = page.locator('form input:not([type="checkbox"]):not([type="hidden"])');
    expect(
      await nonCheckboxInputs.count(),
      "the signup form no longer asks for exactly 6 fields — friction was added (or a field was dropped)"
    ).toBe(6);
  });

  test("has a required consent gate and exactly one optional opt-in", async ({ page }) => {
    const consent = page.getByLabel(/i agree to the/i);
    await expect(consent).toBeVisible();
    await expect(consent).not.toBeChecked();

    const checkboxes = page.locator('form input[type="checkbox"]');
    expect(
      await checkboxes.count(),
      "expected exactly 2 checkboxes: the required Terms/Privacy consent and the optional news opt-in"
    ).toBe(2);

    // Terms + Privacy must be reachable from the consent line itself.
    await expect(page.locator('a[href="/terms"]')).toHaveCount(1);
    await expect(page.locator('a[href="/privacy"]')).toHaveCount(1);
  });

  test("refuses to submit without consent — and therefore creates nothing", async ({ page }) => {
    const identity = freshIdentity();
    await page.getByLabel(/organization name/i).fill(identity.orgName);
    await page.getByLabel(/first name/i).fill(identity.firstName);
    await page.getByLabel(/last name/i).fill(identity.lastName);
    await page.getByLabel(/work email|email/i).first().fill(identity.email);
    await page.getByLabel(/^password$/i).fill(identity.password);
    await page.getByLabel(/confirm password/i).fill(identity.password);

    // Consent deliberately UNCHECKED — this is what keeps the test
    // non-destructive. handleSubmit() returns before api.signup() is reached
    // (app/signup/page.tsx: `if (!agreedToTerms) { setError(...); return; }`).
    const urlBefore = page.url();
    await page.getByRole("button", { name: /create my workspace|create|submit/i }).first().click();

    await expect(
      page,
      "signup proceeded despite unaccepted Terms — a real account may have been created"
    ).toHaveURL(urlBefore);
    await expect(page.getByText(/agree to the terms|please agree/i).first()).toBeVisible({
      timeout: 10_000,
    });
    // Still on the form, nothing provisioned.
    await expect(page.getByLabel(/organization name/i)).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// 3. THE REGRESSION TEST — destructive, triple-gated, skipped by default
// ---------------------------------------------------------------------------

test.describe("Signup → onboarding → authenticated app (DESTRUCTIVE — creates a real tenant)", () => {
  // Skipped at COLLECTION time when the env gate is not satisfied, so the
  // default `npx playwright test` never even opens a browser against the
  // target, let alone types into a signup form.
  test.skip(DESTRUCTIVE_ENV_BLOCK !== null, DESTRUCTIVE_ENV_BLOCK ?? "");

  test("a fresh registration reaches an authenticated dashboard, with no update-profile screen and no bounce to the landing", async ({
    page,
  }, testInfo) => {
    const mismatch = destructiveTargetMismatch(testInfo);
    testInfo.skip(mismatch !== null, mismatch || "");
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "provisions a real tenant — run on one project only (--project=chromium)"
    );
    test.setTimeout(10 * 60 * 1000);

    const identity = freshIdentity();
    testInfo.annotations.push({
      type: "data-left-behind",
      description:
        `Provisions a REAL tenant "${identity.orgName}" with admin ${identity.email} on ` +
        `${testInfo.project.use.baseURL}. Clean up manually — see docs/E2E-SIGNUP-SPEC-RUNBOOK.md.`,
    });

    // --- register -----------------------------------------------------------
    await page.goto("/signup", { waitUntil: "domcontentloaded" });
    await openSignupDetailsStep(page);

    await page.getByLabel(/organization name/i).fill(identity.orgName);
    await page.getByLabel(/first name/i).fill(identity.firstName);
    await page.getByLabel(/last name/i).fill(identity.lastName);
    await page.getByLabel(/work email|email/i).first().fill(identity.email);
    await page.getByLabel(/^password$/i).fill(identity.password);
    await page.getByLabel(/confirm password/i).fill(identity.password);
    await page.getByLabel(/i agree to the/i).check();
    await page.getByRole("button", { name: /create my workspace|create|submit/i }).first().click();

    // FreeWorkspaceReadyStep keys its success box off the org name — proof
    // POST /api/signup actually provisioned before we chase the hand-off.
    // Generous: this creates a Keycloak user AND a tenant schema.
    await expect(
      page.getByText(identity.orgName),
      "POST /api/signup did not report a provisioned workspace"
    ).toBeVisible({ timeout: 120_000 });

    // --- hand off to Keycloak ----------------------------------------------
    // FREE tier has no checkout; the wizard's explicit button calls
    // auth.login({ loginHint, prompt: "login" }) (app/signup/page.tsx's
    // continueToSignIn — no longer a silent 1.2s timer).
    await page.getByRole("button", { name: /continue to sign-in|continue/i }).first().click();
    await page.waitForURL(/\/realms\//, { timeout: 60_000 });

    // loginHint pre-fills #username; only type over it if it disagrees.
    const usernameField = page.locator(KC_USERNAME);
    await expect(usernameField).toBeVisible({ timeout: 30_000 });
    if ((await usernameField.inputValue().catch(() => "")) !== identity.email) {
      await usernameField.fill(identity.email);
    }
    await page.locator(KC_PASSWORD).fill(identity.password);
    await page.locator(KC_SUBMIT).click();

    // --- the assertions the outage would have failed ------------------------
    const state = await detectPostAuthState(page);
    expect(state, explainPostAuthState(state, page)).toBe("app-shell");

    // Explicitly, not just implicitly: neither outage symptom is present.
    await expect(
      page.locator(KC_UPDATE_PROFILE_FORM),
      "Keycloak's Update Account Information form is on screen"
    ).toHaveCount(0);
    await expect(
      page.locator(MARKETING_LOGIN_BUTTON),
      "logged-out marketing chrome is on screen — the user was bounced out of the app"
    ).toHaveCount(0);
    await expect(page.locator(APP_SHELL_SIGN_OUT).first()).toBeVisible({ timeout: 30_000 });

    // The signup admin is a tenant-admin, so app/page.tsx redirects to /admin.
    // Both /admin and / are authenticated destinations; what must never happen
    // is /signup or a Keycloak URL.
    const landedPath = new URL(page.url()).pathname;
    expect(landedPath, `landed on ${landedPath} instead of an authenticated route`).not.toMatch(
      /^\/signup/
    );
    expect(page.url()).not.toMatch(/\/realms\//);

    // Did Keycloak keep email/firstName/lastName?
    //
    // We cannot read the token's claims: the keycloak-js instance is
    // module-scoped inside web/app/lib/auth.ts (`kc`) and is never put on
    // window, and reading the account via Keycloak's Admin API would need
    // admin credentials this test deliberately does not hold. So we assert
    // the USER-VISIBLE PROXY instead: AppShell's sidebar footer renders
    // `firstName || username || "—"` (AppShell.tsx ~:886/:895). If Keycloak
    // had blanked firstName, that slot would fall back to the username or the
    // em-dash. Seeing the exact first name typed into the form is therefore
    // evidence the given name survived registration → onboarding → token.
    // (Accepting the email as an alternative would defeat the point, so it is
    // NOT accepted here; a first-name-only assertion is the tighter one.)
    await expect(
      page.getByText(identity.firstName, { exact: false }).first(),
      "the first name submitted at signup is not visible in the authenticated shell — " +
        "Keycloak may have dropped firstName during onboarding (the original outage)"
    ).toBeVisible({ timeout: 30_000 });

    // --- and it survives a reload ------------------------------------------
    // The outage's second half: the post-onboarding reload bounced the user
    // back to the marketing landing.
    await page.reload({ waitUntil: "domcontentloaded" });
    const afterReload = await detectPostAuthState(page);
    expect(
      afterReload,
      `after a reload: ${explainPostAuthState(afterReload, page)}`
    ).toBe("app-shell");
  });
});

// ---------------------------------------------------------------------------
// 4. Social / identity-broker onboarding — documented, deliberately skipped
// ---------------------------------------------------------------------------

test.describe("Signup via social identity broker (Google / GitHub / Microsoft)", () => {
  /**
   * NOT AUTOMATED, ON PURPOSE — and written as a visible skip rather than
   * omitted, so the gap shows up in the report instead of looking like
   * coverage that exists.
   *
   * Why it cannot be automated here: the buttons (SocialLoginRow.tsx) do not
   * implement OAuth themselves — they hand Keycloak an `idpHint` and Keycloak
   * redirects to the real Google/GitHub/Microsoft consent screen. Completing
   * that needs a real third-party account with a real password (and, in
   * practice, a 2FA challenge and bot-detection that a headless browser is
   * expected to fail). Storing such credentials in this repo or in CI is not
   * something this suite will do.
   *
   * ALSO NOTE: the buttons are rendered only for aliases that
   * GET /api/auth/social-providers reports as enabled on the realm
   * (SocialAuthConfigController) — on an environment with no brokers
   * configured, the row renders nothing at all and there is nothing to click.
   *
   * MANUAL STEPS (run these by hand before shipping any change to
   * SocialLoginRow.tsx, auth.ts's login(), or the realm's identity-provider
   * config — this is the branch the update-profile outage hit hardest,
   * because a broker-created account is exactly the case where Keycloak asks
   * for a profile it did not receive):
   *
   *   1. Confirm the environment actually offers a broker:
   *        curl -s https://api.<env>/api/auth/social-providers
   *      Expect a JSON list containing e.g. "google". Empty list → nothing to
   *      test; stop here and say so.
   *   2. Open https://app.<env>/signup in a clean profile (no Keycloak SSO
   *      cookie, no Google session). Verify the "Or connect via" row shows
   *      exactly the aliases from step 1 — no more.
   *   3. Click Google. Expect: app → auth.<env>/realms/agentic-ai/... →
   *      accounts.google.com. Sign in with a throwaway Google account.
   *   4. THE ASSERTION THAT MATTERS: you must land on the authenticated app
   *      shell (sidebar with a "Sign out" control). You must NOT be parked on
   *      Keycloak's "Update Account Information" screen asking for email /
   *      first name / last name — Google supplies all three, so being asked
   *      for them means the broker's attribute mappers are not populating the
   *      user (this is the social-branch form of the outage).
   *   5. Reload the page. You must stay in the app, not bounce to the
   *      marketing landing.
   *   6. Open the sidebar footer: the name shown must be the Google account's
   *      given name, not the raw email and not "—".
   *   7. Sign out, then sign in again with the same Google account. The
   *      second pass must NOT show the update-profile screen either (a
   *      required action that never clears is a slow-burn version of the same
   *      bug).
   *   8. Record the result in docs/E2E-SIGNUP-SPEC-RUNBOOK.md with the date
   *      and the environment.
   */
  test.skip("completes onboarding via Google and never shows the update-profile screen (MANUAL — see comment)", async () => {
    // Intentionally empty: the manual procedure lives in the comment above.
  });
});
