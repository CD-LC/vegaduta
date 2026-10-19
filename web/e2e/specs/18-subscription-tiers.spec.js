// Business case: FREE-tier caps and the real self-serve upgrade flow (#811,
// Subscription Plan & GTM Strategy doc). Every "Upgrade your plan" surface
// in the console (AgentsPanel's 5-agent cap, WorkflowDesignerPanel's
// 3-workflow cap already covered in 11-workflow-designer.spec.js, and
// WorkflowCanvas's free-tier edit-lock) opens the same shared
// UpgradePrompt.js dialog, which calls POST /api/tenant/upgrade
// (TenantUpgradeController) for a Razorpay subscription id, then hands off
// to Razorpay's own hosted Standard Checkout - a real payment can't be
// completed in this suite, so coverage stops at "the dialog opens with the
// right plans and the API call it makes behaves correctly," the same
// boundary 11-workflow-designer.spec.js draws around Activate+webhook.
//
// AGENT_LIMIT (AgentsPanel.js) is 5 - hit via the API directly (agent
// creation only requires a `providerName` enum value on the request, not a
// genuinely active/working credential - see AgentCreateRequest's javadoc)
// rather than through the UI, since the UI's create form separately blocks
// on "no active provider" (02-providers-agents-chat.spec.js), which would
// conflate two different gates in one test.
const { test, expect, signupTenant, keycloakPasswordGrantToken, createConsoleUser, loginAndSetPermanentPassword, API_BASE } = require("../fixtures");

const AGENT_LIMIT = 5;

test.describe("Free-tier agent cap + upgrade prompt", () => {
  test("hitting the 5-agent cap shows the upgrade banner, and the dialog opens with both plans", async ({ page, request }) => {
    const tenant = await signupTenant(request);
    const adminToken = await keycloakPasswordGrantToken(request, tenant);

    for (let i = 0; i < AGENT_LIMIT; i += 1) {
      const res = await request.post(`${API_BASE}/api/agents`, {
        headers: { Authorization: `Bearer ${adminToken}` },
        data: { name: `E2E Cap Agent ${i}`, systemPrompt: "You are a test agent.", providerName: "ANTHROPIC" },
      });
      expect(res.status(), await res.text()).toBe(201);
    }

    // A 6th create is rejected server-side too - not just a UI nicety.
    const overLimit = await request.post(`${API_BASE}/api/agents`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: { name: "E2E One Too Many", systemPrompt: "You are a test agent.", providerName: "ANTHROPIC" },
    });
    expect(overLimit.status()).toBe(403);
    expect(await overLimit.text()).toContain("upgrade your plan");

    const consoleUser = await createConsoleUser(request, tenant);
    await loginAndSetPermanentPassword(page, {
      username: consoleUser.username,
      temporaryPassword: consoleUser.temporaryPassword,
      newPassword: consoleUser.permanentPassword,
    });
    await page.goto("/agents");

    await expect(page.getByText(`${AGENT_LIMIT} of ${AGENT_LIMIT} agents used`)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(`You've used all ${AGENT_LIMIT} agents included in your plan.`)).toBeVisible();

    await page.getByRole("button", { name: "Upgrade your plan" }).click();
    const dialog = page.getByRole("dialog", { name: "Upgrade your plan" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText(`You've used all ${AGENT_LIMIT} agents included in your plan.`)).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Upgrade to Premium" })).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Upgrade to Unlimited" })).toBeVisible();

    // Escape closes it, same as the backdrop/X button.
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
  });
});

test.describe("Tenant upgrade API (TenantUpgradeController)", () => {
  test("the upgrade endpoint rejects requests with no bearer token", async ({ request }) => {
    const res = await request.post(`${API_BASE}/api/tenant/upgrade`, { data: { targetPlan: "PREMIUM" } });
    expect(res.status()).toBe(401);
  });

  test("a non-admin console member cannot initiate an upgrade - admin-only, same as other tenant-settings writes", async ({ request }) => {
    const tenant = await signupTenant(request);
    const consoleUser = await createConsoleUser(request, tenant);
    const nonAdminToken = await keycloakPasswordGrantToken(request, {
      adminEmail: consoleUser.username,
      adminPassword: consoleUser.temporaryPassword,
    });
    const res = await request.post(`${API_BASE}/api/tenant/upgrade`, {
      headers: { Authorization: `Bearer ${nonAdminToken}` },
      data: { targetPlan: "PREMIUM" },
    });
    expect(res.status()).toBe(403);
  });

  test("a tenant-admin initiating an upgrade to PREMIUM gets a real gateway subscription handoff", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.post(`${API_BASE}/api/tenant/upgrade`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { targetPlan: "PREMIUM" },
    });
    // Only meaningful if this deployment has real Razorpay keys configured
    // (RazorpaySubscriptionGateway) - skip rather than fail otherwise, same
    // "opt-in when the real credential isn't present" posture used
    // throughout this suite for external payment/LLM/messaging providers.
    test.skip(res.status() >= 500, "Razorpay isn't configured on this deployment.");
    expect(res.status(), await res.text()).toBe(200);
    const body = await res.json();
    expect(body.gatewaySubscriptionId).toBeTruthy();
    expect(body.gatewayPublicKey).toBeTruthy();
  });

  test("an invalid target plan is rejected with a validation error, not a 500", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const res = await request.post(`${API_BASE}/api/tenant/upgrade`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { targetPlan: "NOT_A_REAL_PLAN" },
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });
});
