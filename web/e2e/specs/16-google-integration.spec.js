// Business case: the Google Workspace integration (#173) - one "Connect
// Google" OAuth flow (GoogleOAuthController) giving every agent Drive/Gmail/
// Calendar/Sheets tools, rather than a pasted-credential form like Twilio/
// MCP. Admin-only page (admin/integrations/page.js redirects non-admins to
// "/"), same guard pattern as the Issues portal.
//
// A real end-to-end connect can't be automated here - it requires a genuine
// browser round-trip through accounts.google.com and a real Google account,
// which is exactly the kind of external dependency this suite avoids (see
// e2e/README.md). What's fully testable without that: the admin gate, the
// disconnected empty state, and GoogleIntegrationPanel.js's own handling of
// the ?google=connected|error query param GoogleOAuthController's redirect
// lands with - reached here by navigating directly to that URL, the same
// way the browser would land on it after a real Google redirect.
const { test, expect, signupTenant, keycloakPasswordGrantToken, API_BASE } = require("../fixtures");

test.describe("Google Workspace integration", () => {
  test("a non-admin console user is redirected away from /admin/integrations", async ({ consolePage: page }) => {
    await page.goto("/admin/integrations");
    await expect(page).toHaveURL((url) => url.pathname === "/" || url.pathname === "", { timeout: 15_000 });
  });

  test("a fresh tenant shows the disconnected empty state with a Connect Google button", async ({ adminPage: page }) => {
    await page.goto("/admin/integrations");
    await expect(page.getByRole("heading", { name: "Google Workspace" })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("No Google account connected yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "Connect Google" })).toBeVisible();
  });

  test("landing back with ?google=connected shows a success banner and strips the param", async ({ adminPage: page }) => {
    await page.goto("/admin/integrations?google=connected");
    // ShadCN redesign (2026-07-26): the status banner is now a real ui/alert
    // Alert (role="alert"), not a `.ax-kb-success` div - see
    // GoogleIntegrationPanel.tsx.
    await expect(page.getByRole("alert")).toContainText("Google account connected.", { timeout: 15_000 });
    await expect(page).toHaveURL(/\/admin\/integrations$/);
  });

  test("landing back with ?google=error&reason=consent_declined shows the cancelled-specific message", async ({ adminPage: page }) => {
    await page.goto("/admin/integrations?google=error&reason=consent_declined");
    await expect(page.getByRole("alert")).toContainText("Google connection was cancelled.", { timeout: 15_000 });
    await expect(page).toHaveURL(/\/admin\/integrations$/);
  });

  test("landing back with a bare ?google=error shows the generic failure message", async ({ adminPage: page }) => {
    await page.goto("/admin/integrations?google=error");
    await expect(page.getByRole("alert")).toContainText("Could not connect Google - please try again.", { timeout: 15_000 });
  });
});

test.describe("Google Workspace integration (API-level)", () => {
  test("Google endpoints reject requests with no bearer token", async ({ request }) => {
    for (const path of ["/api/channels/google/authorize-url", "/api/channels/google/status"]) {
      const res = await request.get(`${API_BASE}${path}`);
      expect(res.status(), path).toBe(401);
    }
  });

  test("a fresh tenant has no Google connection (204/empty status)", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.get(`${API_BASE}/api/channels/google/status`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect([200, 204]).toContain(res.status());
    if (res.status() === 200) {
      expect(await res.json()).toBeFalsy();
    }
  });

  test("the authorize-url endpoint returns a real Google OAuth consent URL", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.get(`${API_BASE}/api/channels/google/authorize-url`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    // Only succeeds if this deployment has Google OAuth client credentials
    // configured (GoogleOAuthController) - skip rather than fail if it
    // genuinely isn't set up on this stack, same "opt-in when the real
    // credential isn't present" posture as the Twilio/Anthropic specs.
    test.skip(res.status() >= 500, "Google OAuth isn't configured on this deployment.");
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.url).toContain("accounts.google.com");
  });
});
