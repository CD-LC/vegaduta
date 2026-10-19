// Business case: a console user activates an LLM provider, creates an
// agent, and has a conversation with it. Uses the `consolePage` fixture
// (see e2e/fixtures.js) because these panels only exist for a non-admin
// console user, who a tenant-admin never becomes.
//
// Nav split: Providers/Agents/Playground used to all live on "/"; each is
// now its own route (see AppShell.js), so every test here navigates
// explicitly instead of relying on `consolePage` landing on the right panel.
const { test, expect, signupTenant, createConsoleUser, loginAndSetPermanentPassword, pickCustomSelect } = require("../fixtures");

test.describe("Providers, agents, and chat", () => {
  test("an invalid provider API key is rejected with a clear error", async ({ consolePage: page }) => {
    await page.goto("/providers");
    await pickCustomSelect(page, page.getByTestId("provider-activate-select"), "ANTHROPIC");
    await page.fill('input[placeholder="API key"]', "sk-ant-definitely-not-a-real-key");
    await page.click('button:has-text("Activate")');
    await expect(page.locator(".error-box")).toBeVisible({ timeout: 30_000 });
  });

  test("creating an agent without an active provider is blocked client-side", async ({ consolePage: page }) => {
    await page.goto("/agents");
    await page.fill('section:has-text("Agents") input[type="text"] >> nth=0', "Blocked Agent");
    await page.click('button:has-text("Create agent")');
    await expect(page.getByText("Activate a provider above first.")).toBeVisible();
  });

  // B9-PR2 (VegaDuta-PR-Prompts-B9-A1.md) MVP-acceptance journey: a
  // FREE-plan tenant creating an agent and chatting with it via the
  // platform-hosted pool - no BYOK key, no E2E_ANTHROPIC_API_KEY, exactly
  // the "zero real API keys" path B9-PR1's mock-provider + seeded
  // control_plane.platform_hosted_llm_provider row exist for.
  //
  // test.fixme'd, not un-skipped: read AgentWorkbench.tsx (web/app/
  // components/agents/AgentWorkbench.tsx, ~line 129) while writing this -
  // its create-agent block condition is `activeProviders.length === 0`,
  // where `activeProviders` comes only from `providers` (GET /api/providers,
  // BYOK LlmProviderCredential rows). It does not check
  // tenantPlan/usePlatformLlm or the platform-hosted pool at all - so a
  // fresh FREE-tier signup (which, per ProvidersPanel.tsx's own comment,
  // "never activate[s] a BYOK provider at all... they route through the
  // platform-hosted pool") is unconditionally blocked from creating ANY
  // agent today, even with a working platform_hosted_llm_provider row
  // present. This is a real product gap, not a test bug - core-business
  // scope (core/providers + this same AgentWorkbench.tsx block) owns the
  // fix, not infra-ci. Steps below are written to the real, current
  // selectors so this is ready to un-skip the moment that gate accounts for
  // the platform-hosted pool - do not delete or rewrite this test to work
  // around the block; un-skip it once the underlying gate is fixed.
  //
  // Same `{ page, request }` + manual signup pattern as the
  // E2E_ANTHROPIC_API_KEY test above, for the same reason (avoid burning a
  // SignupRateLimiter attempt on a run that's going to skip immediately).
  test("@mvp-acceptance FREE-tier agent creation and chat via the platform-hosted pool (mock-provider in CI)", async ({ page, request }) => {
    test.fixme(true, "RE-DIAGNOSED 2026-09-17, and the original description is now out of date: AgentWorkbench.tsx:601 no longer ignores the hosted pool outright - it gates on `!onPlatformLlm && activeProviders.length === 0`, and :738 defines `onPlatformLlm = isFreePlan || tenantUsePlatformLlm`. So a FREE tenant is fine. What is still broken is the COMPLIMENTARY-GRANT case: every signup now gets 3 months of UNLIMITED (TenantProvisioningRecorder:106-108), so tenantPlan !== 'FREE' and usePlatformLlm is false, making onPlatformLlm false and blocking agent creation for the first 3 months - even though core serves them, since TenantLimitsServiceImpl#usesPlatformPool (:108-120) returns true for a live comp grant. bf266108 fixed exactly this gap in the backend and the frontend was not updated with it. Fix: drive the gate off the authoritative usesPlatformPool flag already returned by /usage-quota (web/app/lib/api.ts:338) instead of re-deriving it. web scope owns AgentWorkbench.tsx. expires:2026-12-31");

    const tenant = await signupTenant(request);
    const consoleUser = await createConsoleUser(request, tenant);
    await loginAndSetPermanentPassword(page, {
      username: consoleUser.username,
      temporaryPassword: consoleUser.temporaryPassword,
      newPassword: consoleUser.permanentPassword,
    });

    // No /providers activation step here on purpose - a FREE tenant has
    // nothing to activate; the whole point of this journey is that agent
    // creation should just work off the seeded platform-hosted pool.
    await page.goto("/agents");
    await page.fill('section:has-text("Agents") input[type="text"] >> nth=0', "Mock Provider Chat Agent");
    await page.fill("textarea", "You are a terse test assistant. Reply with exactly one short sentence.");
    await page.click('button:has-text("Create agent")');
    await expect(page.locator(".success-box")).toContainText("created");

    const agentRow = page.locator(".list-item", { hasText: "Mock Provider Chat Agent" });
    await agentRow.getByRole("link", { name: "Chat →" }).click();
    await expect(page).toHaveURL(/\/playground\?agent=/);

    await expect(page.getByRole("heading", { name: /Chat — Mock Provider Chat Agent/ })).toBeVisible();
    await page.fill('input[placeholder="Ask something…"]', "Say hello in one short sentence.");
    await page.click('button:has-text("Send")');

    // mock-provider/main.py echoes the last user message back in a fixed
    // "mock-provider received: ..." shape (see B9-PR1) - assert on that
    // exact prefix, not just "non-empty", so this genuinely proves the
    // platform-hosted pool routed to mock-provider and not some other path.
    await expect(page.locator(".chat-turn").last()).toContainText("mock-provider received", { timeout: 60_000 });
    await expect(page.locator(".error-box")).toHaveCount(0);
  });

  // Requires a real, working Anthropic API key - this is the only spec file
  // in the suite that talks to a real upstream LLM, so it's opt-in rather
  // than part of the default run. Set E2E_ANTHROPIC_API_KEY to enable it.
  //
  // Deliberately takes `{ page, request }` rather than the `consolePage`
  // fixture: `consolePage` signs up a fresh tenant as part of fixture
  // resolution, which happens BEFORE this test's body (and therefore
  // before the skip check below) ever runs - meaning every skipped run of
  // this test would still burn one of SignupRateLimiter's 5-per-hour
  // attempts for nothing. Signing up manually after the skip check avoids
  // that - see e2e/README.md's rate-limit note for why this matters.
  test("activate a provider, create an agent, and chat with it", async ({ page, request }) => {
    test.skip(!process.env.E2E_ANTHROPIC_API_KEY, "Set E2E_ANTHROPIC_API_KEY to run this test.");

    const tenant = await signupTenant(request);
    const consoleUser = await createConsoleUser(request, tenant);
    await loginAndSetPermanentPassword(page, {
      username: consoleUser.username,
      temporaryPassword: consoleUser.temporaryPassword,
      newPassword: consoleUser.permanentPassword,
    });

    await page.goto("/providers");
    await page.fill('input[placeholder="API key"]', process.env.E2E_ANTHROPIC_API_KEY);
    await page.click('button:has-text("Activate")');
    await expect(page.locator(".success-box")).toContainText("activated successfully");

    await page.goto("/agents");
    await page.fill('section:has-text("Agents") input[type="text"] >> nth=0', "E2E Chat Agent");
    await page.fill("textarea", "You are a terse test assistant. Reply with exactly one short sentence.");
    await page.click('button:has-text("Create agent")');
    await expect(page.locator(".success-box")).toContainText("created");

    // The "Chat →" link on the freshly created agent's row is the real,
    // product-intended path into the Playground - not a direct goto - since
    // it's also how a real person would get there, carrying the agent id via
    // the ?agent= query param.
    const agentRow = page.locator(".list-item", { hasText: "E2E Chat Agent" });
    await agentRow.getByRole("link", { name: "Chat →" }).click();
    await expect(page).toHaveURL(/\/playground\?agent=/);

    await expect(page.getByRole("heading", { name: /Chat — E2E Chat Agent/ })).toBeVisible();
    await page.fill('input[placeholder="Ask something…"]', "Say hello in one short sentence.");
    await page.click('button:has-text("Send")');

    // Streamed response - wait for the assistant turn to contain real text
    // rather than a fixed sleep, since streaming duration varies with the
    // model.
    await expect(page.locator(".chat-turn").last()).not.toHaveText("", { timeout: 60_000 });
    await expect(page.locator(".error-box")).toHaveCount(0);
  });
});
