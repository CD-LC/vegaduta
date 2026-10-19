// Business case: a brand-new visitor creates a workspace and logs in.
// Covers both the UI-driven signup form (Phase 0 Stage 5 + Billing Stage 3)
// and the resulting Keycloak login round-trip, without ever completing a
// real Razorpay payment - confirmed (STAGE-F1-VERIFICATION.md) that the
// tenant's schema is provisioned and ACTIVE immediately on POST /api/signup,
// independent of checkout completion.
const { test, expect, signupTenant, loginViaKeycloak, uniqueSuffix } = require("../fixtures");

test.describe("Signup and login", () => {
  test("signup form provisions a workspace and offers the payment step", async ({ page }) => {
    const suffix = uniqueSuffix();
    await page.goto("/signup");

    await page.fill('input[placeholder="Acme Corp"]', `E2E UI Org ${suffix}`);
    await page.fill('input[placeholder="Ada"]', "Grace");
    await page.fill('input[placeholder="Lovelace"]', "Hopper");
    await page.fill('input[placeholder="you@acme.example"]', `e2e.ui.${suffix}@example.test`);
    await page.fill('input[type="password"] >> nth=0', "UiTestPassw0rd!");
    await page.fill('input[type="password"] >> nth=1', "UiTestPassw0rd!");
    await page.click('button:has-text("Create workspace")');

    // Lands on the "Almost there" screen (Billing Stage 3) rather than an
    // immediate success screen - this is the actual current behavior, not a
    // bug: the trial starts once Stage 4's webhook confirms the
    // subscription, and this screen's Continue-to-payment button is how
    // that gets kicked off. We don't complete a real payment here.
    await expect(page.getByText("Almost there")).toBeVisible();
    await expect(page.getByText(/is provisioned and ready/)).toBeVisible();
    await expect(page.getByRole("button", { name: /Continue to payment/i })).toBeVisible();
  });

  test("duplicate organization name is rejected", async ({ request }) => {
    const tenant = await signupTenant(request);
    await expect(
      signupTenant(request, { orgName: tenant.orgName, adminEmail: `different.${tenant.suffix}@example.test` })
    ).rejects.toThrow(/409|already|exists|taken/i);
  });

  test("duplicate admin email is rejected", async ({ request }) => {
    const tenant = await signupTenant(request);
    await expect(
      signupTenant(request, { adminEmail: tenant.adminEmail, orgName: `Different Org ${tenant.suffix}` })
    ).rejects.toThrow(/409|already|exists|taken/i);
  });

  test("@mvp-acceptance the signup admin logs in and lands on the admin console", async ({ page, request }) => {
    const tenant = await signupTenant(request);
    await loginViaKeycloak(page, tenant);
    await expect(page).toHaveURL(/\/admin/);
    await expect(page.getByRole("heading", { name: "Admin Control Panel" })).toBeVisible();
    await expect(page.getByText(tenant.adminEmail)).toBeVisible();
  });

  // Visual redesign: AuthProvider's Keycloak init moved from onLoad:
  // "login-required" (auto-redirect straight to Keycloak's hosted login
  // form) to "check-sso" (no forced redirect - renders the marketing/
  // landing experience instead), so logging out now lands back on this
  // app's own branded page, not directly on Keycloak's form. Reaching
  // Keycloak from there is one deliberate click away (see loginViaKeycloak
  // in fixtures.js).
  //
  // Phase 2 replaced the plain "Sign in to your workspace" card
  // (LoginLanding.js) with MarketingHome.js's cinematic landing page.
  // Phase 3 replaced that in turn with GenesisExperience.js - a seven-act
  // scroll narrative rather than a conventional page, so there's no hero
  // heading visible without scrolling. A real (visually-hidden) <h1> exists
  // from the first frame specifically so this kind of check - and screen
  // readers/SEO - has something stable to assert against regardless of
  // scroll position; see genesis.css's .gx-sr-only. The login-landing-button
  // testid moved from MarketingNav.js into GenesisNav.js, same contract.
  test("logging out returns to the branded landing page", async ({ page, request }) => {
    const tenant = await signupTenant(request);
    await loginViaKeycloak(page, tenant);
    await page.click('button:has-text("Log out")');
    await page.waitForURL((url) => url.pathname === "/" || url.pathname === "", { timeout: 30_000 });
    await expect(page.getByTestId("login-landing-button")).toBeVisible();
    await expect(page.getByRole("heading", { name: /agentic ai/i })).toBeAttached();
  });
});
