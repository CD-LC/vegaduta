import { type Page, expect } from "@playwright/test";
import { verifyEmailViaMailinator } from "./mailinator";
import { APP_HOST_RE, AUTH_HOST_RE } from "./e2e-env";

// Real auth lives entirely on Keycloak (E2E_AUTH_URL) - see
// tests/auth.spec.ts's header comment for the full "never localhost"
// rationale. Field ids are pinned straight from the deployed theme
// (deploy/keycloak/themes/agentic-ai/login/login.ftl): #username, #password,
// #kc-login - stable across theme restyles since they're Keycloak's own
// FreeMarker template variables, not app-authored markup.

/**
 * Completes a login on Keycloak's hosted page, assuming the browser is
 * already there (redirected via a "Sign in" click, or via
 * `auth.login({ loginHint })`, which pre-fills #username).
 *
 * A BRAND NEW account lands on a "Email verification" required-action page
 * instead of redirecting straight back to the app - the realm enforces
 * verifyEmail (see mailinator.ts's header comment). `email` must be a
 * `@mailinator.com` address (test-tenant.ts's `freshIdentity` already
 * generates these) so `verifyEmailViaMailinator` can actually read the
 * verification mail; logins for an ALREADY-verified returning identity skip
 * that branch entirely since Keycloak won't show the required action twice.
 */
export async function completeKeycloakLogin(page: Page, email: string, password: string) {
  await page.waitForURL(AUTH_HOST_RE, { timeout: 30_000 });
  const usernameField = page.locator("#username");
  // loginHint pre-fills this; only type over it if it's empty or wrong,
  // so the "prompt:login forces a fresh auth" flow (see test-tenant.ts)
  // doesn't fight its own pre-fill.
  const currentValue = await usernameField.inputValue().catch(() => "");
  if (currentValue !== email) {
    await usernameField.fill(email);
  }
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();

  const appUrl = page.waitForURL(APP_HOST_RE, { timeout: 30_000 }).catch(() => null);
  const verifyHeading = page
    .getByRole("heading", { name: /email verification/i })
    .waitFor({ state: "visible", timeout: 30_000 })
    .catch(() => null);
  const outcome = await Promise.race([
    appUrl.then((v) => (v === undefined ? "app" : null)),
    verifyHeading.then((v) => (v === undefined ? "verify" : null)),
  ]);

  if (outcome === "verify") {
    if (!email.endsWith("@mailinator.com")) {
      throw new Error(
        `Landed on Keycloak's email-verification page for ${email}, which isn't a @mailinator.com ` +
          "address - this suite can't read that inbox to click the verification link."
      );
    }
    const inbox = email.split("@")[0];
    await verifyEmailViaMailinator(page, inbox);
    await page.waitForURL(APP_HOST_RE, { timeout: 30_000 });
    return;
  }

  await page.waitForURL(APP_HOST_RE, { timeout: 30_000 });
}

/** Asserts the current page is the authenticated app shell, not a login/landing page. */
export async function expectSignedIn(page: Page) {
  await expect(page).toHaveURL(APP_HOST_RE);
  await expect(page.locator("header")).toBeVisible({ timeout: 15_000 });
}
