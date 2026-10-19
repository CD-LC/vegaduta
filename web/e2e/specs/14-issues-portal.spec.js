// Business case: the Admin Issues portal (#425/#426) - deduplicated failure
// records (GlobalExceptionHandler's catch-all, a FAILED WorkflowRun, or a
// failed ToolCallLog) surfaced for a tenant-admin to triage. Recorded
// SYNCHRONOUSLY at the point of failure (not via the Kafka/outbox pipeline
// the rest of this platform's domain events use - see PlatformIssueService's
// javadoc), specifically so an issue is still captured even if the event
// broker itself is down.
//
// admin/issues/page.js redirects any non-admin straight back to "/"
// (useAuth().isAdmin check) - every UI test here uses `adminPage`, and the
// one non-admin test explicitly proves that redirect rather than assuming it.
const { test, expect, signupTenant, keycloakPasswordGrantToken, createConsoleUser, API_BASE } = require("../fixtures");

test.describe("Admin Issues portal", () => {
  test("a non-admin console user is redirected away from /admin/issues", async ({ consolePage: page }) => {
    await page.goto("/admin/issues");
    await expect(page).toHaveURL((url) => url.pathname === "/" || url.pathname === "", { timeout: 15_000 });
  });

  test("a fresh tenant's Issues page shows the empty state and its status tabs", async ({ adminPage: page }) => {
    await page.goto("/admin/issues");
    await expect(page.getByRole("heading", { name: "Issues" })).toBeVisible({ timeout: 15_000 });

    // Defaults to the "Open" tab - a brand-new tenant has triggered nothing
    // yet, so it should read as clean.
    await expect(page.getByText(/No open issues\. Nothing to triage\./)).toBeVisible({ timeout: 15_000 });

    for (const label of ["All", "Acknowledged", "Resolved"]) {
      await page.getByRole("button", { name: new RegExp(`^${label}`) }).click();
      await expect(page.locator(".panel")).toBeVisible();
    }
  });
});

test.describe("Admin Issues portal (API-level - real failure -> triage lifecycle)", () => {
  test("a failed workflow run is recorded as an issue, then can be acknowledged and resolved", async ({ request }) => {
    const tenant = await signupTenant(request);
    // tenant.adminEmail/adminPassword (the signup flow's first user) already
    // carries tenant-admin - Issues management is admin-only, so this token
    // is used directly rather than creating a second console user.
    const token = await keycloakPasswordGrantToken(request, tenant);
    const authHeaders = { Authorization: `Bearer ${token}` };

    // A Trigger -> Agent graph where the Agent node points at an agentId
    // that doesn't exist for this tenant - guaranteed to fail at run time
    // (WorkflowNodeExecutor's Agent-node lookup) without depending on any
    // real LLM provider being configured.
    const created = await request.post(`${API_BASE}/api/workflows`, {
      headers: authHeaders,
      data: {
        name: "E2E Issue-Generating Workflow",
        description: "Deliberately references a nonexistent agent so its run fails and lands in the Issues portal.",
        graph: {
          nodes: [
            { id: "trigger-1", type: "trigger", position: { x: 0, y: 0 }, data: { label: "Trigger", config: {} } },
            {
              id: "agent-1",
              type: "agent",
              position: { x: 300, y: 0 },
              data: { label: "Agent", config: { agentId: "00000000-0000-0000-0000-000000000000" } },
            },
          ],
          edges: [{ id: "e-trigger-agent", source: "trigger-1", target: "agent-1" }],
        },
      },
    });
    expect(created.status(), await created.text()).toBe(201);
    const workflow = await created.json();

    await request.put(`${API_BASE}/api/workflows/${workflow.id}`, {
      headers: authHeaders,
      data: { name: workflow.name, description: workflow.description, graph: workflow.graph, status: "ACTIVE" },
    });

    const run = await request.post(`${API_BASE}/api/workflows/${workflow.id}/runs`, {
      headers: authHeaders,
      data: { input: "trigger the failure", requestedBy: "e2e-suite" },
    });
    // The run endpoint itself should report the failure cleanly (never a
    // raw 500 bubbling out of the executor), whatever status code this
    // deployment uses for "the run object was created but its execution
    // failed".
    expect(run.status()).toBeLessThan(500);

    // GlobalExceptionHandler/WorkflowExecutionEngine record the issue
    // synchronously, but this test still polls briefly rather than asserting
    // on the very next tick, since the run itself may finish asynchronously
    // relative to the POST /runs response.
    let issue = null;
    for (let attempt = 0; attempt < 10 && !issue; attempt += 1) {
      const list = await request.get(`${API_BASE}/api/admin/issues?status=OPEN`, { headers: authHeaders });
      expect(list.status()).toBe(200);
      const issues = await list.json();
      issue = issues.find((i) => i.source === "WORKFLOW_RUN" || i.source === "TOOL_CALL" || i.source === "EXCEPTION");
      if (!issue) await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    expect(issue, "expected a FAILED run against a nonexistent agent to produce at least one OPEN issue").toBeTruthy();
    expect(issue.status).toBe("OPEN");

    const acknowledged = await request.post(`${API_BASE}/api/admin/issues/${issue.id}/acknowledge`, { headers: authHeaders });
    expect(acknowledged.status(), await acknowledged.text()).toBe(200);
    expect((await acknowledged.json()).status).toBe("ACKNOWLEDGED");

    const resolved = await request.post(`${API_BASE}/api/admin/issues/${issue.id}/resolve`, { headers: authHeaders });
    expect(resolved.status(), await resolved.text()).toBe(200);
    expect((await resolved.json()).status).toBe("RESOLVED");

    // Confirms the status filter itself is real, not just decorative - the
    // now-resolved issue must show up under ?status=RESOLVED and no longer
    // count as OPEN.
    const resolvedList = await request.get(`${API_BASE}/api/admin/issues?status=RESOLVED`, { headers: authHeaders });
    expect((await resolvedList.json()).some((i) => i.id === issue.id)).toBe(true);
    const openList = await request.get(`${API_BASE}/api/admin/issues?status=OPEN`, { headers: authHeaders });
    expect((await openList.json()).some((i) => i.id === issue.id)).toBe(false);
  });

  test("Issues endpoints reject a token from a non-admin tenant member", async ({ request }) => {
    const tenant = await signupTenant(request);
    const consoleUser = await createConsoleUser(request, tenant);
    const nonAdminToken = await keycloakPasswordGrantToken(request, {
      adminEmail: consoleUser.username,
      adminPassword: consoleUser.temporaryPassword,
    });
    const res = await request.get(`${API_BASE}/api/admin/issues`, {
      headers: { Authorization: `Bearer ${nonAdminToken}` },
    });
    expect(res.status()).toBe(403);
  });
});
