import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";
import { corsHeaders } from "../helpers/console-controls";

// Approvals queue: approve a parked tool call and watch the agent carry on.
//
// Core cannot be made to park a tool call without a model that calls tools (the CI mock provider never does), so this
// spec drives the REAL console in a real browser on a freshly signed-up tenant (admin, real session) and stubs only
// the four network calls that depend on such a call, at the network layer:
//   GET  /api/approvals                              the queue: one PENDING call to get_invoice
//   POST /api/approvals/{id}/approve                 the decision
//   GET  /api/agents/{id}/sessions/{sid}/transcript  what core stored after the decision: first the platform's
//                                                    "approval_result" message (the agent is carrying on), then the
//                                                    agent's reply (finished)
// Everything else (auth, settings such as the expiry time, the page, the components) is real.
// Real side effects: it provisions a tenant on whatever E2E_BASE_URL points at. Nothing is sent to a model.

const AGENT = "11111111-aaaa-4bbb-8ccc-222222222222";
const SESSION = "e2e-session-1";
const REQUEST = "33333333-aaaa-4bbb-8ccc-444444444444";

function pendingRow() {
  return {
    id: REQUEST,
    status: "PENDING",
    actionType: "mcp_tool.get_invoice",
    summary: "Tool call: get_invoice",
    requestedBy: `agent:${AGENT}:session:${SESSION}`,
    createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    payload: JSON.stringify({
      replay: { version: 1, toolName: "get_invoice", goal: "Look up invoice 1042 for the customer" },
      arguments: { invoiceId: "1042" },
    }),
  };
}

test.describe("Approvals queue: approve, then the agent carries on", () => {
  test("approving a tool call shows what the agent does next, from carrying on to finished", async ({ page }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "provisions a real tenant - run on one project only");
    test.setTimeout(10 * 60 * 1000);

    await signUpFreshTenant(page, "approvals");

    let decided: Record<string, unknown> | null = null;
    let transcriptReads = 0;
    const approveCalls: string[] = [];

    await page.route(/\/api\/approvals(\?|$)/, async (route) => {
      if (route.request().method() !== "GET") return route.fallback();
      const status = new URL(route.request().url()).searchParams.get("status");
      const rows = decided ? (status === "PENDING" ? [] : [decided]) : status === "PENDING" || !status ? [pendingRow()] : [];
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        headers: { ...corsHeaders(route), "x-total-count": String(rows.length), "x-has-more": "false" },
        body: JSON.stringify(rows),
      });
    });
    await page.route(new RegExp(`/api/approvals/${REQUEST}/approve$`), async (route) => {
      approveCalls.push(route.request().url());
      decided = { ...pendingRow(), status: "APPROVED", decidedAt: new Date().toISOString(), decidedBy: "e2e" };
      await route.fulfill({ status: 200, contentType: "application/json", headers: corsHeaders(route), body: JSON.stringify(decided) });
    });
    await page.route(new RegExp(`/api/agents/${AGENT}/sessions/${SESSION}/transcript`), async (route) => {
      const marker = { role: "user", content: `<approval_result request="${REQUEST}" tool="get_invoice" outcome="completed">\n{"total":120}\n</approval_result>` };
      let messages: Array<Record<string, string>> = [{ role: "user", content: "Look up invoice 1042" }];
      if (decided) {
        transcriptReads += 1;
        messages = transcriptReads === 1 ? [...messages, marker] : [...messages, marker, { role: "assistant", content: "Invoice 1042 totals 120." }];
      }
      await route.fulfill({ status: 200, contentType: "application/json", headers: corsHeaders(route), body: JSON.stringify({ messages }) });
    });

    await test.step("the queue shows the call, its arguments and what approve and deny each do", async () => {
      await page.goto("/admin/approvals");
      const preview = page.getByTestId("tool-call-preview");
      await expect(preview).toBeVisible({ timeout: 30_000 });
      await expect(preview.getByTestId("tool-call-name")).toHaveText("get_invoice");
      await expect(preview.getByTestId("tool-call-goal")).toContainText("Look up invoice 1042");
      await expect(preview.getByTestId("tool-call-args")).toContainText("1042");
      await expect(preview.getByTestId("tool-call-consequences")).toContainText("get_invoice runs right now");
      // Nothing about the agent carrying on before a decision exists.
      await expect(page.getByTestId("approval-continuation-panel")).toHaveCount(0);
    });

    await test.step("approving sends exactly one decision and the agent is reported as carrying on", async () => {
      await page.getByRole("button", { name: "Approve", exact: true }).click();
      await expect(page.getByTestId("approval-continuing")).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId("approval-continuing")).toContainText("The agent is carrying on with the task.");
      expect(approveCalls).toHaveLength(1);
    });

    await test.step("when the agent's reply is stored the line changes to finished", async () => {
      await expect(page.getByTestId("approval-continuation-completed")).toBeVisible({ timeout: 60_000 });
      await expect(page.getByTestId("approval-continuation-completed")).toContainText("The agent finished carrying on with the task.");
      await expect(page.getByTestId("approval-continuing")).toHaveCount(0);
    });
  });
});
