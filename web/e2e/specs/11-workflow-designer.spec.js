// Business case: building a workflow on the visual canvas (#114/#501) - the
// flagship "Build" feature. Two halves, per e2e/README.md's own convention
// for a feature this deep:
//
//  1. Browser-surface tests below drive the real canvas (list page ->
//     create -> palette -> node config drawer -> Save Draft -> Validate) -
//     everything reachable without needing @xyflow/react drag-to-connect
//     gestures, which Playwright can't reliably simulate against React
//     Flow's SVG handles.
//  2. The webhook/schedule trigger boxes only render for an ACTIVE workflow
//     viewed by a tenant-admin (see WorkflowCanvas.js's `workflow.status ===
//     "ACTIVE" && isAdmin` guards) - reaching ACTIVE from the UI requires a
//     real trigger->output connection, which is exactly the drag gesture
//     above can't drive headlessly. That business logic (enable/disable
//     webhook, set/clear a schedule) is instead exercised directly against
//     WorkflowController's REST API via the `request` fixture, building a
//     valid connected graph as plain JSON - the same shape WorkflowCanvas.js
//     itself persists (see persistGraph/api.updateWorkflow).
//
// Uses `adminPage`, not `consolePage` - the trigger-channel/webhook/schedule
// boxes are admin-only chrome (see the guard above), and a workflow's
// creation/edit permissions aren't restricted to non-admins the way the
// Providers/Agents/Playground panels are.
const { test, expect, signupTenant, keycloakPasswordGrantToken, API_BASE } = require("../fixtures");

test.describe("Workflow Designer canvas", () => {
  test("@mvp-acceptance create a workflow, add and configure an Output node, and save it as a draft", async ({ adminPage: page }) => {
    await page.goto("/workflows");

    await page.fill('input[placeholder="New workflow name (e.g. Support Triage)"]', "E2E Draft Workflow");
    await page.fill('input[placeholder="Description (optional)"]', "Created by the Playwright suite.");
    await page.click('button:has-text("+ New Workflow")');

    // Creating navigates straight into the new workflow's own canvas route.
    await page.waitForURL(/\/workflows\/[a-f0-9-]+$/, { timeout: 20_000 });

    // starterGraph() seeds every brand-new workflow with exactly one Trigger
    // node already on the canvas (nodeDefs.js) - nothing to add for that.
    await expect(page.locator(".react-flow__node", { hasText: "Trigger" })).toBeVisible({ timeout: 15_000 });

    // Node palette (.ax-wf-palette > .ax-wf-palette-item, text = the node
    // def's label - see nodeDefs.js's NODE_DEFS) - click-to-add rather than
    // drag, per addNodeByClick in WorkflowCanvas.js.
    await page.locator(".ax-wf-palette-item", { hasText: "Output" }).click();
    await expect(page.locator(".react-flow__node", { hasText: "Output" })).toBeVisible({ timeout: 10_000 });

    // Selecting the new node opens NodeConfigDrawer on the right - configure
    // it to write to a local file, the simplest Output channel with no
    // external credential to fake.
    await page.locator(".react-flow__node", { hasText: "Output" }).click();
    await page.getByTestId("output-channel-select").locator(".select-trigger, select, button").first().click();
    await page.locator(".select-popover .select-option", { hasText: "Local file" }).click();
    await expect(page.getByTestId("output-filetype-select")).toBeVisible();

    await page.click('button:has-text("Save Draft")');
    await expect(page.locator(".hud-toast-message")).toContainText("Draft saved.", { timeout: 15_000 });
  });

  test("validating an unfinished graph (Trigger not connected to anything) lists the problems", async ({ adminPage: page }) => {
    await page.goto("/workflows");
    await page.fill('input[placeholder="New workflow name (e.g. Support Triage)"]', "E2E Invalid Workflow");
    await page.click('button:has-text("+ New Workflow")');
    await page.waitForURL(/\/workflows\/[a-f0-9-]+$/, { timeout: 20_000 });

    // The starter graph is just a lone, unconnected Trigger - Validate
    // against OrchestratorService#validate's real rules should always find
    // at least one problem for it (e.g. no Output/terminal step reachable).
    await page.click('button:has-text("Validate")');

    const dialog = page.locator(".wf-validate-modal[role='dialog']");
    await expect(dialog).toBeVisible({ timeout: 15_000 });
    await expect(dialog.locator("h2")).toHaveText("This workflow isn't ready yet");
    await expect(dialog.locator(".wf-validate-list li").first()).toBeVisible();

    await dialog.getByRole("button", { name: "Got it" }).click();
    await expect(dialog).toBeHidden();
  });

  // The three-workflow free-tier cap (WorkflowDesignerPanel.js's
  // WORKFLOW_LIMIT, backed server-side by TenantLimitsServiceImpl) - create
  // up to the limit, then confirm the form flips to the upgrade banner
  // instead of letting a fourth one through.
  test("hitting the free-tier workflow cap shows the upgrade banner instead of a create form", async ({ adminPage: page }) => {
    await page.goto("/workflows");
    for (let i = 0; i < 3; i += 1) {
      await page.fill('input[placeholder="New workflow name (e.g. Support Triage)"]', `E2E Cap Workflow ${i}`);
      await page.click('button:has-text("+ New Workflow")');
      await page.waitForURL(/\/workflows\/[a-f0-9-]+$/, { timeout: 20_000 });
      await page.goto("/workflows");
    }
    await expect(page.locator(".ax-wflist-limit-pill")).toContainText("Limit reached");
    await expect(page.locator(".ax-wflist-limit-banner")).toContainText("upgrade your plan");
    await expect(page.locator('input[placeholder="New workflow name (e.g. Support Triage)"]')).toHaveCount(0);
  });
});

test.describe("Workflow triggers (API-level - ACTIVE + webhook/schedule)", () => {
  test("activate a real trigger->output graph, then enable/disable its webhook and schedule", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const authHeaders = { Authorization: `Bearer ${token}` };

    const created = await request.post(`${API_BASE}/api/workflows`, {
      headers: authHeaders,
      data: {
        name: "E2E API Workflow",
        description: "Built directly via the REST API for trigger coverage.",
        graph: {
          nodes: [
            { id: "trigger-1", type: "trigger", position: { x: 0, y: 0 }, data: { label: "Trigger", config: {} } },
            {
              id: "output-1",
              type: "output",
              position: { x: 300, y: 0 },
              data: { label: "Output", config: { channel: "log" } },
            },
          ],
          edges: [{ id: "e-trigger-output", source: "trigger-1", target: "output-1" }],
        },
      },
    });
    expect(created.status(), await created.text()).toBe(201);
    const workflow = await created.json();

    const activated = await request.put(`${API_BASE}/api/workflows/${workflow.id}`, {
      headers: authHeaders,
      data: { name: workflow.name, description: workflow.description, graph: workflow.graph, status: "ACTIVE" },
    });
    expect(activated.status(), await activated.text()).toBe(200);
    expect((await activated.json()).status).toBe("ACTIVE");

    // Webhook trigger - GET starts disabled, POST enables and returns a
    // token, DELETE disables it again (see api.js's get/enable/disable
    // WorkflowWebhook wrappers and WorkflowController's matching routes).
    const webhookBefore = await request.get(`${API_BASE}/api/workflows/${workflow.id}/webhook`, { headers: authHeaders });
    expect(webhookBefore.status()).toBe(200);
    expect((await webhookBefore.json()).enabled).toBeFalsy();

    const webhookEnabled = await request.post(`${API_BASE}/api/workflows/${workflow.id}/webhook`, { headers: authHeaders });
    expect(webhookEnabled.status()).toBe(200);
    const webhookBody = await webhookEnabled.json();
    expect(webhookBody.enabled).toBe(true);
    expect(webhookBody.token).toBeTruthy();

    const webhookDisabled = await request.delete(`${API_BASE}/api/workflows/${workflow.id}/webhook`, { headers: authHeaders });
    expect(webhookDisabled.status()).toBe(200);
    expect((await webhookDisabled.json()).enabled).toBe(false);

    // Schedule trigger - POST with a cron expression enables it, DELETE
    // clears it (WorkflowCanvas.js's Schedule box builds the same cron
    // string client-side from the frequency/day/time pickers before calling
    // this - see applySchedule()).
    const scheduleEnabled = await request.post(`${API_BASE}/api/workflows/${workflow.id}/schedule`, {
      headers: authHeaders,
      data: { cron: "0 9 * * *" }, // "every day at 09:00 UTC"
    });
    expect(scheduleEnabled.status(), await scheduleEnabled.text()).toBe(200);
    const scheduleBody = await scheduleEnabled.json();
    expect(scheduleBody.enabled).toBe(true);
    expect(scheduleBody.nextRunAt).toBeTruthy();

    const scheduleDisabled = await request.delete(`${API_BASE}/api/workflows/${workflow.id}/schedule`, { headers: authHeaders });
    expect(scheduleDisabled.status()).toBe(200);
    expect((await scheduleDisabled.json()).enabled).toBe(false);
  });

  test("@mvp-acceptance running a workflow synchronously returns a completed run with its output", async ({ request }) => {
    const tenant = await signupTenant(request);
    const token = await keycloakPasswordGrantToken(request, tenant);
    const authHeaders = { Authorization: `Bearer ${token}` };

    const created = await request.post(`${API_BASE}/api/workflows`, {
      headers: authHeaders,
      data: {
        name: "E2E Run Workflow",
        description: "",
        graph: {
          nodes: [
            { id: "trigger-1", type: "trigger", position: { x: 0, y: 0 }, data: { label: "Trigger", config: {} } },
            {
              id: "output-1",
              type: "output",
              position: { x: 300, y: 0 },
              data: { label: "Output", config: { channel: "log" } },
            },
          ],
          edges: [{ id: "e-trigger-output", source: "trigger-1", target: "output-1" }],
        },
      },
    });
    const workflow = await created.json();

    const run = await request.post(`${API_BASE}/api/workflows/${workflow.id}/runs`, {
      headers: authHeaders,
      data: { input: "Playwright E2E run", requestedBy: "e2e-suite" },
    });
    expect(run.status(), await run.text()).toBe(200);
    const runBody = await run.json();
    expect(["COMPLETED", "RUNNING", "FAILED"]).toContain(runBody.status);

    const runs = await request.get(`${API_BASE}/api/workflows/${workflow.id}/runs`, { headers: authHeaders });
    expect(runs.status()).toBe(200);
    expect((await runs.json()).length).toBeGreaterThanOrEqual(1);
  });
});
