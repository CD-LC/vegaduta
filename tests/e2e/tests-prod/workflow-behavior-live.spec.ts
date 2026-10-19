import { test, expect, type Page } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole } from "../helpers/prod-session";
import {
  createWorkflowViaUi,
  putGraph,
  apiCall,
  openWorkflow,
  runWorkflow,
  deleteWorkflow,
  expectRunOutput,
  node,
  edge,
  type GraphNode,
  type GraphEdge,
} from "../helpers/workflow-graph";

/**
 * WORKFLOW BEHAVIOR (real end-user scenarios, live tenant).
 *
 * Complements workflow-node-config-live.spec.ts (which edits ONE node's
 * drawer field and checks ONE output). These tests read what whole
 * workflows actually PRODUCE for different inputs and configs:
 *   - multi-node data pipelines (extract -> template -> uppercase) and how
 *     changing one node's params changes the final output,
 *   - Condition routing driven by a table of realistic customer messages,
 *   - Loop caps + odd inputs (empty list, non-list) without a crash,
 *   - bad configuration surfacing a clear message, never a stack trace,
 *   - run history: newest-first, distinct outputs, version bump on edit,
 *     rerun reflects the edited graph, canvas shows the run after reload,
 *   - draft vs ACTIVE (published) both runnable and re-editable,
 *   - an Agent (LLM) node feeding a Code node, with a tolerant assertion.
 *
 * Runs are started over the same REST endpoint the canvas Run button
 * calls (POST /api/workflows/{id}/run) so the assertions read the real
 * structured trace (per-node status + summary), which is far less flaky
 * than scraping the inspector. One test also drives the real UI.
 * Every test creates uniquely named workflows and deletes them.
 */

type Trace = { nodeId: string; nodeType: string; label: string; status: string; summary: string; logLines?: string[] };
type Run = { id: string; status: string; errorMessage?: string | null; trace: Trace[]; workflowVersion?: number };

function gate(testInfo: Parameters<typeof requireUserCredentials>[0]) {
  requireUserCredentials(testInfo);
  requireMutationOptIn(testInfo);
  testInfo.skip(testInfo.project.name !== "chromium", "writes real data - one project only");
}

const trig = () => node("t1", "trigger", { channel: null }, "Trigger");
const out = () => node("o1", "output", { channel: "log" }, "Output");

async function makeWf(page: Page, label: string, nodes: GraphNode[], edges: GraphEdge[]) {
  const name = `E2E Behavior ${label} ${Date.now()}`;
  const res = await apiCall(page, "POST", "/api/workflows", { name, description: "", graph: { nodes: [], edges: [] } });
  expect(res.status, `create workflow failed: ${res.text.slice(0, 200)}`).toBeLessThan(300);
  const id = JSON.parse(res.text).id as string;
  await putGraph(page, id, name, nodes, edges);
  return { id, name };
}

async function run(page: Page, id: string, input: string): Promise<Run> {
  const res = await apiCall(page, "POST", `/api/workflows/${id}/run`, { input });
  expect(res.status, `run request failed (${res.status}): ${res.text.slice(0, 300)}`).toBeLessThan(300);
  return JSON.parse(res.text) as Run;
}

const step = (r: Run, nodeId: string) => r.trace.find((t) => t.nodeId === nodeId);
/** Everything a user could read for a run, joined - for "no raw errors leak" checks. */
const allText = (r: Run) =>
  [r.errorMessage ?? "", ...r.trace.flatMap((t) => [t.summary ?? "", ...(t.logLines ?? [])])].join("\n");
const NO_LEAK = /Exception|\bat com\.|\bat java\.|stack ?trace|NullPointer|\[object Object\]/;

test.describe("Workflow behavior - outputs per config and input (live production scenario)", () => {
  test.setTimeout(5 * 60 * 1000);

  test("data pipeline: extract -> template -> uppercase; editing one node's params changes the final output, per input", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const nodes = [
      trig(),
      node("x1", "code", { operation: "extract", field: "customer.name" }, "GetName"),
      node("m1", "code", { operation: "template", template: "Dear {{input}}, your order is ready" }, "Compose"),
      node("u1", "code", { operation: "uppercase" }, "Shout"),
      out(),
    ];
    const edges = [edge("t1", "x1"), edge("x1", "m1"), edge("m1", "u1"), edge("u1", "o1")];
    const wf = await makeWf(page, "Pipeline", nodes, edges);
    try {
      const r1 = await run(page, wf.id, '{"customer":{"name":"Ada Lovelace"},"total":42}');
      expect(r1.status).toBe("COMPLETED");
      expect(r1.trace.map((t) => t.nodeId), "steps must run in graph order").toEqual(["t1", "x1", "m1", "u1", "o1"]);
      expect(step(r1, "x1")!.summary).toContain("Ada Lovelace");
      expect(step(r1, "u1")!.summary).toContain("DEAR ADA LOVELACE, YOUR ORDER IS READY");
      expect(allText(r1)).not.toMatch(NO_LEAK);

      // Different input, same config -> different output (not a canned answer).
      const r2 = await run(page, wf.id, '{"customer":{"name":"Grace Hopper"}}');
      expect(step(r2, "u1")!.summary).toContain("DEAR GRACE HOPPER, YOUR ORDER IS READY");
      expect(step(r2, "u1")!.summary).not.toContain("ADA");

      // Edit config: different field + template + lowercase instead of uppercase.
      nodes[1] = node("x1", "code", { operation: "extract", field: "total" }, "GetName");
      nodes[2] = node("m1", "code", { operation: "template", template: "TOTAL DUE: {{input}} USD" }, "Compose");
      nodes[3] = node("u1", "code", { operation: "lowercase" }, "Shout");
      await putGraph(page, wf.id, wf.name, nodes, edges);
      const r3 = await run(page, wf.id, '{"customer":{"name":"Ada"},"total":42}');
      expect(r3.status).toBe("COMPLETED");
      expect(step(r3, "u1")!.summary, "config edit did not change the output").toContain("total due: 42 usd");
      expect(step(r3, "u1")!.summary).not.toContain("DEAR");

      // Missing field: empty substitution, still COMPLETED, no raw error.
      const r4 = await run(page, wf.id, '{"customer":{"name":"Ada"}}');
      expect(r4.status).toBe("COMPLETED");
      expect(allText(r4)).not.toMatch(NO_LEAK);
    } finally {
      await deleteWorkflow(page, wf.id);
    }
  });

  test("condition routing: a table of realistic customer messages goes to the right branch", async ({ page }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const wf = await makeWf(
      page,
      "Routing",
      [
        trig(),
        node("k1", "condition", { contains: "refund" }, "IsRefund"),
        node("ct", "code", { operation: "template", template: "BILLING-QUEUE::{{input}}" }, "Billing"),
        node("cf", "code", { operation: "template", template: "GENERAL-QUEUE::{{input}}" }, "General"),
        node("ot", "output", { channel: "log" }, "OutBilling"),
        node("of", "output", { channel: "log" }, "OutGeneral"),
      ],
      [edge("t1", "k1"), edge("k1", "ct", "true"), edge("k1", "cf", "false"), edge("ct", "ot"), edge("cf", "of")]
    );
    try {
      const table: Array<[string, "ct" | "cf"]> = [
        ["I want a REFUND for order 991", "ct"],
        ["Please refund me", "ct"],
        ["Where is my parcel?", "cf"],
        ["Refunds policy question", "ct"],
        ["Hello, please help", "cf"],
      ];
      for (const [msg, expectedNode] of table) {
        const r = await run(page, wf.id, msg);
        expect(r.status, `"${msg}" run failed: ${allText(r)}`).toBe("COMPLETED");
        const ran = r.trace.filter((t) => t.status !== "SKIPPED").map((t) => t.nodeId);
        const other = expectedNode === "ct" ? "cf" : "ct";
        expect(ran, `"${msg}" should have gone through ${expectedNode}`).toContain(expectedNode);
        expect(ran, `"${msg}" must not run ${other}`).not.toContain(other);
        const prefix = expectedNode === "ct" ? "BILLING-QUEUE::" : "GENERAL-QUEUE::";
        expect(step(r, expectedNode)!.summary).toContain(prefix + msg);
      }
    } finally {
      await deleteWorkflow(page, wf.id);
    }
  });

  test("loop: cap sweep (1, 3, exact size, huge) and odd inputs (empty list, plain text, numbers) never crash", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const mk = (cap: number) => [
      trig(),
      node("l1", "loop", { itemsField: "", maxIterations: cap }, "Loop"),
      node("c1", "code", { operation: "template", template: "<{{input}}>" }, "Wrap"),
      out(),
    ];
    const edges = [edge("t1", "l1"), edge("l1", "c1"), edge("c1", "o1")];
    const wf = await makeWf(page, "Loop", mk(25), edges);
    try {
      const items = '["a","b","c","d"]';
      for (const [cap, done] of [[1, 1], [3, 3], [4, 4], [1000, 4]] as const) {
        await putGraph(page, wf.id, wf.name, mk(cap), edges);
        const r = await run(page, wf.id, items);
        expect(r.status).toBe("COMPLETED");
        const loop = step(r, "l1")!.summary;
        expect(loop, `cap ${cap}`).toMatch(new RegExp(`Repeated 'Wrap' ${done} of 4 item\\(s\\)`));
        if (done < 4) expect(loop).toContain(`capped at ${cap}`);
        else expect(loop).not.toContain("capped");
      }
      await putGraph(page, wf.id, wf.name, mk(25), edges);
      for (const odd of ["[]", "just some plain words", "12345", '{"a":1}']) {
        const r = await run(page, wf.id, odd);
        expect(["COMPLETED", "FAILED"], `odd input ${odd}`).toContain(r.status);
        expect(allText(r), `odd input ${odd} leaked an internal error`).not.toMatch(NO_LEAK);
        if (r.status === "FAILED") expect((r.errorMessage ?? "").length, "FAILED run must explain itself").toBeGreaterThan(5);
      }
    } finally {
      await deleteWorkflow(page, wf.id);
    }
  });

  test("bad configuration gives a clear, human message (never a stack trace): invalid JSON, invalid regex, unreachable host, missing agent", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const a = await makeWf(page, "BadJson", [trig(), node("x1", "code", { operation: "extract", field: "a.b" }, "Extract"), out()], [edge("t1", "x1"), edge("x1", "o1")]);
    const b = await makeWf(page, "BadRegex", [trig(), node("x1", "code", { operation: "regex_replace", pattern: "([unclosed", replacement: "x" }, "Regex"), out()], [edge("t1", "x1"), edge("x1", "o1")]);
    const c = await makeWf(page, "BadHost", [trig(), node("h1", "http", { method: "GET", url: "https://no-such-host-e2e.invalid/x" }, "Call"), out()], [edge("t1", "h1"), edge("h1", "o1")]);
    const d = await makeWf(page, "NoAgent", [trig(), node("g1", "agent", {}, "Ask"), out()], [edge("t1", "g1"), edge("g1", "o1")]);
    try {
      const ra = await run(page, a.id, "definitely not json");
      expect(["FAILED", "COMPLETED"]).toContain(ra.status);
      expect(allText(ra), "invalid JSON leaked internals").not.toMatch(NO_LEAK);
      if (ra.status === "FAILED") expect(allText(ra).length).toBeGreaterThan(5);

      const rb = await run(page, b.id, "some text");
      expect(allText(rb), "invalid regex leaked internals").not.toMatch(NO_LEAK);
      expect(["FAILED", "COMPLETED"]).toContain(rb.status);

      const rc = await run(page, c.id, "go");
      expect(allText(rc), "unreachable host leaked internals").not.toMatch(NO_LEAK);
      expect(step(rc, "h1")!.status, "HTTP to a dead host must not report OK").not.toBe("COMPLETED");

      // Missing agent: refused up-front (400 + readable errors) or failed run with a message.
      const rd = await apiCall(page, "POST", `/api/workflows/${d.id}/run`, { input: "hi" });
      expect(rd.status, `missing agent must not be a 5xx crash: ${rd.text.slice(0, 200)}`).toBeLessThan(500);
      expect(rd.text).not.toMatch(NO_LEAK);
      expect(rd.text.toLowerCase()).toMatch(/agent/);
    } finally {
      for (const w of [a, b, c, d]) await deleteWorkflow(page, w.id);
    }
  });

  test("run history: every run is recorded newest-first with its own output, versions bump on edit, and the canvas shows the run after reload", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const name = `E2E Behavior History ${Date.now()}`;
    const id = await createWorkflowViaUi(page, name);
    const nodes = [trig(), node("c1", "code", { operation: "template", template: "v1:{{input}}" }, "Tag"), out()];
    const edges = [edge("t1", "c1"), edge("c1", "o1")];
    try {
      await putGraph(page, id, name, nodes, edges);
      const first = await run(page, id, "alpha");
      const second = await run(page, id, "bravo");
      expect(step(first, "c1")!.summary).toContain("v1:alpha");
      expect(step(second, "c1")!.summary).toContain("v1:bravo");

      nodes[1] = node("c1", "code", { operation: "template", template: "v2:{{input}}" }, "Tag");
      await putGraph(page, id, name, nodes, edges);
      const third = await run(page, id, "charlie");
      expect(step(third, "c1")!.summary, "rerun after edit must use the edited graph").toContain("v2:charlie");
      if (first.workflowVersion != null && third.workflowVersion != null) {
        expect(third.workflowVersion, "editing the graph should bump the run's workflowVersion").toBeGreaterThan(first.workflowVersion);
      }

      const list = await apiCall(page, "GET", `/api/workflows/${id}/runs`);
      expect(list.status).toBe(200);
      const runs = JSON.parse(list.text) as Run[];
      expect(runs.length).toBeGreaterThanOrEqual(3);
      expect(runs[0].id, "history is not newest-first").toBe(third.id);
      const ids = runs.map((r) => r.id);
      expect(new Set(ids).size, "duplicate run ids in history").toBe(ids.length);
      expect(ids).toEqual(expect.arrayContaining([first.id, second.id, third.id]));

      // An old run is immutable: fetching it still shows the OLD output.
      const old = await apiCall(page, "GET", `/api/workflows/${id}/runs/${first.id}`);
      expect(old.status).toBe(200);
      expect(step(JSON.parse(old.text) as Run, "c1")!.summary).toContain("v1:alpha");

      // Real UI: run from the Run modal, output on screen; reload keeps the run visible.
      await openWorkflow(page, id);
      expect(await runWorkflow(page, "delta")).toBe("COMPLETED");
      await expectRunOutput(page, id, "v2:delta", "the UI run's own output never appeared in the run result");
      // The dedicated history page lists every run (short id + status) and each row opens its full trace.
      await page.goto(`/workflows/${id}/runs`, { waitUntil: "domcontentloaded" });
      await expect(page.getByText(`#${third.id.slice(0, 8)}`).first(), "history page is missing a run").toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(`#${first.id.slice(0, 8)}`).first(), "history page lost the oldest run").toBeVisible();
      expect(await page.getByText(/COMPLETED/).count(), "history page should show COMPLETED badges").toBeGreaterThanOrEqual(3);
      await page.getByText(`#${first.id.slice(0, 8)}`).first().click();
      await page.waitForURL(/\/runs\/[0-9a-f-]{36}/, { timeout: 30_000 });
      await expect(page.getByText(/v1:alpha/).first(), "run detail should show that run's own output").toBeVisible({ timeout: 30_000 });
    } finally {
      await deleteWorkflow(page, id);
    }
  });

  test("draft vs published: both run, status is persisted, and editing a published workflow is reflected in its next run", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    await signInToConsole(page);
    const nodes = [trig(), node("c1", "code", { operation: "template", template: "draft-out:{{input}}" }, "Tag"), out()];
    const edges = [edge("t1", "c1"), edge("c1", "o1")];
    const wf = await makeWf(page, "Publish", nodes, edges);
    const putStatus = async (status: "DRAFT" | "ACTIVE", n: GraphNode[]) => {
      const body = {
        name: wf.name,
        description: "",
        graph: {
          nodes: n.map((x, i) => ({ id: x.id, type: x.type, position: { x: i * 260, y: 0 }, data: { label: x.label, config: x.config } })),
          edges: edges.map((e) => ({ id: `e-${e.source}-x-${e.target}`, source: e.source, target: e.target, sourceHandle: null, targetHandle: null })),
        },
        status,
      };
      return apiCall(page, "PUT", `/api/workflows/${wf.id}`, body);
    };
    try {
      expect(JSON.parse((await apiCall(page, "GET", `/api/workflows/${wf.id}`)).text).status).toBe("DRAFT");
      const d = await run(page, wf.id, "one");
      expect(d.status, "a DRAFT must be runnable manually").toBe("COMPLETED");
      expect(step(d, "c1")!.summary).toContain("draft-out:one");

      const pub = await putStatus("ACTIVE", nodes);
      expect(pub.status, `publish failed: ${pub.text.slice(0, 200)}`).toBeLessThan(300);
      expect(JSON.parse((await apiCall(page, "GET", `/api/workflows/${wf.id}`)).text).status).toBe("ACTIVE");
      const p = await run(page, wf.id, "two");
      expect(p.status).toBe("COMPLETED");
      expect(step(p, "c1")!.summary).toContain("draft-out:two");

      const edited = [nodes[0], node("c1", "code", { operation: "template", template: "live-out:{{input}}" }, "Tag"), nodes[2]];
      const upd = await putStatus("ACTIVE", edited);
      // Free plans may lock published workflows; either accepted-and-reflected or a clear 4xx.
      if (upd.status < 300) {
        const p2 = await run(page, wf.id, "three");
        expect(step(p2, "c1")!.summary, "edit to a published workflow was not used by the next run").toContain("live-out:three");
      } else {
        expect(upd.status).toBeLessThan(500);
        expect(upd.text).not.toMatch(NO_LEAK);
      }

      const back = await putStatus("DRAFT", edited);
      expect(back.status, "unpublish (back to DRAFT) failed").toBeLessThan(500);
    } finally {
      await deleteWorkflow(page, wf.id);
    }
  });

  test("Agent (LLM) node: an agent's answer to an exact-token instruction flows into a Code node and out the Output", async ({
    page,
  }, testInfo) => {
    gate(testInfo);
    test.setTimeout(6 * 60 * 1000);
    await signInToConsole(page);
    const agentsRes = await apiCall(page, "GET", "/api/agents");
    expect(agentsRes.status).toBe(200);
    const agents = JSON.parse(agentsRes.text) as Array<{ id: string; name?: string }>;
    test.skip(agents.length === 0, "tenant has no agent to put in an Agent node");
    const agentId = agents[0].id;

    const nodes = [
      trig(),
      node("g1", "agent", { agentId }, "Ask"),
      node("u1", "code", { operation: "uppercase" }, "Shout"),
      out(),
    ];
    const wf = await makeWf(page, "AgentNode", nodes, [edge("t1", "g1"), edge("g1", "u1"), edge("u1", "o1")]);
    try {
      let last: Run | null = null;
      let ok = false;
      for (let attempt = 0; attempt < 3 && !ok; attempt++) {
        const token = `zebra${Date.now() % 100000}`;
        last = await run(page, wf.id, `Reply with exactly this single word and nothing else: ${token}`);
        const shouted = step(last, "u1")?.summary ?? "";
        ok = last.status === "COMPLETED" && shouted.toUpperCase().includes(token.toUpperCase());
        if (last.status === "COMPLETED") {
          expect(step(last, "g1")!.status).not.toBe("FAILED");
          expect(allText(last)).not.toMatch(NO_LEAK);
        }
      }
      expect(ok, `agent answer never carried the demanded token through the pipeline. last: ${last ? allText(last).slice(0, 400) : "none"}`).toBe(true);
      const agentStep = step(last!, "g1")!;
      expect(agentStep.logLines?.join(" ") ?? "", "agent step should report which model answered").toMatch(/model|Agent:/i);
    } finally {
      await deleteWorkflow(page, wf.id);
    }
  });
});
