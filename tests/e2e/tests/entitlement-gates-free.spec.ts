import { test, expect, type Page } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";
import { API_BASE_URL } from "../helpers/e2e-env";

// FREE-tier entitlement audit - the one spec this billing-E2E suite is built
// around (see e2e/HANDOFF-BILLING-E2E-2026-07-28.md and
// docs/BILLING-TIER-STRATEGY-2026-07-27.md for the full tier-matrix ground
// truth this spec asserts against). Signs up ONE real, throwaway FREE tenant
// (signUpFreshTenant - real signup wizard, real Keycloak login) and walks
// all 8 gates the task specified, in this order:
//
//   1. Model/provider lock on agent creation       (AgentWorkbench.tsx's isFreePlan gate)
//   7. White-label branding still visible (no-regression check)  (AppShell.tsx's isWhiteLabel)
//   8a. Agent count cap (FREE = 5)                 (AGENT_LIMIT in AgentWorkbench.tsx)
//   2. BYOK ProvidersPanel is read-only             (ProvidersPanel.tsx)
//   8b. Workflow count cap (FREE = 3)               (WORKFLOW_LIMIT in WorkflowDesignerPanel.tsx)
//   8c. Knowledge-base collection cap (FREE = 1)    (KnowledgeCollectionService.java)
//   3. WhatsApp channel setup rejected               (MetaWhatsAppCredentialService.java)
//   4. MCP connector cap + catalog-only restriction  (McpConnectorPlanLimits.java / McpRegistryService.java)
//   5. Guardrails custom-rule authoring rejected     (GuardrailRuleService.java) - reads stay open
//   6. LLM usage/tracing view rejected               (LlmCallLogController.java) - already confirmed
//      working per the handoff doc; reused here rather than re-derived.
//
// Steps are grouped by page (not strictly by the task's numbering) purely to
// avoid redundant navigations - each test.step() below is labelled with the
// gate number(s) it covers so the report maps 1:1 onto the 8 items.
//
// IMPORTANT FINDING (read before extending this spec): items 3/4/5/6/8c have
// NO dedicated UI lock in this codebase today - verified by reading
// ChannelsPanel.tsx and KnowledgeCollectionsPanel.tsx directly: neither file
// contains "isFreePlan", "tenantPlan", "freeTier", or "upgradeLocked"
// anywhere. Those gates are enforced ONLY on the backend
// (TenantLimitExceededException -> HTTP 403 via the shared
// GlobalExceptionHandler in core/app), with no proactive lock/upgrade-prompt
// in the React UI a FREE tenant would actually see before submitting. This
// spec therefore calls the real REST endpoints directly via page.request for
// those items (same "backend-only gate, hit the endpoint directly" pattern
// the task brief itself specified for MCP/guardrails), and documents the
// missing UI lock as a real product gap in the final report rather than
// pretending a lock exists.
//
// Auth for the direct API calls: this app's browser-side auth
// (web/app/lib/auth.ts) keeps its Keycloak access token in-memory only (no
// cookie, no localStorage) - page.request shares cookies with the page's
// browser context but NOT that in-memory bearer token, so a raw
// page.request.get/post would be unauthenticated. captureBearerToken() below
// gets a REAL token by watching the actual Authorization header the app's
// own authenticated fetch() sends on a real navigation, then reuses that
// header verbatim for the direct backend-only calls in this spec.
//
// Real production side effects this run leaves behind on
// the target environment (E2E_BASE_URL - every run
// of this suite creates real test data): one FREE tenant, 5 real agents, 3
// real workflows, 2 real MCP connector registrations (filesystem + memory,
// both zero-credential catalog entries, never actually connected to
// anything since no command/args/env is submitted).

const API_ORIGIN = API_BASE_URL;

/**
 * Captures a real `Authorization: Bearer <token>` header off the wire by
 * triggering a real authenticated navigation (any data-fetching page fires
 * one on mount) and reading it back from the matching outgoing request -
 * see this file's header comment for why a raw page.request call can't
 * authenticate itself any other way.
 */
async function captureBearerToken(page: Page): Promise<string> {
  const reqPromise = page.waitForRequest(
    (r) => r.url().startsWith(API_ORIGIN) && !!r.headers()["authorization"],
    { timeout: 20_000 }
  );
  await page.goto("/agents");
  const req = await reqPromise;
  return req.headers()["authorization"];
}

test.describe("FREE-tier entitlement gates", () => {
  test("all 8 FREE-plan gates hold for a fresh tenant", async ({ page }, testInfo) => {
    // Provisions one real tenant and creates real agents/workflows/MCP rows
    // against it - run once, not once per browser project.
    testInfo.skip(
      testInfo.project.name !== "chromium",
      "provisions one real FREE tenant and mutates real data against it - run on one project only"
    );
    test.setTimeout(25 * 60 * 1000);

    await signUpFreshTenant(page, "free-gates");

    // ---- 1. Model/provider lock on agent creation --------------------
    // isDraft = !selectedAgent, and selectedAgentId starts null, so the
    // create-agent form is already open on first load - no "New agent"
    // click needed for the first one.
    await test.step("1. Model/provider lock on agent creation", async () => {
      await page.goto("/agents");
      // FREE: a fixed "Platform LLM (Free tier)" badge replaces the editable
      // provider Select entirely (2026-07-21 fix comment in AgentWorkbench.tsx) -
      // assert both that the badge is shown AND the editable select is gone.
      await expect(page.getByText("Platform LLM (Free tier)").first()).toBeVisible();
      await expect(page.locator('[data-testid="agent-provider-select"]')).toHaveCount(0);

      await page.getByPlaceholder("Name this agent…").fill("E2E FREE Agent 1");
      await page.getByRole("button", { name: "Save draft" }).click();
      await expect(page.getByText('"E2E FREE Agent 1" created.')).toBeVisible({ timeout: 20_000 });
    });

    // ---- 7. White-label branding still visible (no-regression check) -
    await test.step("7. White-label branding still visible (no-regression)", async () => {
      // isWhiteLabel must be false for FREE (AppShell.tsx) - the real winged
      // logo-mark + "VegaDūta" wordmark render, NOT the plain org-initials
      // placeholder the isWhiteLabel branch would show instead.
      await expect(page.locator('img[alt="VegaDūta"]')).toBeVisible();
      await expect(page.getByRole("link", { name: /VegaDūta/ }).first()).toBeVisible();
    });

    // ---- 8a. Agent count cap (FREE = 5) -------------------------------
    await test.step("8a. Agent count cap enforced (FREE = 5 agents)", async () => {
      for (let i = 2; i <= 5; i++) {
        await page.getByRole("button", { name: /Agents/ }).click();
        await page.getByRole("listbox").getByRole("button", { name: "New agent" }).click();
        await page.getByPlaceholder("Name this agent…").fill(`E2E FREE Agent ${i}`);
        await page.getByRole("button", { name: "Save draft" }).click();
        await expect(page.getByText(`"E2E FREE Agent ${i}" created.`)).toBeVisible({ timeout: 20_000 });
      }
      await expect(page.getByText("5/5 agents used")).toBeVisible();

      // 6th attempt: client-side validate() rejects it BEFORE any API call
      // (AgentWorkbench.tsx's atAgentLimit check) - button stays present,
      // clicking it just surfaces the limit error, no agent is created.
      await page.getByRole("button", { name: /Agents/ }).click();
      await page.getByRole("listbox").getByRole("button", { name: "New agent" }).click();
      await page.getByPlaceholder("Name this agent…").fill("E2E FREE Agent 6 (should reject)");
      await page.getByRole("button", { name: "Save draft" }).click();
      await expect(page.getByText("You've reached your plan's limit of 5 agents.")).toBeVisible();
      await expect(page.getByText('"E2E FREE Agent 6 (should reject)" created.')).toHaveCount(0);
      await expect(page.getByText("6/5 agents used")).toHaveCount(0);
    });

    // ---- 2. BYOK ProvidersPanel is read-only for FREE -----------------
    await test.step("2. BYOK providers panel is read-only for FREE", async () => {
      await page.goto("/providers");
      await expect(page.getByText(/provider activation is a paid-plan feature/i)).toBeVisible();
      // Every provider card is non-clickable for FREE (ProvidersPanel.tsx:
      // `clickable = isAdmin && !isFreePlan && ...` - role="button" is only
      // set when clickable, so this selects zero elements for FREE).
      await expect(page.locator('[role="button"]').filter({ hasText: "Anthropic" })).toHaveCount(0);
      await expect(page.locator('[role="button"]').filter({ hasText: "OpenAI" })).toHaveCount(0);
    });

    // ---- 8b. Workflow count cap (FREE = 3) ----------------------------
    await test.step("8b. Workflow count cap enforced (FREE = 3 workflows)", async () => {
      for (let i = 1; i <= 3; i++) {
        await page.goto("/workflows");
        await page.getByRole("button", { name: "New workflow" }).click();
        await page.getByPlaceholder(/New workflow name/i).fill(`E2E FREE Workflow ${i}`);
        await page.getByRole("button", { name: "Create workflow" }).click();
        // A successful create navigates straight to the new workflow's canvas.
        await page.waitForURL(/\/workflows\/[^/]+$/, { timeout: 20_000 });
      }
      await page.goto("/workflows");
      await expect(page.getByText("Limit reached")).toBeVisible();
      await expect(page.getByRole("button", { name: "New workflow" })).toHaveCount(0);
      await expect(page.getByText("3 / 3")).toBeVisible();
    });

    // ---- 8c. Knowledge-base collection cap (FREE = 1) -----------------
    await test.step("8c. Knowledge-base collection cap enforced (FREE = 1, no UI lock)", async () => {
      // Every tenant is seeded with one un-deletable "Default" collection
      // (KnowledgeCollectionService, migration V15), which already fills a
      // FREE tenant's cap of 1 - so even the FIRST attempt at a 2nd
      // collection must be rejected. No isFreePlan gate exists anywhere in
      // KnowledgeCollectionsPanel.tsx - this is backend-only enforcement.
      await page.goto("/knowledge");
      await page.getByRole("button", { name: "New collection", exact: true }).first().click();
      const form = page.locator("form").filter({ hasText: "Name" });
      await form.locator("input").first().fill("E2E FREE Collection 2 (should reject)");
      await form.getByRole("button", { name: "Create", exact: true }).click();
      await expect(
        page.getByText(/knowledge collection\(s\) - upgrade your plan to add more/i)
      ).toBeVisible({ timeout: 20_000 });
    });

    // ---- Backend-only gates: no dedicated UI lock exists for these, so
    // hit the real REST endpoints directly (per the task brief's own
    // instruction for MCP/guardrails, applied consistently to WhatsApp/LLM
    // usage too, since none of the four have a frontend lock either). ----
    const authHeader = await captureBearerToken(page);
    expect(authHeader).toMatch(/^Bearer /);

    // ---- 3. WhatsApp channel setup rejected for FREE ------------------
    await test.step("3. WhatsApp channel setup rejected for FREE (backend-only gate, no UI lock)", async () => {
      const res = await page.request.post(`${API_ORIGIN}/api/channels/meta-whatsapp/credential`, {
        headers: { Authorization: authHeader },
        data: {
          phoneNumberId: "000000000000000",
          wabaId: "000000000000000",
          accessToken: "e2e-fake-token",
          appSecret: "e2e-fake-secret",
        },
      });
      expect(res.status()).toBe(403);
      const body = await res.json();
      expect(String(body.error)).toMatch(/whatsapp channel.*upgrade your plan/i);
    });

    // ---- 4. MCP connector cap + catalog-only restriction --------------
    await test.step("4. MCP connector cap (2) + catalog-only restriction for FREE", async () => {
      // A non-catalog custom name is rejected outright, independent of the
      // cap (McpConnectorPlanLimits.allowsCustomServers is false for FREE).
      const customRes = await page.request.post(`${API_ORIGIN}/api/mcp/registry`, {
        headers: { Authorization: authHeader },
        data: { name: "e2e-custom-non-catalog-server", type: "STDIO", description: "", enabled: false },
      });
      expect(customRes.status()).toBe(403);
      expect(String((await customRes.json()).error)).toMatch(/built-in mcp catalog/i);

      // First two catalog connectors succeed (FREE cap = 2) - "filesystem"
      // and "memory" are both zero-credential catalog entries
      // (McpCatalogService), registered here as static (no command/args/env)
      // so nothing actually tries to connect anywhere.
      for (const name of ["filesystem", "memory"]) {
        const res = await page.request.post(`${API_ORIGIN}/api/mcp/registry`, {
          headers: { Authorization: authHeader },
          data: { name, type: "STDIO", description: "", enabled: false },
        });
        expect(res.status(), `registering catalog entry "${name}"`).toBe(201);
      }

      // Third registration - even a valid catalog name - is rejected once
      // the plan's connector cap is reached.
      const capRes = await page.request.post(`${API_ORIGIN}/api/mcp/registry`, {
        headers: { Authorization: authHeader },
        data: { name: "puppeteer", type: "STDIO", description: "", enabled: false },
      });
      expect(capRes.status()).toBe(403);
      expect(String((await capRes.json()).error)).toMatch(/allows up to 2 mcp connectors/i);
    });

    // ---- 5. Guardrails custom-rule authoring rejected; reads open -----
    await test.step("5. Guardrails custom-rule authoring rejected for FREE; reads stay open", async () => {
      const listRes = await page.request.get(`${API_ORIGIN}/api/guardrails/rules`, {
        headers: { Authorization: authHeader },
      });
      expect(listRes.status()).toBe(200);
      expect(Array.isArray(await listRes.json())).toBe(true);

      const createRes = await page.request.post(`${API_ORIGIN}/api/guardrails/rules`, {
        headers: { Authorization: authHeader },
        data: {
          name: "E2E custom rule (should reject)",
          description: "",
          pattern: "e2e-test-pattern",
          action: "BLOCK",
          enabled: true,
        },
      });
      expect(createRes.status()).toBe(403);
      expect(String((await createRes.json()).error)).toMatch(/authoring custom guardrail rules is a premium feature/i);
    });

    // ---- 6. LLM usage/tracing view rejected for FREE ------------------
    await test.step("6. LLM usage/tracing view rejected for FREE (reused from prior session's confirmed assertion)", async () => {
      const res = await page.request.get(`${API_ORIGIN}/api/admin/llm-usage/summary`, {
        headers: { Authorization: authHeader },
      });
      expect(res.status()).toBe(403);
      expect(String((await res.json()).error)).toMatch(/llm usage\/tracing.*premium feature/i);
    });
  });
});
