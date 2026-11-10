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
    // A tenant with no workflows yet has this button twice (page header and empty state): either one opens the form.
    await page.getByRole("button", { name: "New workflow", exact: true }).first().click();
    await page.getByPlaceholder(/New workflow name/i).fill(workflowName);
    await page.getByRole("button", { name: "Create workflow" }).click();
    await page.waitForURL(/\/workflows\/[^/]+/, { timeout: 20_000 });

    const workflowId = page.url().split("/workflows/")[1].split(/[/?]/)[0];

    // Canvas renders with the starter graph - one Trigger node, no edges.
    await expect(page.locator(".react-flow")).toBeVisible({ timeout: 20_000 });
    await expect(page.locator(".react-flow__node").first()).toBeVisible({ timeout: 15_000 });

    // Before running: the inspector is hidden until it has something to show
    // (studio layout, 2026-09-29) - open it explicitly, then: no run yet.
    // The inspector's starting state has changed between builds (hidden, then shown by default), so open it
    // only when it is not already showing; clicking the toggle on an open inspector would close it.
    // Exact match: the empty-state paragraph below the run chip also starts with "No run yet".
    const noRunYet = page.getByText("No run yet", { exact: true });
    if (!(await noRunYet.isVisible().catch(() => false))) {
      await page.getByRole("button", { name: "Show or hide the inspector" }).click();
    }
    await expect(noRunYet).toBeVisible();

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

    // The run is followed in the "Run being viewed" banner (the older run inspector, with .ax-wf-run-id and
    // .ax-wf-run-status, is gone). A bare Trigger-only graph reaches Completed almost at once, but the generous
    // timeout absorbs real latency (cold containers, concurrent load) rather than a fixed sleep.
    const viewed = page.getByRole("region", { name: "Run being viewed" });
    await expect(viewed).toContainText("Viewing run #1", { timeout: 20_000 });
    const status = viewed.getByRole("status");
    await expect(status).toHaveText(/Completed|Failed|Cancelled/, { timeout: 4 * 60_000 });

    // The real, product-defined success bar for this test: the run actually completed, not merely reached some
    // terminal state.
    await expect(status).toHaveText("Completed");

    // The Trigger step itself shows in the flight recorder, not just an overall status with no step detail.
    await expect(page.getByRole("region", { name: "Run flight recorder" })).toContainText("Trigger");

    // --- Run history reflects it ---
    const historyRow = page.getByRole("listbox", { name: "Workflow runs" }).getByRole("option").first();
    await expect(historyRow).toBeVisible({ timeout: 15_000 });
    await expect(historyRow).toContainText("Completed");

    // --- The dedicated full run-log page also reflects it ---
    // Opening a step of the viewed run shows its inspector, whose footer links to the run's own page.
    await page.getByRole("application", { name: "Workflow canvas" }).getByText("Trigger").first().click();
    await page.getByRole("button", { name: "Open full run" }).click();
    await page.waitForURL(/\/workflows\/[^/]+\/runs\/[^/]+/, { timeout: 15_000 });
    await expect(page.getByText("RUN SUMMARY")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("COMPLETED", { exact: false }).first()).toBeVisible();
    await expect(page.getByText("STEP-BY-STEP")).toBeVisible();
  });
});
