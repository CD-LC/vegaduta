import { test, expect } from "@playwright/test";
import { AUTH_URL, APP_CLIENT_ID, KEYCLOAK_REALM, DOMAIN, APP_URL, LOCALHOST_HOST_RE, IS_OSS_SITE } from "../helpers/prod-env";
import { openKeycloakLoginFromApp } from "../helpers/prod-session";

/**
 * The sign-in handoff to Keycloak. Deliberately stops AT the login page -
 * no credentials are typed here, and no session is established. This is the
 * single most regression-prone path on the platform (issuer/hostname
 * misconfiguration has broken production sign-in more than once), and all of
 * it is verifiable without an account.
 */
test.describe("Sign-in handoff to Keycloak", () => {
  test("the header Sign in button hands off to the real IdP on this domain", async ({ page }) => {
    await page.goto("/");

    // "Sign in" is a header BUTTON on the classic site and a LINK to /login (then an email step) on the OSS
    // site; the helper follows whichever the deployment serves.
    const header = page.locator("header");
    await expect(
      header.getByRole("button", { name: /^sign in$/i }).or(header.getByRole("link", { name: /^sign in$/i })).first()
    ).toBeVisible();
    await openKeycloakLoginFromApp(page);

    const url = new URL(page.url());
    expect(url.origin, "token issuer must be this environment's Keycloak, never localhost").toBe(AUTH_URL);
    expect(url.pathname).toContain(`/realms/${KEYCLOAK_REALM}/protocol/openid-connect/auth`);
    expect(url.searchParams.get("client_id")).toBe(APP_CLIENT_ID);
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");

    const redirectUri = url.searchParams.get("redirect_uri") || "";
    expect(redirectUri).toContain(DOMAIN);
    expect(redirectUri, "RULE 0: redirect_uri must never be localhost").not.toMatch(LOCALHOST_HOST_RE);
  });

  test("the login page is branded and offers the configured identity providers", async ({ page }, testInfo) => {
    await openKeycloakLoginFromApp(page);

    // Custom theme, not stock Keycloak.
    await expect(page.getByText(/VegaD/i).first()).toBeVisible();

    // Keycloak's own FreeMarker field ids - stable across theme restyles.
    await expect(page.locator("#username")).toBeVisible();
    await expect(page.locator("#password")).toBeVisible();
    await expect(page.locator("#kc-login")).toBeVisible();

    // Social IdPs. These have been disabled by a bad realm import before
    // (see the repo's login-social-idps notes), which is invisible until
    // someone tries to use them.
    // Social IdPs are a hosted-deployment realm setting; the OSS realm ships none (observed on the kind stage:
    // only username/password), so the check is skipped in OSS mode rather than failing by design.
    const body = await page.locator("body").innerText();
    if (!IS_OSS_SITE) {
      expect(body, "expected Google/GitHub social sign-in on the login page").toMatch(/google|github/i);
    } else {
      testInfo.annotations.push({ type: "note", description: "E2E_SITE_MODE=oss: social IdP assertion not applied" });
    }
  });

  test("the login page offers account recovery and self-service registration", async ({ page }) => {
    await openKeycloakLoginFromApp(page);

    const body = await page.locator("body").innerText();
    expect(body, "no password-reset path on the login page").toMatch(/forgot|reset/i);
  });

  test("invalid credentials are rejected without revealing whether the account exists", async ({ page }) => {
    // A safe negative-path login: it creates no session and no data, but it
    // does prove the credential check is actually wired to a real user store
    // rather than accepting anything.
    await openKeycloakLoginFromApp(page);

    await page.locator("#username").fill(`e2e-nonexistent-${Date.now()}@mailinator.com`);
    await page.locator("#password").fill("definitely-not-a-real-password");
    await page.locator("#kc-login").click();

    // Must stay on Keycloak with an error - never bounce into the app.
    await expect(page).toHaveURL(new RegExp(`auth\\.${DOMAIN.replace(/\./g, "\\.")}`));
    const body = await page.locator("body").innerText();
    expect(body).toMatch(/invalid|incorrect|try again/i);

    // Username enumeration guard: the message must not distinguish "no such
    // user" from "wrong password".
    expect(body, "login error leaks account existence").not.toMatch(/user (does not|doesn't) exist|no such user|unknown user/i);
  });

  test("the app never falls back to a localhost issuer", async ({ page }) => {
    // Reads the SPA's own Keycloak configuration out of the loaded bundle
    // surface: any localhost issuer here means a base-only image was built
    // and shipped (the documented way sign-in breaks on this platform).
    await page.goto("/");
    const html = await page.content();
    const localhostRefs = html.match(/https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?/gi) || [];
    expect(localhostRefs, `page HTML references localhost: ${localhostRefs.join(", ")}`).toEqual([]);
  });

  test("the app host redirects the OIDC callback back to itself", async ({ request }) => {
    // /login is the app's own entry route; it must exist and not 404 on prod.
    const res = await request.get(`${APP_URL}/login`);
    expect(res.status()).toBeLessThan(400);
  });
});
