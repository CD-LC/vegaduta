import { test, expect } from "@playwright/test";
import { signUpFreshTenant } from "../helpers/test-tenant";

// Console core smoke: agent CRUD, workflow builder, knowledge-base upload -
// all exercised on ONE freshly-signed-up FREE tenant (matches this repo's
// "run the real self-service flow, don't fabricate state" posture used by
// checkout-ui.spec.ts). Real side effects on production - see the "Real data
// left behind" note at the bottom of this file's final report.
//
// Selectors were pinned by reading the real routed components, not the
// pre-AXON leftovers still in the tree:
//   - /agents -> AgentWorkbench.tsx (web/app/agents/page.tsx imports this,
//     NOT the older AgentsPanel.tsx, which is kept around unrouted).
//   - /workflows -> WorkflowDesignerPanel.tsx (list) -> /workflows/[id] ->
//     WorkflowCanvas.tsx (the @xyflow/react canvas).
//   - /knowledge -> KnowledgeCollectionsPanel.tsx (sidebar) +
//     KnowledgePanel.tsx (upload + per-file pipeline status).
//
// IMPORTANT FINDING (read before "fixing" the delete step): on a FREE
// tenant, deleting an agent AND deleting a workflow are both paid-plan-only
// capabilities at the UI level (see AgentWorkbench.tsx's isFreePlan branch
// on its Delete button, and WorkflowDesignerPanel.tsx's row-delete button -
// both wrap the button in the shared `upgradeLocked` prop, whose onClick
// literally does `e.preventDefault(); e.stopPropagation();` instead of
// calling the delete handler - see ui/button.tsx). So a FREE tenant cannot
// delete either through the console. This test verifies that gate (button
// is upgrade-locked, agent/workflow survives the click) instead of a real
// delete+disappear, since the latter is impossible for this tenant tier.
// This is a real, previously-unconfirmed billing-gate finding relevant to
// the still-unwritten entitlement-gates-free.spec.ts (see
// e2e/HANDOFF-BILLING-E2E-2026-07-28.md) - worth folding in there too.

test.describe("Console core smoke - agents, workflows, knowledge base", () => {
  test("FREE tenant: agent CRUD (create/edit/persist/delete-is-gated), workflow builder, KB upload", async ({
    page,
  }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "provisions a real tenant - run on one project only");
    test.setTimeout(25 * 60 * 1000);

    await signUpFreshTenant(page, "console");

    // ---------------------------------------------------------------
    // 1. Agent CRUD
    // ---------------------------------------------------------------
    await test.step("Agent CRUD smoke", async () => {
      await page.goto("/agents");

      // Fresh tenant has zero agents -> AgentWorkbench lands in draft mode
      // by default (no agents to auto-select).
      const agentName = `E2E Agent ${Date.now()}`;
      await expect(page.getByRole("button", { name: "Save draft" }).first()).toBeVisible({ timeout: 15_000 });
      await page.getByPlaceholder(/name this agent/i).fill(agentName);
      await page.getByRole("button", { name: "Save draft" }).first().click();

      // Real success notice (role="status") + the agent is now "live".
      await expect(page.getByText(`"${agentName}" created.`)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText("1/5 agents used")).toBeVisible();
      await expect(page.getByText(agentName).first()).toBeVisible();

      // Reload -> verify persistence. AgentWorkbench auto-selects the most
      // recently created agent when no selection is in state, so the created
      // agent's name should be visible again with no extra clicks.
      await page.reload();
      await expect(page.locator("header")).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(agentName).first()).toBeVisible({ timeout: 15_000 });

      // Edit: name/persona are read-only once an agent exists (by design -
      // see AgentWorkbench.tsx's own header comment on the "honesty note"
      // about which fields have a real PATCH endpoint). The system prompt IS
      // live-editable via its pencil icon - this is the real "edit" path.
      await page.getByRole("button", { name: "Edit prompt" }).click();
      const newPrompt = `You are an E2E-updated assistant, stamp ${Date.now()}.`;
      const promptTextarea = page.locator("textarea").first();
      await promptTextarea.fill(newPrompt);
      await page.getByRole("button", { name: "Save", exact: true }).click();
      await expect(page.getByText("Prompt updated.")).toBeVisible({ timeout: 15_000 });

      // Reload -> verify the new prompt persisted server-side.
      await page.reload();
      await expect(page.locator("header")).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(newPrompt).first()).toBeVisible({ timeout: 15_000 });

      // Delete: FREE tenants get an upgrade-locked Delete button (see file
      // header note) - clicking it must NOT delete the agent. Verify the
      // lock state and that the agent survives, rather than asserting a
      // real delete this tenant tier cannot perform.
      const deleteButton = page.getByRole("button", { name: "Delete", exact: true });
      await expect(deleteButton).toHaveAttribute("aria-disabled", "true");
      await deleteButton.click();
      await expect(page.getByRole("alertdialog")).toHaveCount(0);
      await expect(page.getByText(agentName).first()).toBeVisible();
      await expect(page.getByText("1/5 agents used")).toBeVisible();
    });

    // ---------------------------------------------------------------
    // 2. Workflow builder
    // ---------------------------------------------------------------
    await test.step("Workflow builder smoke", async () => {
      await page.goto("/workflows");

      const workflowName = `E2E Workflow ${Date.now()}`;
      await page.getByRole("button", { name: "New workflow", exact: true }).click();
      await page.getByPlaceholder(/New workflow name/i).fill(workflowName);
      await page.getByRole("button", { name: "Create workflow" }).click();

      // Navigates to the dedicated canvas route for this workflow.
      await page.waitForURL(/\/workflows\/[^/]+/, { timeout: 20_000 });

      // Canvas renders with the real starter graph (one "Trigger" node,
      // WorkflowDesignerPanel.tsx's starterGraph()).
      await expect(page.locator(".react-flow")).toBeVisible({ timeout: 20_000 });
      await expect(page.locator(".react-flow__node").first()).toBeVisible({ timeout: 15_000 });

      // Save (persists the starter graph as-is).
      await page.getByRole("button", { name: "Save Draft", exact: true }).click();

      // Verify it appears in the workflow list.
      await page.goto("/workflows");
      await expect(page.getByText(workflowName)).toBeVisible({ timeout: 15_000 });
    });

    // ---------------------------------------------------------------
    // 3. Knowledge base upload
    // ---------------------------------------------------------------
    await test.step("Knowledge base upload smoke", async () => {
      await page.goto("/knowledge");

      // FREE tier caps at 1 KB collection, and every tenant is seeded with
      // one non-deletable "Default" collection at signup (see
      // KnowledgeCollectionService's javadoc) - that seed already consumes
      // the cap, so this uploads into the existing collection rather than
      // creating a new one. Wait for the upload control to appear as proof
      // a collection is actually selected (it's hidden behind an EmptyState
      // until selectedCollection is truthy).
      const chooseFileButton = page.getByRole("button", { name: /choose a file to upload/i });
      await expect(chooseFileButton).toBeVisible({ timeout: 15_000 });

      const fileName = `e2e-smoke-${Date.now()}.md`;
      await page.locator('input[type="file"]').setInputFiles({
        name: fileName,
        mimeType: "text/markdown",
        buffer: Buffer.from(
          "# E2E smoke test\n\nTiny in-memory test file uploaded by console-core-smoke.spec.ts."
        ),
      });

      // Appears in the per-collection file table immediately (sync upload
      // path for .md - see KnowledgePanel.tsx's SYNC_EXTENSIONS).
      await expect(page.getByText(fileName)).toBeVisible({ timeout: 20_000 });

      // Poll for the real extraction -> embedding -> ready pipeline
      // (KnowledgeDocumentStatus) to finish. The panel already self-polls
      // every 4s while any document is non-terminal, so a generous
      // expect() timeout does the "wait for ready" polling for us.
      await expect(page.getByText(/ready/i).first()).toBeVisible({ timeout: 60_000 });
    });
  });
});
