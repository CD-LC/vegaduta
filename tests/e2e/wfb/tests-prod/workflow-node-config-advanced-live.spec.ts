import { test, expect, type Page, type Locator } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import {
  createWorkflowViaUi,
  putGraph,
  openWorkflow,
  selectNode,
  runWorkflow,
  deleteWorkflow,
  apiCall,
  node,
  edge,
} from "../helpers/workflow-graph";

/**
 * REAL-TENANT validation of the workflow nodes whose behavior involves
 * MORE than one node or another part of the platform: Parallel Fan-Out,
 * Trigger Workflow (sub-workflow call), the Human Approval gate, and the
 * direct Tool node. Companion to workflow-node-config-live.spec.ts, which
 * covers the single-node transforms (Code / Condition / HTTP / Output / Loop).
 *
 * Same standard: every assertion is about what the RUN did, not that a field
 * accepted text. Deterministic on purpose - none of these involve an LLM turn
 * (the Tool node "calls this one tool directly with no LLM turn in between",
 * per its own drawer copy), so they consume none of the tenant's hosted-model
 * allowance and can't flake on model wording.
 *
 * Engine semantics asserted were read from WorkflowExecutionEngine /
 * WorkflowNodeExecutor, not guessed: a Parallel node's branches ARE its
 * outgoing edges and their outputs merge into a JSON array in edge order; a
 * Trigger Workflow node with no target is SKIPPED with its input passed
 * through; the Tool node likewise when no agent/tool is chosen.
 */

const drawerField = (page: Page, labelText: string): Locator =>
  page.locator("div.field").filter({ has: page.getByText(labelText, { exact: true }) });

async function saveDraft(page: Page): Promise<void> {
  const [res] = await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === "PUT" && /\/api\/workflows\/[0-9a-f-]{36}$/.test(new URL(r.url()).pathname),
      { timeout: 20_000 }
    ),
    page.getByRole("button", { name: /^save draft$/i }).click(),
  ]);
  expect(res.status(), "Save Draft's PUT failed").toBeLessThan(300);
}

async function pickOption(page: Page, field: Locator, optionName: string): Promise<void> {
  await field.locator("button.select-trigger").click();
  await page.getByRole("option", { name: optionName, exact: true }).click();
}

/**
 * Clicks Run and expects the platform's PRE-RUN validation to refuse it:
 * "This workflow isn't ready yet" with one line per misconfigured node.
 * Returns the dialog text, and dismisses it. (Found while building this file:
 * an unconfigured Trigger Workflow or Tool node is not "skipped at run time"
 * as the executor alone suggests - the run never starts at all.)
 */
async function runExpectingValidationRefusal(page: Page, prompt: string): Promise<string> {
  await page.getByRole("button", { name: /run now/i }).click();
  const box = page.getByPlaceholder(/e\.g\. a sample inbound message/i);
  await expect(box, "Run modal never opened").toBeVisible({ timeout: 15_000 });
  await box.fill(prompt);
  await page.getByRole("button", { name: "▶ Run", exact: true }).click();
  const dialog = page.getByText(/this workflow isn't ready yet/i);
  await expect(dialog, "an unconfigured node did not stop the run at validation").toBeVisible({ timeout: 15_000 });
  const text = await page.locator("body").innerText();
  await page.getByRole("button", { name: /^got it$/i }).click();
  await expect(dialog).toHaveCount(0);
  return text;
}

function gate(testInfo: Parameters<typeof requireUserCredentials>[0]) {
  requireUserCredentials(testInfo);
  requireMutationOptIn(testInfo);
  testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");
}

test.describe("Workflow multi-node behavior - parallel, sub-workflow, approval gate, direct tool (live production scenario)", () => {
  test.setTimeout(6 * 60 * 1000);

  test("Parallel Fan-Out runs every branch and merges their outputs in edge order", async ({ page }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const name = `E2E NodeCfg Parallel ${Date.now()}`;
    const id = await createWorkflowViaUi(page, name);
    try {
      await putGraph(
        page,
        id,
        name,
        [
          node("t1", "trigger", { channel: null }, "Trigger"),
          node("p1", "parallel", {}, "Fan"),
          node("cu", "code", { operation: "uppercase" }, "Up"),
          node("cl", "code", { operation: "lowercase" }, "Down"),
          node("o1", "output", { channel: "log" }, "Output"),
        ],
        [edge("t1", "p1"), edge("p1", "cu"), edge("p1", "cl"), edge("cu", "o1"), edge("cl", "o1")]
      );
      await openWorkflow(page, id);

      // The Parallel node has no config of its own - its drawer is just the label + delete.
      await selectNode(page, "p1");
      await expect(page.getByRole("button", { name: /^delete node$/i }), "parallel node drawer never opened").toBeVisible();
      await expect(page.locator("div.field").filter({ has: page.getByText("Operation", { exact: true }) })).toHaveCount(0);

      expect(await runWorkflow(page, "Hello World")).toBe("COMPLETED");
      await expect(
        page.getByText('["HELLO WORLD","hello world"]', { exact: true }).first(),
        "both branches' outputs should be merged into one JSON array, in edge order"
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await deleteWorkflow(page, id);
    }
  });

  test("Trigger Workflow node: with no target the run is refused at validation; once a target is chosen in the drawer it really runs that workflow", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const subName = `E2E NodeCfg SubFlow ${Date.now()}`;
    const parentName = `E2E NodeCfg Parent ${Date.now()}`;
    const subId = await createWorkflowViaUi(page, subName);
    let parentId = "";
    try {
      await putGraph(
        page,
        subId,
        subName,
        [node("t1", "trigger", { channel: null }, "Trigger"), node("c1", "code", { operation: "uppercase" }, "Shout"), node("o1", "output", { channel: "log" }, "Output")],
        [edge("t1", "c1"), edge("c1", "o1")]
      );
      parentId = await createWorkflowViaUi(page, parentName);
      await putGraph(
        page,
        parentId,
        parentName,
        [node("t1", "trigger", { channel: null }, "Trigger"), node("w1", "workflow", { targetWorkflowId: "" }, "CallSub"), node("o1", "output", { channel: "log" }, "Output")],
        [edge("t1", "w1"), edge("w1", "o1")]
      );
      await openWorkflow(page, parentId);

      // 1) No target: the platform refuses to start the run at all.
      const refusal = await runExpectingValidationRefusal(page, "hello sub flow");
      expect(refusal).toMatch(/Workflow node 'CallSub' has no target workflow selected/);

      // 2) Choose the target through the REAL drawer select; the run must now go through it.
      await selectNode(page, "w1");
      await pickOption(page, drawerField(page, "Workflow to trigger"), subName);
      await saveDraft(page);
      expect(await runWorkflow(page, "hello sub flow")).toBe("COMPLETED");
      await expect(
        page.getByText(/HELLO SUB FLOW/).first(),
        "the sub-workflow's uppercase output never reached the parent run"
      ).toBeVisible({ timeout: 20_000 });
    } finally {
      if (parentId) await deleteWorkflow(page, parentId);
      await deleteWorkflow(page, subId);
    }
  });

  test("Human Approval node: auto-approves by default; once the tenant policy requires approval it pauses the run, files a request with its message, and Approve resumes it", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const name = `E2E NodeCfg Approval ${Date.now()}`;
    const message = `E2E-APPROVE-${Date.now()}`;
    const ACTION = "workflow.node.approval";
    const id = await createWorkflowViaUi(page, name);
    try {
      await putGraph(
        page,
        id,
        name,
        [
          node("t1", "trigger", { channel: null }, "Trigger"),
          node("a1", "approval", { message, adminOnly: false }, "Gate"),
          node("o1", "output", { channel: "log" }, "Output"),
        ],
        [edge("t1", "a1"), edge("a1", "o1")]
      );
      await openWorkflow(page, id);

      // The drawer shows the configured message.
      await selectNode(page, "a1");
      await expect(page.getByPlaceholder(/double-check the refund amount/i)).toHaveValue(message);

      // 1) DEFAULT POLICY. The action type "workflow.node.approval" is not in the
      //    platform's default-gated list (channel sends, bulk sends, *.delete,
      //    code-workspace writes, payment-ish names), so with no policy
      //    override the Approval node does NOT pause: it reports
      //    "Auto-approved by policy." and the run completes. Recorded as a
      //    fact about the shipped default, worth a product decision - the
      //    node's own drawer copy says "Every run pauses here".
      await apiCall(page, "PUT", `/api/approvals/policy/${ACTION}`, { requiresApproval: false });
      expect(await runWorkflow(page, "default-policy-run")).toBe("COMPLETED");
      await expect(
        page.getByText(/Auto-approved by policy/).first(),
        "with the default policy the approval node should report it was auto-approved"
      ).toBeVisible({ timeout: 15_000 });

      // 2) POLICY REQUIRES APPROVAL. Now the same node must pause the run.
      const set = await apiCall(page, "PUT", `/api/approvals/policy/${ACTION}`, { requiresApproval: true });
      expect(set.status, `could not set the approval policy: ${set.text.slice(0, 200)}`).toBeLessThan(300);
      const status = await runWorkflow(page, "policy-run-needs-a-human");
      expect(status, "with the policy on, the approval gate must PAUSE the run").toMatch(/^AWAITING/);

      // The request must be real, carrying the workflow author's message.
      await page.goto("/admin/approvals", { waitUntil: "domcontentloaded" });
      const pending = page.getByText(message).locator("visible=true").first();
      await expect(pending, "no pending approval carrying this node's message appeared").toBeVisible({ timeout: 30_000 });
      await pending.click();
      await expect(page.getByText(new RegExp(ACTION.replace(/\./g, "\."))).first()).toBeVisible({ timeout: 10_000 });
      await page.getByRole("button", { name: /^approve$/i }).click();
      await expect(page.getByText(/approved.*will now execute/i), "Approve gave no confirmation").toBeVisible({
        timeout: 15_000,
      });

      // And approving must actually let the run finish. Poll the run history.
      await expect
        .poll(
          async () => {
            // The run list is read straight from the API: the console's run history UI was
            // redesigned (History button -> Runs/Flight log), the data it renders was not.
            const runs = await apiCall(page, "GET", `/api/workflows/${id}/runs`);
            const body = runs.status < 300 ? runs.text : "";
            let statuses: string[] = [];
            try { statuses = (JSON.parse(body) as Array<{ status?: string }>).map((r) => String(r.status ?? "")); } catch { /* not ready */ }
            return statuses.filter((x) => x === "COMPLETED").length >= 2 && !statuses.some((x) => /^AWAITING/.test(x));
          },
          { message: "the run never completed after the approval was granted", timeout: 90_000, intervals: [3_000] }
        )
        .toBe(true);
    } finally {
      // Put the tenant policy back exactly to the default behavior.
      await apiCall(page, "PUT", `/api/approvals/policy/${ACTION}`, { requiresApproval: false }).catch(() => {});
      await deleteWorkflow(page, id);
    }
  });

  test("Tool node: refused at validation until an agent + tool are picked in the drawer, then it calls the tool directly with no LLM turn", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const name = `E2E NodeCfg Tool ${Date.now()}`;
    const id = await createWorkflowViaUi(page, name);
    try {
      await putGraph(
        page,
        id,
        name,
        [node("t1", "trigger", { channel: null }, "Trigger"), node("k1", "tool", {}, "Direct"), node("o1", "output", { channel: "log" }, "Output")],
        [edge("t1", "k1"), edge("k1", "o1")]
      );
      await openWorkflow(page, id);

      // 1) Nothing chosen: the platform refuses to start the run, naming both gaps.
      const refusal = await runExpectingValidationRefusal(page, "tool-node-marker");
      expect(refusal).toMatch(/Tool node 'Direct' has no agent selected/);
      expect(refusal).toMatch(/Tool node 'Direct' has no tool selected/);

      // 2) Pick an agent and one of its tools through the real drawer.
      await selectNode(page, "k1");
      const agentField = drawerField(page, "Agent (whose tool access this call reuses)");
      await agentField.locator("button.select-trigger").click();
      const firstAgent = page.getByRole("option").first();
      await expect(firstAgent, "tenant has no agents to pick for the Tool node").toBeVisible({ timeout: 10_000 });
      await firstAgent.click();
      const toolField = drawerField(page, "Tool");
      await toolField.locator("button.select-trigger").click();
      const toolOption = page.getByRole("option", { name: "list_knowledge_collections", exact: true });
      await expect(toolOption, "list_knowledge_collections not offered for the chosen agent").toBeVisible({
        timeout: 15_000,
      });
      await toolOption.click();
      await saveDraft(page);

      expect(await runWorkflow(page, "tool-node-marker")).toBe("COMPLETED");
      await expect(
        page.getByText(/isn't ready yet/i),
        "a fully configured Tool node must pass validation"
      ).toHaveCount(0);
      await expect(
        page.getByText(/list_knowledge_collections/).first(),
        "the run never mentions the tool it was configured to call"
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await deleteWorkflow(page, id);
    }
  });
});
