import { test, expect } from "@playwright/test";
import { AUTH_BASE_URL, AUTH_HOST_RE } from "../helpers/e2e-env";

// Real auth lives entirely on Keycloak (E2E_AUTH_URL), driven via OIDC +
// PKCE from keycloak-js (web/app/lib/auth.ts) - clicking "Sign in" redirects
// the whole browser tab there, it isn't an in-app form. We deliberately do
// NOT attempt a real login (no test credentials belong in this suite); we
// only verify the handoff lands on the right, correctly-branded, non-local
// page. On a deployed environment no browser-facing URL may ever
// point at localhost - if this test ever sees localhost in the resulting
// URL, that's a real build-config regression (NEXT_PUBLIC_KEYCLOAK_URL baked
// wrong at build time), not a flaky test.
//
// The footer's "Company" column (which used to hold a "Sign in" button) was
// removed in the footer redesign - the footer now only has Product /
// Resources / Legal columns plus a legal-entity bottom bar. Sign-in only
// lives in the header now, and the header's "Sign in" button is
// `hidden sm:inline-flex` (desktop only), so this whole describe block pins
// an explicit desktop viewport rather than relying on Playwright project
// order/rotation.

test.describe("Sign in handoff to Keycloak", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test("clicking Sign in lands on the real Keycloak login page", async ({ page }) => {
    await page.goto("/");

    await page.locator("header").getByRole("button", { name: "Sign in" }).click();
    await page.waitForURL(AUTH_HOST_RE, { timeout: 30_000 });

    const url = page.url();
    expect(url).not.toContain("localhost");
    expect(url).not.toContain("127.0.0.1");
    expect(new URL(url).hostname).toBe(new URL(AUTH_BASE_URL).hostname);
  });

  test("Keycloak login page is branded with the VegaDūta wordmark", async ({ page }) => {
    await page.goto("/");
    await page.locator("header").getByRole("button", { name: "Sign in" }).click();
    await page.waitForURL(AUTH_HOST_RE, { timeout: 30_000 });

    await expect(page.getByText("VegaDūta").first()).toBeVisible();
  });

  test("Keycloak login page offers Google, GitHub and Microsoft social sign-in", async ({ page }) => {
    await page.goto("/");
    await page.locator("header").getByRole("button", { name: "Sign in" }).click();
    await page.waitForURL(AUTH_HOST_RE, { timeout: 30_000 });

    // Rendered by the custom "agentic-ai" login theme (login.ftl) as one
    // <a> per enabled realm identity provider. All three IdPs (google,
    // github, microsoft) are enabled in the deployed realm.
    await expect(page.getByRole("link", { name: /google/i })).toBeVisible();
    await expect(page.getByRole("link", { name: /github/i })).toBeVisible();
    await expect(page.getByRole("link", { name: /microsoft/i })).toBeVisible();
  });
});
