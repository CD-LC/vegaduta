import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";

// Workflow EXECUTION smoke - distinct from console-core-smoke.spec.ts's
// "Workflow builder smoke", which only creates a workflow and saves the
// starter graph without ever running it. This spec actually triggers a real
// run (WorkflowCanvas.tsx's "Run now" button -> api.runWorkflow, a real
// POST /api/workflows/{id}/run against the real OrchestratorService/
// WorkflowExecutionEngine) and polls it to a terminal state, then confirms a
// run-history entry and the dedicated run-log page both reflect it.
//
// Uses the starter graph as-is (one bare "Trigger" node, no agent/tool
// nodes - see nodeDefs.ts's starterGraph()) rather than building a more
// elaborate graph through the canvas UI: it's the simplest workflow the
// product lets a brand-new tenant run, it needs no BYOK/hosted-pool LLM call
// (so this test isn't gated on model availability or slow/costly inference),
// and it's real product behavior - "Run now" is enabled the moment a
// workflow exists, not just once it has agent nodes wired in.
//
// Selectors pinned by reading the real routed components (not guessed):
// web/app/components/WorkflowDesignerPanel.tsx (list/create),
// web/app/components/workflow/WorkflowCanvas.tsx (canvas + run modal + run
// inspector), web/app/workflows/[id]/runs/[runId]/page.tsx (full run-log
// page) - and their translation strings in web/messages/en.json, since none
// of this UI hardcodes English inline.

test.describe("Workflow execution - real run to a terminal state", () => {
  test("Run now executes the workflow and a completed run appears in history + the run-log page", async ({
    page,
  }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "provisions a real tenant - run on one project only");
    // 25min, matching console-core-smoke.spec.ts's convention - the prior
    // 15min budget undercut helpers/mailinator.ts's own documented up-to-20min
    // polling ceiling, so this test could never actually reach that helper's
    // full retry budget before Playwright's own test timeout cut it off first.
    test.setTimeout(25 * 60 * 1000);

    await signUpFreshTenant(page, "wfexec");

    // --- Create a fresh workflow (same flow as console-core-smoke.spec.ts) ---
    await page.goto("/workflows");
    const workflowName = `E2E Run ${Date.now()}`;
    await page.getByRole("button", { name: "New workflow", exact: true }).click();
    await page.getByPlaceholder(/New workflow name/i).fill(workflowName);
    await page.getByRole("button", { name: "Create workflow" }).click();
    await page.waitForURL(/\/workflows\/[^/]+/, { timeout: 20_000 });

    const workflowId = page.url().split("/workflows/")[1].split(/[/?]/)[0];

    // Canvas renders with the starter graph - one Trigger node, no edges.
    await expect(page.locator(".react-flow")).toBeVisible({ timeout: 20_000 });
    await expect(page.locator(".react-flow__node").first()).toBeVisible({ timeout: 15_000 });

    // Before running: the inspector is hidden until it has something to show
    // (studio layout, 2026-09-29) - open it explicitly, then: no run yet.
    await expect(page.getByText("No run yet").first()).toBeHidden();
    await page.getByRole("button", { name: "Show or hide the inspector" }).click();
    await expect(page.getByText("No run yet").first()).toBeVisible();

    // --- Trigger a real execution ---
    // "Run now" opens a modal (runtime comment/attachments/params, all
    // optional) rather than firing immediately.
    await page.getByRole("button", { name: "Run now" }).click();
    await expect(page.getByLabel(/Run comment/i)).toBeVisible({ timeout: 10_000 });
    await page.getByLabel(/Run comment/i).fill("e2e workflow-execution.spec.ts run");

    // The modal's confirm button is the real dispatch - WorkflowCanvas.tsx's
    // runWithRuntimeInputs() persists the graph as DRAFT then POSTs
    // /api/workflows/{id}/run with background: true, closing this modal as
    // soon as the RUNNING row exists (not once the run finishes).
    await page.getByRole("button", { name: "▶ Run" }).click();

    // Modal closes immediately; the run inspector on the right now shows a
    // real run id and a live/terminal status pulled from the real backend.
    await expect(page.getByLabel(/Run comment/i)).toBeHidden({ timeout: 10_000 });
    await expect(page.locator(".ax-wf-run-id")).toContainText("Run #", { timeout: 20_000 });

    // Poll to a terminal state. The client itself polls every 1.2s
    // (WorkflowCanvas.tsx's runWithRuntimeInputs) and updates
    // `.ax-wf-run-status`'s text to the real WorkflowRun.status once
    // terminal - a bare Trigger-only graph should reach COMPLETED almost
    // immediately, but this generous timeout absorbs real staging latency
    // (cold containers, concurrent E2E load) rather than a fixed sleep.
    const statusEl = page.locator(".ax-wf-run-status");
    await expect(statusEl).toContainText(/COMPLETED|FAILED|CANCELLED/, { timeout: 4 * 60_000 });

    // The real, product-defined success bar for this test: the run actually
    // completed, not merely reached some terminal state.
    await expect(statusEl).toContainText("COMPLETED");

    // The Trigger step itself shows a real "OK" trace entry, not just an
    // overall status with no step detail.
    await expect(page.getByText("Trigger").first()).toBeVisible();

    // --- Run history reflects it ---
    await page.getByRole("tab", { name: "History" }).click();
    const historyRow = page.locator(".ax-wf-history-row").first();
    await expect(historyRow).toBeVisible({ timeout: 15_000 });
    await expect(historyRow).toContainText("COMPLETED");

    // --- The dedicated full run-log page also reflects it ---
    await page.getByRole("link", { name: "View full run log" }).click();
    await page.waitForURL(/\/workflows\/[^/]+\/runs\/[^/]+/, { timeout: 15_000 });
    await expect(page.getByText("RUN SUMMARY")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("COMPLETED", { exact: false }).first()).toBeVisible();
    await expect(page.getByText("STEP-BY-STEP")).toBeVisible();
  });
});
