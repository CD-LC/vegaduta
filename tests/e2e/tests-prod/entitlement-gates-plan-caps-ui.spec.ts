import { test, expect, type Page } from "@playwright/test";
import { requireAdminCredentials, requireMutationOptIn, DOMAIN, ADMIN_URL } from "../helpers/prod-env";
import { signInToAdmin } from "../helpers/prod-session";
import { collectProdErrors } from "../helpers/prod-console";

/**
 * Coverage gap this spec closes (billing audit, 2026-08-28):
 *
 * The existing PREMIUM/UNLIMITED entitlement specs (e2e/tests/entitlement-
 * gates-premium.spec.ts and its -unlimited sibling - the .xyz staging suite,
 * not this one) verify raised agent/workflow caps EXCLUSIVELY via direct
 * `page.request.post("/api/agents"|"/api/workflows")` calls, never through
 * the real AgentWorkbench "Save draft" button or WorkflowDesignerPanel "New
 * workflow" form. That is exactly why a hardcoded-UI-cap bug would never be
 * caught: no spec drives a PREMIUM/UNLIMITED tenant's real creation UI past
 * FREE's own numeric ceiling.
 *
 * And there is such a bug, confirmed by reading the source this spec exercises:
 *   - web/app/lib/planLimits.ts: AGENT_LIMIT=5, WORKFLOW_LIMIT=3, documented
 *     there as "Universal-tier" caps.
 *   - web/app/components/agents/AgentWorkbench.tsx:448 -
 *     `atAgentLimit = agents.length >= AGENT_LIMIT` - no isFreePlan/tenantPlan
 *     check anywhere near it, unlike every other gate in that file (which are
 *     all `isFreePlan`-branched).
 *   - web/app/components/WorkflowDesignerPanel.tsx:119 -
 *     `atLimit = workflows.length >= WORKFLOW_LIMIT` - same story; `isFreePlan`
 *     exists in this file (line 117) for the delete-button gate but is never
 *     consulted for `atLimit`.
 * So a PREMIUM tenant (backend cap 25 agents / 15 workflows - V101 seed) or
 * an UNLIMITED one (uncapped) hits AgentWorkbench's "you've reached your
 * plan's limit of 5 agents" banner / WorkflowDesignerPanel's "Limit reached"
 * badge at the FREE-tier count, even though the backend would happily accept
 * more. This spec proves it end to end on a real PREMIUM tenant.
 *
 * Follows this suite's established conventions: collectProdErrors() for
 * console/network capture, requireAdminCredentials/requireMutationOptIn
 * gates, and (since helpers/admin-session.ts + helpers/test-tenant.ts
 * pin the local-suite admin/auth hosts in their waitForURL regexes
 * and would hang against production) the signup + plan-flip flow is written
 * inline against DOMAIN instead - the same reasoning tests-prod/signup.spec.ts's
 * own WRITES block documents, extended here to the plan-override step
 * helpers/admin-session.ts's setTenantPlan does not have a domain-aware twin
 * for.
 *
 * Real side effects: provisions one real FREE tenant, flips it to PREMIUM via
 * the sanctioned super-admin override (bypasses Razorpay, same mechanism
 * every other admin-gated entitlement spec in this repo uses), and leaves
 * behind 6 real agents + 4 real workflows in that tenant. Nothing is deleted
 * - annotated in the test report, matching this suite's "data-left-behind"
 * convention (see console-authenticated.spec.ts's agent-creation write test).
 */

const AUTH_HOST_RE = new RegExp(`auth\\.${DOMAIN.replace(/\./g, "\\.")}`);
const APP_HOST_RE = new RegExp(`app\\.${DOMAIN.replace(/\./g, "\\.")}`);

interface Identity {
  email: string;
  password: string;
  orgName: string;
}

function freshIdentity(label: string): Identity {
  const stamp = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  return {
    email: `e2e-${label}-${stamp}@mailinator.com`,
    password: `E2eTest!${Math.random().toString(36).slice(2, 10)}Aa1`,
    orgName: `E2E ${label} ${stamp}`,
  };
}

/**
 * Real self-service signup through to a logged-in console session. Domain-
 * aware (see file header for why this isn't helpers/test-tenant.ts).
 */
async function signUpAndSignIn(page: Page, label: string): Promise<Identity> {
  const identity = freshIdentity(label);

  await page.goto("/signup", { waitUntil: "domcontentloaded" });
  await page.waitForLoadState("load", { timeout: 15_000 }).catch(() => {});
  const start = page.getByRole("button", { name: /register now|get started|create/i }).first();
  if ((await start.count()) > 0) {
    await start.click().catch(() => {});
  }

  await expect(page.getByLabel(/workspace name|workspace/i)).toBeVisible({ timeout: 30_000 });
  await page.getByLabel(/workspace name|workspace/i).fill(identity.orgName);
  await page.getByLabel(/first name/i).fill("Playwright");
  await page.getByLabel(/last name/i).fill("Bot");
  await page.getByLabel(/work email|email/i).first().fill(identity.email);
  await page.getByLabel(/^password$/i).fill(identity.password);
  await page.getByLabel(/confirm password/i).fill(identity.password);
  await page.getByLabel(/i agree to the/i).check();
  await page.getByRole("button", { name: /launch your control panel|create|continue|submit/i }).first().click();

  // Provisioning a Keycloak user + tenant schema is slow under concurrent
  // E2E load - generous timeout, matching tests-prod/signup.spec.ts.
  await expect(page.getByText(identity.orgName)).toBeVisible({ timeout: 90_000 });

  // FreeWorkspaceReadyStep auto-redirects to Keycloak ~1.2s later with the
  // email pre-filled as loginHint - only the password needs entering.
  await page.waitForURL(AUTH_HOST_RE, { timeout: 45_000 });
  await page.locator("#password").fill(identity.password);
  await page.locator("#kc-login").click();

  await page.waitForURL(APP_HOST_RE, { timeout: 45_000 });
  await expect(page.locator("header")).toBeVisible({ timeout: 30_000 });

  return identity;
}

/**
 * Super-admin plan override via platform-admin's Tenant Roster & Plans screen -
 * domain-aware equivalent of helpers/admin-session.ts's setTenantPlan (that
 * one is pinned to the local-suite admin host). Call on a page already signed in via
 * signInToAdmin from prod-session.ts.
 */
async function setTenantPlan(page: Page, orgName: string, plan: "PREMIUM" | "UNLIMITED") {
  await page.goto(`${ADMIN_URL}/tenants`);
  const searchBox = page.getByPlaceholder(/search/i).first();
  await searchBox.fill(orgName);

  const row = page.locator("div", { hasText: orgName }).filter({ has: page.getByText("Plan") }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });

  const planSelect = row.locator("select");
  await planSelect.selectOption(plan);
  await row.getByRole("button", { name: /^save$/i }).click();

  await expect(page.getByText(new RegExp(`moved to ${plan}`, "i"))).toBeVisible({ timeout: 15_000 });
}

test.describe("Entitlement gates - real UI past FREE's numeric ceiling (WRITES to production)", () => {
  test("AgentWorkbench 'Save draft' and WorkflowDesignerPanel 'New workflow' stay usable for a PREMIUM tenant at FREE's 5-agent/3-workflow ceiling", async ({
    page,
    browser,
  }, testInfo) => {
    // Checked first, before provisioning anything real.
    requireAdminCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data to production - one project only");
    test.setTimeout(25 * 60 * 1000);

    const errors = collectProdErrors(page);

    // --- Setup: real FREE tenant, then super-admin override to PREMIUM ---
    const identity = await signUpAndSignIn(page, "premium-ui-cap");

    const adminContext = await browser.newContext();
    const adminPage = await adminContext.newPage();
    await signInToAdmin(adminPage);
    await setTenantPlan(adminPage, identity.orgName, "PREMIUM");
    await adminContext.close();

    // Reload so entitlements are read live (no cache) and capture a bearer
    // token + API origin off that reload's own authenticated request, the
    // same technique e2e/tests/entitlement-gates-premium.spec.ts uses.
    const [settingsResponse] = await Promise.all([
      page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/tenant/settings" && r.request().method() === "GET"
      ),
      page.reload(),
    ]);
    expect(settingsResponse.status()).toBe(200);
    const settings = await settingsResponse.json();
    expect(settings.plan).toBe("PREMIUM");
    const authHeader = settingsResponse.request().headers()["authorization"];
    const apiBase = new URL(settingsResponse.url()).origin;
    expect(authHeader).toBeTruthy();
    await expect(page.locator("header")).toBeVisible({ timeout: 15_000 });

    // --- Fill to FREE's own ceiling via the backend - fast, and not itself
    // what's under test (that part is already covered by the staging suite's
    // page.request-based assertions). ---
    for (let i = 0; i < 5; i++) {
      const res = await page.request.post(`${apiBase}/api/agents`, {
        headers: { Authorization: authHeader },
        data: {
          name: `e2e-premium-uicap-agent-${i}-${Date.now()}`,
          systemPrompt: "e2e test agent seeding a PREMIUM tenant to FREE's 5-agent UI ceiling.",
          providerName: "ANTHROPIC",
        },
      });
      expect(res.status(), `seed agent #${i + 1} of 5`).toBe(201);
    }
    for (let i = 0; i < 3; i++) {
      const res = await page.request.post(`${apiBase}/api/workflows`, {
        headers: { Authorization: authHeader },
        data: {
          name: `e2e-premium-uicap-workflow-${i}-${Date.now()}`,
          description: "e2e test workflow seeding a PREMIUM tenant to FREE's 3-workflow UI ceiling.",
          graph: { nodes: [], edges: [] },
        },
      });
      expect(res.status(), `seed workflow #${i + 1} of 3`).toBe(201);
    }

    // --- THE ACTUAL GAP: drive AgentWorkbench's real "Save draft" form for
    // a 6th agent, with this PREMIUM tenant sitting exactly at FREE's own
    // ceiling of 5. ---
    await page.goto("/agents", { waitUntil: "domcontentloaded" });

    // AgentWorkbench.tsx:2160's atAgentLimit banner must NOT appear - if it
    // does, AGENT_LIMIT=5 (web/app/lib/planLimits.ts) is leaking into a
    // PREMIUM tenant's UI instead of only FREE's.
    await expect(page.getByText(/reached your plan's limit of 5 agents/i)).toHaveCount(0);

    const sixthAgentName = `E2E Premium 6th Agent ${Date.now()}`;
    const nameField = page.getByPlaceholder(/name this agent/i);
    await expect(nameField).toBeVisible({ timeout: 30_000 });
    await nameField.fill(sixthAgentName);

    const saveDraft = page.getByRole("button", { name: /save draft/i });
    await expect(saveDraft, "Save draft was disabled/hidden for a PREMIUM tenant at FREE's agent ceiling").toBeEnabled({
      timeout: 15_000,
    });
    await saveDraft.click();
    await expect(page.getByText(new RegExp(`"?${sixthAgentName}"? created`, "i"))).toBeVisible({ timeout: 30_000 });

    // Persistence across a full reload - not an in-memory optimistic update.
    await page.reload();
    await expect(page.getByText(sixthAgentName).first()).toBeVisible({ timeout: 30_000 });

    // --- Same gap for WorkflowDesignerPanel's real "New workflow" form, at
    // FREE's 3-workflow ceiling. ---
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });

    // WorkflowDesignerPanel.tsx:243-247's "Limit reached" badge (which
    // replaces the "New workflow" button entirely) must NOT appear.
    await expect(page.getByText("Limit reached")).toHaveCount(0);

    const newWorkflowButton = page.getByRole("button", { name: /^new workflow$/i });
    await expect(
      newWorkflowButton,
      "the 'New workflow' button was replaced by the Limit-reached badge for a PREMIUM tenant at FREE's workflow ceiling"
    ).toBeVisible({ timeout: 30_000 });
    await expect(newWorkflowButton).toBeEnabled();
    await newWorkflowButton.click();

    const fourthWorkflowName = `E2E Premium 4th Workflow ${Date.now()}`;
    const workflowNameField = page.getByPlaceholder(/New workflow name/i);
    await expect(workflowNameField).toBeVisible({ timeout: 15_000 });
    await workflowNameField.fill(fourthWorkflowName);
    await page.getByRole("button", { name: /^create workflow$/i }).click();

    await expect(page.getByText(fourthWorkflowName).first()).toBeVisible({ timeout: 30_000 });

    testInfo.annotations.push({
      type: "data-left-behind",
      description:
        `Provisioned a real ${DOMAIN} PREMIUM tenant "${identity.orgName}" (${identity.email}) with 6 real agents ` +
        `(incl. "${sixthAgentName}") and 4 real workflows (incl. "${fourthWorkflowName}") - clean up manually.`,
    });

    expect(errors.unexpected(), "unexpected console/network errors").toEqual([]);
  });
});
