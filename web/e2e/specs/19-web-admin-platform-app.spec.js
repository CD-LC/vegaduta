// Business case: the separate web-admin app (port 3001) - a cross-tenant
// platform-admin console (Ecosystem Maintenance / Subscription Plans /
// Observability) with no coverage anywhere in this suite until now, since it
// lives in its own Next.js app (web-admin/) with its own Keycloak client
// (agentic-ai-platform-admin) and its own AuthProvider.js.
//
// Two real constraints shape what's automatable here:
//
//  1. No baseURL is configured for this app in playwright.config.js (that
//     config's single project points at the tenant console on :3000) - every
//     test below uses an absolute URL via E2E_ADMIN_BASE_URL (default
//     http://localhost:3001) rather than relying on the configured baseURL.
//  2. The platform-admin realm role is NEVER granted through any API this
//     suite can drive - it's a manual, one-time grant a human makes in the
//     Keycloak admin console (see web-admin/app/lib/auth.js's
//     PLATFORM_ADMIN_ROLE comment). So only the "denied" path (a real,
//     freshly-signed-up tenant-admin who does NOT hold that role) is
//     reachable with a fully automated fixture; the "ready" (actual platform
//     admin content) path is opt-in, gated behind E2E_PLATFORM_ADMIN_EMAIL/
//     E2E_PLATFORM_ADMIN_PASSWORD - same "skip unless a real credential is
//     provided" posture as E2E_ANTHROPIC_API_KEY/E2E_TWILIO_* elsewhere in
//     this suite. See e2e/README.md's opt-in specs table.
const { test, expect, signupTenant, keycloakPasswordGrantToken, API_BASE } = require("../fixtures");

const ADMIN_BASE_URL = process.env.E2E_ADMIN_BASE_URL || "http://localhost:3001";

// web-admin's auth.js uses onLoad: "login-required" (not the tenant
// console's "check-sso") - an unauthenticated visit redirects straight to
// Keycloak's hosted login as a real navigation, with no branded landing page
// or "Log in" button to click through first (see that file's own comment).
async function loginToWebAdmin(page, { email, password }) {
  await page.goto(ADMIN_BASE_URL, { timeout: 15_000 });
  await page.waitForURL(/\/realms\//, { timeout: 30_000 });
  await page.fill("#username", email, { timeout: 15_000 });
  await page.fill("#password", password, { timeout: 15_000 });
  await page.click("#kc-login", { timeout: 15_000 });
  await page.waitForURL((url) => !url.href.includes("/realms/"), { timeout: 30_000 });
}

test.describe("web-admin: access control", () => {
  test("@mvp-acceptance a real tenant-admin without the platform-admin role sees the Access restricted screen, not a crash or silent redirect", async ({ page, request }) => {
    const tenant = await signupTenant(request);
    await loginToWebAdmin(page, { email: tenant.adminEmail, password: tenant.adminPassword });

    await expect(page.getByRole("heading", { name: "Access restricted" })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(tenant.adminEmail)).toBeVisible();
    await expect(page.getByText(/doesn.t hold the platform-admin role/)).toBeVisible();

    // Sign out is still reachable from the denied screen - not a dead end.
    await page.getByRole("button", { name: "Sign out" }).click();
    await page.waitForURL(/\/realms\//, { timeout: 20_000 });
  });
});

test.describe("web-admin: platform-admin content (opt-in)", () => {
  test.beforeEach(() => {
    test.skip(
      !process.env.E2E_PLATFORM_ADMIN_EMAIL || !process.env.E2E_PLATFORM_ADMIN_PASSWORD,
      "Set E2E_PLATFORM_ADMIN_EMAIL/E2E_PLATFORM_ADMIN_PASSWORD (a Keycloak account manually granted the " +
        "platform-admin realm role) to run this test."
    );
  });

  test("a real platform admin lands on Ecosystem Maintenance and can reach the other two sections", async ({ page }) => {
    await loginToWebAdmin(page, {
      email: process.env.E2E_PLATFORM_ADMIN_EMAIL,
      password: process.env.E2E_PLATFORM_ADMIN_PASSWORD,
    });

    await expect(page.locator(".ax-topbar-org")).toHaveText("Ecosystem Maintenance", { timeout: 20_000 });
    await expect(page.getByRole("heading", { name: "LLM Task Routing" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Platform-Hosted LLM Providers" })).toBeVisible();
    await expect(page.getByRole("heading", { name: /Cross-Tenant Roster/ })).toBeVisible();

    await page.getByRole("link", { name: "Subscription Plans" }).click();
    await expect(page.locator(".ax-topbar-org")).toHaveText("Subscription Plans", { timeout: 15_000 });

    await page.getByRole("link", { name: "Observability" }).click();
    await expect(page).toHaveURL(/\/observability$/, { timeout: 15_000 });

    await page.getByRole("link", { name: "Ecosystem" }).click();
    await expect(page).toHaveURL(new RegExp(`${ADMIN_BASE_URL.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/?$`));
  });
});

test.describe("web-admin API (platform-admin only endpoints)", () => {
  test("platform endpoints reject requests with no bearer token", async ({ request }) => {
    for (const path of ["/api/platform/tenants", "/api/platform/health", "/api/platform/llm-tasks"]) {
      const res = await request.get(`${API_BASE}${path}`);
      expect(res.status(), path).toBe(401);
    }
  });

  test("platform endpoints reject a token from a tenant-admin who lacks the platform-admin role", async ({ request }) => {
    const { keycloakPasswordGrantToken } = require("../fixtures");
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.get(`${API_BASE}/api/platform/tenants`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(res.status()).toBe(403);
  });
});
