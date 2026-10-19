import { test, expect, type Page } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";
import { requireAdminCredentials, signInAsPlatformAdmin, setTenantPlan } from "../helpers/admin-session";

// Verifies the PREMIUM flip-side of every FREE-tier entitlement gate this
// billing audit put in place - see e2e/HANDOFF-BILLING-E2E-2026-07-28.md for
// the full context (this is one of 6 spec files that handoff left unwritten;
// its sibling entitlement-gates-free.spec.ts did NOT exist on disk at the
// time this file was written, so there was no structure to mirror).
//
// Sources read for exact selectors/values (none modified):
//   - web/app/components/agents/AgentWorkbench.tsx - isFreePlan branches
//     (creation-time provider/model picker, "Platform LLM (Free tier)" badge)
//   - web/app/components/ProvidersPanel.tsx - isFreePlan read-only banner +
//     card clickability gate
//   - web/app/components/AppShell.tsx - isWhiteLabel branch (logo/wordmark)
//   - core/mcp/.../McpConnectorPlanLimits.java - FREE 2 connectors/catalog-only,
//     PREMIUM 10 connectors/custom-servers-allowed
//   - core/guardrails/.../GuardrailRuleController.java + GuardrailRuleService.java -
//     POST/DELETE /api/guardrails/rules, FREE-tier authoring gate
//   - core/chat/.../LlmCallLogController.java - GET /api/admin/llm-usage/summary,
//     FREE-tier visibility gate
//   - core/app/.../db/migration/controlplane/V101__plan_limits.sql - seed values
//     (FREE: 2 users/5 agents/3 workflows/1 kb; PREMIUM: 10/25/15/5; both BYOK+
//     WhatsApp allowed from PREMIUM up; white_label only on UNLIMITED)
//   - core/omnichannel/.../meta/MetaWhatsAppCredentialService.java - WhatsApp
//     channel's plan gate (whatsappAllowed)
//
// Real side effects: provisions one real FREE tenant, flips it to PREMIUM via
// the sanctioned super-admin override (setTenantPlan - bypasses Razorpay,
// same as every other admin-gated spec in this suite), and creates real
// agents/workflows/KB collections/an MCP connector/a guardrail rule against
// production. The guardrail rule is deleted at the end (a DELETE route
// exists); the rest is left behind as ordinary throwaway test-tenant data,
// matching this suite's established convention.

/**
 * Captures a live bearer token + the API's actual origin from a real
 * authenticated request the app itself makes on every AppShell mount
 * (api.getTenantSettings(), web/app/components/AppShell.tsx) - lets this
 * spec make backend-only assertions (MCP registration, guardrail rules,
 * llm-usage, agent/workflow/KB counts) with page.request instead of
 * reimplementing the app's Keycloak token plumbing. The token lives only in
 * apiFetch's in-memory auth.getToken() (web/app/lib/api.ts) - never a
 * cookie - so intercepting one real outgoing request is the only way to
 * read it from outside the page's own JS. Reloading is also exactly the
 * "entitlements are read live, no cache" step the plan-flip setup needs.
 */
async function captureApiContext(page: Page): Promise<{ token: string; apiBase: string }> {
  const [request] = await Promise.all([
    page.waitForRequest(
      (req) => /\/api\/tenant\/settings(\?|$)/.test(req.url()) && !!req.headers()["authorization"],
      { timeout: 20_000 }
    ),
    page.reload(),
  ]);
  const token = request.headers()["authorization"];
  const url = new URL(request.url());
  return { token, apiBase: `${url.protocol}//${url.host}` };
}

test.describe("Entitlement gates - PREMIUM tenant (flip side of the FREE gates)", () => {
  test("PREMIUM unlocks BYOK choice, provider activation, WhatsApp, MCP custom connectors, guardrail authoring, LLM usage visibility, and raised agent/workflow/KB caps - while staying un-white-labeled", async ({
    page,
    browser,
  }, testInfo) => {
    // Call first, before provisioning anything real - no point creating a
    // live tenant if this whole spec is about to skip anyway.
    requireAdminCredentials(testInfo);
    test.setTimeout(25 * 60 * 1000);

    // --- Setup: fresh FREE tenant, then super-admin flips it to PREMIUM ---
    const identity = await signUpFreshTenant(page, "premium-gates");

    const adminContext = await browser.newContext();
    const adminPage = await adminContext.newPage();
    await signInAsPlatformAdmin(adminPage);
    await setTenantPlan(adminPage, identity.orgName, "PREMIUM");
    await adminContext.close();

    // Reload the tenant's original page - entitlements are read live, no
    // cache - and grab a bearer token + API origin off that reload's own
    // authenticated request for the backend-only checks below.
    const { token, apiBase } = await captureApiContext(page);
    const authHeaders = { Authorization: token };
    await expect(page.locator("header")).toBeVisible({ timeout: 15_000 });

    // Sanity-check the flip actually landed before testing any gate off it.
    const settingsRes = await page.request.get(`${apiBase}/api/tenant/settings`, { headers: authHeaders });
    expect(settingsRes.ok()).toBeTruthy();
    const settings = await settingsRes.json();
    expect(settings.plan).toBe("PREMIUM");
    expect(settings.byokAllowed).toBe(true);
    expect(settings.whatsappAllowed).toBe(true);
    // Item 8's backend half - only UNLIMITED gets white-label, not PREMIUM.
    expect(settings.whiteLabel).toBe(false);

    // --- 1. AgentWorkbench.tsx isFreePlan branches - full BYOK provider/model choice at creation ---
    await page.goto("/agents");
    // Fresh tenant has zero agents yet, so AgentWorkbench opens straight into
    // its draft/creation state (isDraft = !selectedAgent) with no "New agent"
    // click needed - the interesting thing to check is which branch of the
    // isFreePlan ternary rendered.
    const providerSelect = page.locator('[data-testid="agent-provider-select"]');
    await expect(providerSelect).toBeVisible({ timeout: 15_000 });
    await expect(providerSelect).toBeEnabled();
    // The FREE-tier substitute (a fixed, non-interactive badge) must be gone.
    await expect(page.getByText("Platform LLM (Free tier)")).toHaveCount(0);

    // --- 2. ProvidersPanel.tsx - provider-key activation now interactive ---
    await page.goto("/providers");
    await expect(page.getByText(/is on the Free plan and automatically uses the platform-hosted LLM/i)).toHaveCount(0);
    const anthropicCard = page.getByRole("button").filter({ hasText: "Anthropic" }).first();
    await expect(anthropicCard).toBeVisible({ timeout: 15_000 });
    await anthropicCard.click();
    await expect(page.getByText("Activate Anthropic")).toBeVisible();
    await expect(page.getByLabel("API key")).toBeEditable();
    await expect(page.getByRole("button", { name: "Activate" })).toBeEnabled();
    // Deliberately never submits - same "don't actually validate a fake key
    // against a real provider" restraint tests/checkout-ui.spec.ts documents
    // for Razorpay. The entitlement under test is reachability of the
    // activation form, not the provider's own credential validation.

    // --- 3. WhatsApp channel setup unlocked (MetaWhatsAppCredentialService#activate's plan gate) ---
    const waRes = await page.request.post(`${apiBase}/api/channels/meta-whatsapp/credential`, {
      headers: authHeaders,
      data: {
        phoneNumberId: "000000000000000",
        wabaId: "000000000000000",
        accessToken: "e2e-fake-token-not-real",
        appSecret: "e2e-fake-secret-not-real",
      },
    });
    // A real Meta Graph API call with fake credentials still fails - the
    // point of this assertion is that it now fails on THAT (invalid
    // token/phone id), not on the FREE-tier plan gate, which used to reject
    // with 403 before the request ever reached Meta.
    expect(waRes.status()).not.toBe(403);
    const waBody = await waRes.text();
    expect(waBody).not.toMatch(/does not include the WhatsApp channel/i);

    // --- 4. MCP: PREMIUM allows a custom (non-catalog) connector, cap raised to 10 ---
    const customMcpName = `e2e-premium-custom-${Date.now()}`;
    const mcpRes = await page.request.post(`${apiBase}/api/mcp/registry`, {
      headers: authHeaders,
      data: { name: customMcpName, type: "STDIO", description: "e2e custom connector", enabled: false },
    });
    expect(mcpRes.status()).toBe(201);
    const mcpBody = await mcpRes.json();
    expect(mcpBody.name).toBe(customMcpName);

    // --- 5. POST /api/guardrails/rules now succeeds (was 403 for FREE) ---
    const guardrailRes = await page.request.post(`${apiBase}/api/guardrails/rules`, {
      headers: authHeaders,
      data: {
        name: `e2e-premium-rule-${Date.now()}`,
        description: "e2e custom guardrail rule",
        pattern: "e2e-test-pattern-do-not-match-real-content",
        action: "BLOCK",
        enabled: true,
      },
    });
    expect(guardrailRes.status()).toBe(201);
    const guardrailBody = await guardrailRes.json();
    expect(guardrailBody.id).toBeTruthy();
    // Clean up - DELETE /api/guardrails/rules/{id} exists on GuardrailRuleController.
    const deleteRes = await page.request.delete(`${apiBase}/api/guardrails/rules/${guardrailBody.id}`, {
      headers: authHeaders,
    });
    expect(deleteRes.status()).toBe(204);

    // --- 6. GET /api/admin/llm-usage/summary now returns 200 ---
    const usageRes = await page.request.get(`${apiBase}/api/admin/llm-usage/summary`, { headers: authHeaders });
    expect(usageRes.status()).toBe(200);

    // --- 7. Agent/workflow/KB caps raised beyond FREE's 5/3/1 ---
    // (control_plane.plan_limits: FREE 5 agents/3 workflows/1 kb collection,
    // PREMIUM 25/15/5 - V101__plan_limits.sql). No need to reach PREMIUM's
    // own 25/15/5 ceiling - just prove FREE's ceiling no longer applies.
    for (let i = 0; i < 6; i++) {
      const agentRes = await page.request.post(`${apiBase}/api/agents`, {
        headers: authHeaders,
        data: {
          name: `e2e-premium-agent-${i}-${Date.now()}`,
          systemPrompt: "You are an e2e test agent used to verify PREMIUM plan limits.",
          providerName: "ANTHROPIC",
        },
      });
      expect(agentRes.status(), `agent #${i + 1} of 6 (FREE caps at 5)`).toBe(201);
    }

    for (let i = 0; i < 4; i++) {
      const workflowRes = await page.request.post(`${apiBase}/api/workflows`, {
        headers: authHeaders,
        data: {
          name: `e2e-premium-workflow-${i}-${Date.now()}`,
          description: "e2e test workflow used to verify PREMIUM plan limits",
          graph: { nodes: [], edges: [] },
        },
      });
      expect(workflowRes.status(), `workflow #${i + 1} of 4 (FREE caps at 3)`).toBe(201);
    }

    for (let i = 0; i < 2; i++) {
      const kbRes = await page.request.post(`${apiBase}/api/knowledge/collections`, {
        headers: authHeaders,
        data: {
          name: `e2e-premium-kb-${i}-${Date.now()}`,
          description: "e2e test knowledge collection used to verify PREMIUM plan limits",
        },
      });
      expect(kbRes.status(), `kb collection #${i + 1} of 2 (FREE caps at 1)`).toBe(201);
    }

    // --- 8. White-label still OFF for PREMIUM (only UNLIMITED gets it) ---
    // Backend half already asserted above (settings.whiteLabel === false).
    // UI half: AppShell.tsx's isWhiteLabel branch must NOT be the one that
    // rendered - the real winged-emblem mark + "VegaDūta" wordmark stay.
    await page.goto("/");
    await expect(page.locator('img[alt="VegaDūta"]')).toBeVisible({ timeout: 15_000 });
  });
});
