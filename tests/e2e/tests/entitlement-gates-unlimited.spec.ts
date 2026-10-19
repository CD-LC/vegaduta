import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";
import { requireAdminCredentials, signInAsPlatformAdmin, setTenantPlan } from "../helpers/admin-session";
import { AUTH_HOST_RE } from "../helpers/e2e-env";

// Verifies the UNLIMITED tier's entitlement surface end to end on a real,
// admin-promoted tenant:
//   1. White-label suppression in AppShell.tsx (the headline assertion - see
//      that file's `isWhiteLabel` branch, fixed 2026-07-27 after being fully
//      broken).
//   2. The shared Keycloak login page (E2E_AUTH_URL) stays branded -
//      it's cross-tenant, not the white-labeled tenant's own surface.
//   3. Uncapped limits, read straight off the live GET /api/tenant/settings
//      response (the same payload web/app/features/page.tsx renders).
//   4. Every other paid gate actually opens up: BYOK/WhatsApp entitlement
//      flags, a real non-catalog MCP connector registration, a real
//      guardrail-rule create+delete, and the PREMIUM+ LLM-usage-summary
//      endpoint.
//
// Provisions one real FREE tenant via the real signup wizard, then uses a
// second, admin-authenticated browser context to override its plan to
// UNLIMITED (bypassing Razorpay - see admin-session.ts's setTenantPlan
// javadoc reference). Real side effects: a real tenant, one real MCP
// connector row (no DELETE endpoint exists on McpRegistryController today -
// left behind, disabled/never-connected, documented in the closing report),
// and one real guardrail rule (created and then deleted via the real DELETE
// endpoint, so nothing is left behind there).

const INT_MAX = 2147483647;

// Mirrors AppShell.tsx's own `initialsOf` exactly, so the expected rail mark
// matches whatever that component actually renders for this org name.
function initialsOf(name: string): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/);
  return (
    ((parts[0]?.[0] || "") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase() || "?"
  );
}

test.describe("Entitlement gates - UNLIMITED tier", () => {
  test("white-label suppression, uncapped limits, and full gate access on an UNLIMITED tenant", async ({
    page,
    browser,
  }, testInfo) => {
    // Skip cleanly (not a failure) when no platform-admin test credentials
    // are configured - checked FIRST, before provisioning a throwaway tenant
    // for a test that can't actually run.
    requireAdminCredentials(testInfo);
    test.setTimeout(25 * 60 * 1000);

    // --- Setup: real FREE tenant, then super-admin override to UNLIMITED ---
    const identity = await signUpFreshTenant(page, "unlimited-gates");

    const adminContext = await browser.newContext();
    const adminPage = await adminContext.newPage();
    await signInAsPlatformAdmin(adminPage);
    await setTenantPlan(adminPage, identity.orgName, "UNLIMITED");
    await adminContext.close();

    // Reload the tenant page so AppShell/features re-fetch tenant settings
    // and pick up the new plan.
    await page.reload();
    await expect(page.locator("header")).toBeVisible({ timeout: 15_000 });

    // --- 1. White-label suppressed in AppShell.tsx (headline assertion) ---
    const nav = page.locator('nav[aria-label]').first();

    // The winged emblem is gone entirely - no VegaDūta img anywhere in the rail.
    await expect(nav.locator('img[alt="VegaDūta"]')).toHaveCount(0);

    // The brand-identity-showcase trigger button (aria-label "VegaDūta brand
    // identity") no longer exists - white-label swaps it for a plain,
    // non-interactive home Link (aria-label "Home") instead.
    await expect(nav.locator('button[aria-label="VegaDūta brand identity"]')).toHaveCount(0);
    const brandMarkLink = nav.locator('a[aria-label="Home"]');
    await expect(brandMarkLink).toBeVisible();

    // It shows the tenant's own org-initials mark, not our emblem.
    const expectedInitials = initialsOf(identity.orgName);
    if (expectedInitials !== "?") {
      await expect(brandMarkLink).toContainText(expectedInitials);
    }

    // The wordmark link (.brand-name) shows the tenant's own org name, not
    // "VegaDūta".
    const wordmark = nav.locator("a.brand-name");
    await expect(wordmark).toHaveText(identity.orgName);
    await expect(wordmark).not.toContainText("Dūta");

    // Clicking the (now non-interactive) brand-mark link never opens the
    // brand-identity showcase modal - BrandIdentityModal isn't even mounted
    // for a white-labeled tenant (`{!isWhiteLabel && <BrandIdentityModal .../>}`).
    await brandMarkLink.click();
    await expect(page.getByRole("dialog", { name: "VegaDūta brand identity" })).toHaveCount(0);

    // --- 2. Shared unauthenticated surfaces stay branded ---
    // A brand-new, never-authenticated context hitting the real Keycloak
    // login page - shared across every tenant, must NOT inherit this
    // tenant's white-label.
    const anonContext = await browser.newContext();
    const anonPage = await anonContext.newPage();
    await anonPage.goto("/");
    await anonPage.locator("header").getByRole("button", { name: "Sign in" }).click();
    await anonPage.waitForURL(AUTH_HOST_RE, { timeout: 30_000 });
    // Keycloak theme markup (deploy/keycloak/themes/agentic-ai/login/template.ftl):
    // <img alt="VegaDūta"> mark + a "Back to VegaDūta home" wordmark link.
    await expect(anonPage.getByAltText("VegaDūta")).toBeVisible({ timeout: 15_000 });
    await expect(anonPage.getByRole("link", { name: /Back to VegaDūta home/i })).toBeVisible();
    await anonContext.close();

    // --- 3. Uncapped limits, read straight off the live tenant-settings payload ---
    const [settingsResponse] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/tenant/settings" && r.request().method() === "GET"
      ),
      page.goto("/features"),
    ]);
    expect(settingsResponse.status()).toBe(200);
    const settings = await settingsResponse.json();
    const authHeader = settingsResponse.request().headers()["authorization"];
    const apiBase = new URL(settingsResponse.url()).origin;
    expect(authHeader).toBeTruthy();

    expect(settings.plan).toBe("UNLIMITED");
    expect(settings.whiteLabel).toBe(true);
    expect(settings.byokAllowed).toBe(true);
    expect(settings.whatsappAllowed).toBe(true);
    // maxUsers is a primitive int (INT_MAX sentinel when uncapped); the other
    // three are nullable Integers (null when uncapped) - see
    // TenantSettingsResponse.java's javadoc and features/page.tsx's own
    // normalizeLimit().
    expect(settings.maxUsers === null || settings.maxUsers >= INT_MAX).toBeTruthy();
    for (const key of ["maxAgents", "maxWorkflows", "maxKbCollections"]) {
      expect(settings[key] === null || settings[key] >= INT_MAX).toBeTruthy();
    }

    // Cross-check the same story renders on the Plans & features UI.
    await expect(page.getByRole("heading", { name: "Plans & features" })).toBeVisible();
    await expect(page.getByText(/on the/i).filter({ hasText: "Unlimited" })).toBeVisible();
    await expect(page.getByText("Everything unlocked")).toBeVisible();
    const table = page.locator("table");
    const byokRow = table.locator("tbody tr", { hasText: "Bring your own key & model choice" });
    await expect(byokRow.locator('[aria-label="Included"]')).toHaveCount(3);
    const whatsappRow = table.locator("tbody tr", { hasText: "WhatsApp Business" });
    await expect(whatsappRow.locator('[aria-label="Included"]')).toHaveCount(2);
    const whiteLabelRow = table.locator("tbody tr", { hasText: "White-label" });
    await expect(whiteLabelRow.locator('[aria-label="Included"]')).toHaveCount(1);
    const seatsRow = table.locator("tbody tr", { hasText: "Team members" });
    await expect(seatsRow).toContainText("∞");

    // --- 4. Full access to every other gate ---

    // 4a. MCP custom (non-catalog) connector registration - FREE/PREMIUM are
    // capped/catalog-restricted (McpConnectorPlanLimits.java); UNLIMITED has
    // no cap and allows custom servers. enabled:false so nothing actually
    // spawns/connects - this only proves the plan gate itself doesn't 403.
    // No DELETE endpoint exists on McpRegistryController, so this row is
    // left behind (disabled, never connected) - flagged in the closing report.
    const mcpName = `e2e-unlimited-gate-probe-${Date.now()}`;
    const mcpRes = await page.request.post(`${apiBase}/api/mcp/registry`, {
      headers: { Authorization: authHeader },
      data: {
        name: mcpName,
        type: "STDIO",
        description: "Playwright entitlement-gates-unlimited spec probe - safe to ignore/remove.",
        enabled: false,
        command: "true",
        args: [],
        env: {},
      },
    });
    expect(mcpRes.status()).toBe(201);

    // 4b. Guardrail custom-rule create - PREMIUM/UNLIMITED only
    // (GuardrailRuleService.createCustom); cleaned up via the real DELETE
    // endpoint so nothing is left behind.
    const guardrailPayload = {
      name: `E2E unlimited-gate probe ${Date.now()}`,
      description: "Playwright entitlement-gates-unlimited spec - deleted immediately after creation.",
      pattern: "e2e-unlimited-gate-probe-pattern",
      action: "BLOCK",
      enabled: true,
    };
    const createRes = await page.request.post(`${apiBase}/api/guardrails/rules`, {
      headers: { Authorization: authHeader },
      data: guardrailPayload,
    });
    expect(createRes.status()).toBe(201);
    const created = await createRes.json();
    expect(created.id).toBeTruthy();

    const deleteRes = await page.request.delete(`${apiBase}/api/guardrails/rules/${created.id}`, {
      headers: { Authorization: authHeader },
    });
    expect(deleteRes.status()).toBe(204);

    // 4c. LLM usage/cost summary - PREMIUM+ only (LlmCallLogController's
    // assertPremiumPlan) - drive it through the real Platform Maintenance
    // screen so the request/response is the app's own, not a hand-built call.
    const [summaryResponse] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/admin/llm-usage/summary" && r.request().method() === "GET"
      ),
      page.goto("/admin/maintenance"),
    ]);
    expect(summaryResponse.status()).toBe(200);
  });
});
