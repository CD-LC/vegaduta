import { test, expect } from "@playwright/test";
import { GATED_CONSOLE_ROUTES, CONSOLE_ONLY_MARKERS } from "../helpers/prod-routes";
import { ADMIN_URL, AUTH_URL, ADMIN_CLIENT_ID, KEYCLOAK_REALM, DOMAIN } from "../helpers/prod-env";

/**
 * Auth gating, asserted the way this app actually behaves on production.
 *
 * IMPORTANT (verified against the deployed app, 2026-08-16): a logged-out
 * visit to a console route such as /agents does NOT 401 and does NOT
 * redirect. AuthProvider renders GenesisExperience - the marketing landing -
 * in place of the console, leaving the URL as /agents. So "did the gate
 * hold?" cannot be answered by a status code or a URL check; the only
 * meaningful assertion is that NO tenant data or console chrome reached the
 * DOM. That is what these tests check.
 */
test.describe("Console routes are gated for logged-out visitors", () => {
  for (const route of GATED_CONSOLE_ROUTES) {
    test(`${route} leaks no console content without a session`, async ({ page }) => {
      await page.goto(route, { waitUntil: "domcontentloaded" });
      // Give the client-side auth lifecycle time to resolve and swap in
      // whatever it decides to render.
      await page.waitForLoadState("networkidle", { timeout: 10_000 }).catch(() => {});

      const body = await page.locator("body").innerText();

      for (const marker of CONSOLE_ONLY_MARKERS) {
        expect(
          marker.test(body),
          `${route} rendered console-only content matching ${marker} to a logged-out visitor`
        ).toBe(false);
      }

      // The unauthenticated fallback must still be a real page, not a crash
      // or an endless spinner.
      await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
      await expect(page.getByRole("heading", { level: 1 }).first()).toBeVisible({ timeout: 20_000 });

      // A logged-out visitor must always have a way in.
      const signIn = page.getByRole("button", { name: /sign in/i }).or(page.getByRole("link", { name: /sign in/i }));
      await expect(signIn.first()).toBeVisible();
    });
  }
});

test.describe("Platform-admin console is gated at the identity provider", () => {
  test("admin host redirects an anonymous visitor to Keycloak", async ({ page }) => {
    await page.goto(ADMIN_URL, { waitUntil: "domcontentloaded" });

    // Unlike the tenant app, platform-admin has no logged-out render at all - it
    // bounces straight to the IdP.
    await page.waitForURL(new RegExp(`auth\\.${DOMAIN.replace(/\./g, "\\.")}`), { timeout: 45_000 });

    const url = new URL(page.url());
    expect(url.origin).toBe(AUTH_URL);
    expect(url.pathname).toContain(`/realms/${KEYCLOAK_REALM}/protocol/openid-connect/auth`);

    // The admin app must use its OWN client, not the tenant app's - sharing
    // one client would mean tenant-scoped tokens could address the admin SPA.
    expect(url.searchParams.get("client_id")).toBe(ADMIN_CLIENT_ID);

    // PKCE must be in play for a public SPA client.
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("code_challenge")).toBeTruthy();

    // The redirect target must come back to admin on this environment.
    const redirectUri = url.searchParams.get("redirect_uri") || "";
    expect(redirectUri).toContain(`admin.${DOMAIN}`);
    expect(redirectUri).not.toMatch(/localhost|127\.0\.0\.1/);
  });

  test("no admin data renders before authentication", async ({ page }) => {
    await page.goto(ADMIN_URL, { waitUntil: "domcontentloaded" });
    await page.waitForURL(new RegExp(`auth\\.${DOMAIN.replace(/\./g, "\\.")}`), { timeout: 45_000 });

    const body = await page.locator("body").innerText();
    for (const marker of [/Tenant Roster/i, /Platform spend/i, /LLM Providers/i]) {
      expect(marker.test(body), `admin data matching ${marker} rendered pre-auth`).toBe(false);
    }
  });
});

test.describe("Redirects", () => {
  test("/explore redirects to the landing page", async ({ page }) => {
    await page.goto("/explore", { waitUntil: "domcontentloaded" });
    await expect(page).toHaveURL(/\/$/);
  });
});
