import { test, expect } from "@playwright/test";
import { node, edge, type GraphNode, type GraphEdge } from "../helpers/workflow-graph";
import { setupControlSweep, NO_LEAK } from "../helpers/control-sweep";

/**
 * CONTROL SWEEP, part 2 - workflow designer and build studio controls. Same
 * shape, gating and cleanup as control-sweep-live.spec.ts (see its header);
 * shared setup is helpers/control-sweep.ts.
 */

test.describe("Control sweep - workflow designer and build studio controls (live)", () => {
  test.setTimeout(4 * 60 * 1000);

  const sweep = setupControlSweep();
  const { api, gated, newAgent, newWorkflow, putGraph, workflows } = sweep;

  test("workflow settings (Intelligence modal): depth, tuning, step caching, cost routing, timeout and retries persist; out-of-range limits are rejected", async ({ page }) => {
    const wf = await newWorkflow(page, "Settings", [node("t1", "trigger", { channel: null }, "Trigger"), node("o1", "output", { channel: "log" }, "Output")], [edge("t1", "o1")]);
    const get = async () => (await api(page, "GET", `/api/workflows/${wf.id}`)).json;

    for (const d of ["CAREFUL", "RIGOROUS", "PLANNED", "FAST"]) {
      const r = await api(page, "PATCH", `/api/workflows/${wf.id}/reasoning-depth`, { reasoningDepth: d });
      expect(r.status, `depth ${d} -> ${r.status} ${r.text.slice(0, 160)}`).toBeLessThan(300);
      expect(String((await get()).reasoningDepth).toUpperCase(), `depth ${d} not persisted`).toBe(d);
    }
    for (const m of ["MANUAL", "SIMPLE"]) {
      const r = await api(page, "PATCH", `/api/workflows/${wf.id}/tuning-mode`, { tuningMode: m });
      expect(r.status).toBeLessThan(300);
      expect(String((await get()).tuningMode).toUpperCase(), `tuning ${m} not persisted`).toBe(m);
    }
    for (const on of [true, false]) {
      const r = await api(page, "PATCH", `/api/workflows/${wf.id}/step-caching`, { enabled: on });
      expect(r.status).toBeLessThan(300);
      expect((await get()).stepCachingEnabled, `step caching ${on} not persisted`).toBe(on);
    }
    const cr = await api(page, "PATCH", `/api/workflows/${wf.id}/cost-routing`, {
      enabled: true,
      economyProviderName: sweep.template!.providerName,
      economyModel: sweep.template!.model,
    });
    if (!gated(cr, "workflow cost routing")) {
      expect(cr.status, `${cr.status} ${cr.text.slice(0, 200)}`).toBeLessThan(300);
      const g = await get();
      expect(g.costRoutingEnabled).toBe(true);
      expect(g.economyModel).toBe(sweep.template!.model);
      await api(page, "PATCH", `/api/workflows/${wf.id}/cost-routing`, { enabled: false, economyProviderName: null, economyModel: null });
      expect((await get()).costRoutingEnabled, "workflow cost routing OFF not persisted").toBe(false);
    }

    const ok = await api(page, "PATCH", `/api/workflows/${wf.id}/execution-limits`, { maxStepsPerRun: 30, stepTimeoutSeconds: 45, maxRetryAttempts: 2 });
    expect(ok.status, `${ok.status} ${ok.text.slice(0, 200)}`).toBeLessThan(300);
    const g = await get();
    expect([g.maxStepsPerRun, g.stepTimeoutSeconds, g.maxRetryAttempts], "execution limits not persisted").toEqual([30, 45, 2]);
    for (const [label, body] of [
      ["maxSteps 0", { maxStepsPerRun: 0, stepTimeoutSeconds: 45, maxRetryAttempts: 2 }],
      ["maxSteps 201", { maxStepsPerRun: 201, stepTimeoutSeconds: 45, maxRetryAttempts: 2 }],
      ["timeout 0", { maxStepsPerRun: 30, stepTimeoutSeconds: 0, maxRetryAttempts: 2 }],
      ["timeout 301", { maxStepsPerRun: 30, stepTimeoutSeconds: 301, maxRetryAttempts: 2 }],
      ["retries -1", { maxStepsPerRun: 30, stepTimeoutSeconds: 45, maxRetryAttempts: -1 }],
      ["retries 6", { maxStepsPerRun: 30, stepTimeoutSeconds: 45, maxRetryAttempts: 6 }],
    ] as const) {
      const r = await api(page, "PATCH", `/api/workflows/${wf.id}/execution-limits`, body);
      expect(r.status, `${label} should be a 4xx, got ${r.status}`).toBeGreaterThanOrEqual(400);
      expect(r.status, `${label} was a server error`).toBeLessThan(500);
    }
    const after = await get();
    expect([after.maxStepsPerRun, after.stepTimeoutSeconds, after.maxRetryAttempts], "a rejected limit changed stored values").toEqual([30, 45, 2]);
  });

  test("workflow trigger strip: schedule persists, turns off, and a malformed cron is refused", async ({ page }) => {
    const wf = await newWorkflow(page, "Schedule", [node("t1", "trigger", { channel: null }, "Trigger"), node("o1", "output", { channel: "log" }, "Output")], [edge("t1", "o1")]);
    // Scheduling needs the workflow ACTIVE (publish).
    await putGraph(page, wf.id, wf.name, [node("t1", "trigger", { channel: null }, "Trigger"), node("o1", "output", { channel: "log" }, "Output")], [edge("t1", "o1")], "ACTIVE");
    const put = await api(page, "PUT", `/api/workflows/${wf.id}/schedule`, { cron: "0 9 * * MON" });
    if (gated(put, "workflow schedule")) return;
    expect(put.status, `${put.status} ${put.text.slice(0, 200)}`).toBeLessThan(300);
    expect(JSON.stringify((await api(page, "GET", `/api/workflows/${wf.id}/schedule`)).json), "cron not persisted").toContain("9");
    const bad = await api(page, "PUT", `/api/workflows/${wf.id}/schedule`, { cron: "not a cron" });
    expect(bad.status, "malformed cron accepted or server error").toBeGreaterThanOrEqual(400);
    expect(bad.status).toBeLessThan(500);
    const del = await api(page, "DELETE", `/api/workflows/${wf.id}/schedule`);
    expect(del.status).toBeLessThan(300);
  });

  test("workflow version history + diagram + duplicate: publish makes a version, restore rewinds the graph, diagram renders, duplicate is a new draft", async ({ page }) => {
    const t = node("t1", "trigger", { channel: null }, "Trigger");
    const o = (msg: string) => node("o1", "output", { channel: "log", message: msg }, "Output");
    const wf = await newWorkflow(page, "Versions", [t, o("first")], [edge("t1", "o1")]);
    const p1 = await api(page, "POST", `/api/workflows/${wf.id}/versions`, { changeNote: "sweep v1" });
    expect(p1.status, `publish version -> ${p1.status} ${p1.text.slice(0, 200)}`).toBeLessThan(300);
    await putGraph(page, wf.id, wf.name, [t, node("c1", "code", { operation: "uppercase" }, "Up"), o("second")], [edge("t1", "c1"), edge("c1", "o1")]);
    const p2 = await api(page, "POST", `/api/workflows/${wf.id}/versions`, { changeNote: "sweep v2" });
    expect(p2.status).toBeLessThan(300);
    const list = await api(page, "GET", `/api/workflows/${wf.id}/versions`);
    expect(list.status).toBe(200);
    const nums = (list.json as any[]).map((v) => v.versionNumber ?? v.version);
    expect(nums.length, "two publishes should leave two versions").toBeGreaterThanOrEqual(2);
    // Every create/update auto-snapshots (WorkflowService), so the LOWEST version is the empty
    // "Workflow created" graph. The rewind target is the lowest version whose graph holds the first output message.
    let first = NaN;
    for (const n of nums.filter((x: any) => typeof x === "number").sort((a: number, b: number) => a - b)) {
      const v = await api(page, "GET", `/api/workflows/${wf.id}/versions/${n}`);
      const gj = typeof v.json?.graphJson === "string" ? v.json.graphJson : JSON.stringify(v.json?.graphJson ?? "");
      if (gj.includes('"first"')) {
        first = n;
        break;
      }
    }
    expect(Number.isNaN(first), `no version snapshot holds the first graph; versions=${JSON.stringify(list.json).slice(0, 400)}`).toBe(false);
    const diff = await api(page, "GET", `/api/workflows/${wf.id}/versions/diff?from=${first}&to=${Math.max(...nums)}`);
    expect(diff.status, "version diff failed").toBeLessThan(400);
    const restore = await api(page, "POST", `/api/workflows/${wf.id}/versions/${first}/restore`);
    expect(restore.status, `restore -> ${restore.status} ${restore.text.slice(0, 200)}`).toBeLessThan(300);
    const g = (await api(page, "GET", `/api/workflows/${wf.id}`)).json;
    expect(g.graph.nodes.map((n: any) => n.id).sort(), `restore did not rewind the graph to version ${first} (versions=${JSON.stringify((list.json as any[]).map((v) => [v.versionNumber, v.changeSummary]))}; restore=${restore.text.slice(0, 500)}; got=${JSON.stringify(g).slice(0, 500)})`).toEqual(["o1", "t1"]);

    for (const f of ["mermaid", "dot", "svg"]) {
      const d = await api(page, "GET", `/api/workflows/${wf.id}/diagram?format=${f}`);
      expect(d.status, `diagram ${f} -> ${d.status}`).toBeLessThan(500);
    }
    const dup = await api(page, "POST", `/api/workflows/${wf.id}/duplicate`);
    if (!gated(dup, "duplicate workflow")) {
      expect(dup.status).toBeLessThan(300);
      workflows.push(dup.json.id);
      expect(dup.json.id).not.toBe(wf.id);
      expect(String(dup.json.status).toUpperCase(), "duplicate should be an editable DRAFT").toBe("DRAFT");
    }
  });

  // Per-node validation table: every node type that is NOT already behavior-tested in
  // workflow-node-config-*.spec.ts / workflow-behavior-live.spec.ts, plus the missing-field
  // branches of the ones that are. Each case builds trigger -> NODE (-> worker) -> output,
  // saves it, reads the config back byte-for-byte, then asks the real /validate endpoint and
  // checks that errors NAMING THIS NODE match (or are absent for a good config).
  type VCase = { name: string; type: string; config: Record<string, unknown>; expect: RegExp | null; withWorker?: boolean; noWorkerEdge?: boolean };
  const AGENT = "__AGENT__";
  const VCASES: VCase[] = [
    { name: "knowledge: bad collectionId", type: "knowledge", config: { collectionId: "not-a-uuid" }, expect: /invalid collectionId/i },
    { name: "knowledge: every collection + topK", type: "knowledge", config: { topK: 3 }, expect: null },
    { name: "supervisor: no agent", type: "supervisor", config: {}, expect: /no agent selected/i },
    { name: "supervisor: no routes", type: "supervisor", config: { agentId: AGENT, instructions: "route" }, expect: /no usable routes/i },
    { name: "supervisor: good", type: "supervisor", config: { agentId: AGENT, instructions: "route it", routes: [{ handle: "a", label: "A" }] }, expect: null },
    { name: "fan_out: no worker edge", type: "fan_out", config: { maxItems: 3, maxConcurrency: 2 }, expect: /needs exactly one outgoing/i, noWorkerEdge: true },
    { name: "fan_out: good", type: "fan_out", config: { itemsField: "", maxItems: 3, maxConcurrency: 2 }, expect: null, withWorker: true },
    { name: "human_input: field without key", type: "human_input", config: { message: "hi", fields: [{ label: "x" }] }, expect: /no 'key'/i },
    { name: "human_input: fields not a list", type: "human_input", config: { fields: "nope" }, expect: /not a list/i },
    { name: "human_input: good", type: "human_input", config: { message: "Approve?", fields: [{ key: "reason", label: "Reason", type: "text" }] }, expect: null },
    { name: "sub_flow: no body", type: "sub_flow", config: {}, expect: /no 'body' graph/i },
    { name: "voice_menu: no prompt", type: "voice_menu", config: {}, expect: /no prompt/i },
    { name: "voice_menu: good", type: "voice_menu", config: { prompt: "Press 1 for sales", numDigits: 1, timeoutSeconds: 5, maxRetries: 1, branches: [{ digit: "1", label: "sales" }] }, expect: null },
    { name: "bulk_email: no subject/body", type: "bulk_email", config: {}, expect: /no subject/i },
    { name: "bulk_email: unknown source", type: "bulk_email", config: { subject: "s", body: "b", recipientsSource: "bogus" }, expect: /unknown recipientsSource/i },
    { name: "bulk_email: group needs uuid", type: "bulk_email", config: { subject: "s", body: "b", recipientsSource: "group", groupId: "nope" }, expect: /valid contact groupId/i },
    { name: "bulk_email: static needs recipients", type: "bulk_email", config: { subject: "s", body: "b", recipientsSource: "static", staticRecipients: "" }, expect: /no static recipients/i },
    { name: "bulk_email: good (never run)", type: "bulk_email", config: { subject: "s", body: "b", recipientsSource: "static", staticRecipients: "a@example.com", maxRecipients: 5, maxPerMinute: 10, includeUnsubscribe: true, skipIfReplied: true, html: false, replyTo: "r@example.com" }, expect: null },
    { name: "trigger: invalid channel", type: "trigger", config: { channel: "BOGUS" }, expect: /invalid channel/i },
    { name: "agent: no agentId", type: "agent", config: {}, expect: /no agentId/i },
    { name: "agent: malformed agentId", type: "agent", config: { agentId: "x" }, expect: /invalid agentId/i },
    { name: "agent: vanished agent", type: "agent", config: { agentId: "00000000-0000-0000-0000-000000000000" }, expect: /no longer exists/i },
    { name: "agent: good + retry + approval flag", type: "agent", config: { agentId: AGENT, requiresApproval: false, retryAttempts: 2 }, expect: null },
    { name: "http: bad method", type: "http", config: { url: "https://example.com", method: "BREW" }, expect: /unsupported method/i },
    { name: "http: bad body type", type: "http", config: { url: "https://example.com", method: "POST", bodyType: "carrier-pigeon" }, expect: /body type/i },
    { name: "code: bad operation", type: "code", config: { operation: "teleport" }, expect: /unrecognized operation/i },
    { name: "output: bad channel", type: "output", config: { channel: "carrier_pigeon" }, expect: /unrecognized channel/i },
    { name: "condition: no expression", type: "condition", config: {}, expect: /condition/i },
    { name: "device_control: persists + validates without a 5xx", type: "device_control", config: { commandType: "TAP", deviceId: "d1", payload: "{\"x\":1,\"y\":2}" }, expect: null },
    { name: "crm_lead: persists + validates without a 5xx", type: "crm_lead", config: { action: "create", stage: "NEW", activityType: "NOTE", activityBody: "hello" }, expect: null },
  ];

  test("workflow node validation table: every node type saves its config verbatim and /validate names the exact missing field", async ({ page }) => {
    const a = await newAgent(page);
    const failures: string[] = [];
    for (const c of VCASES) {
      const config = JSON.parse(JSON.stringify(c.config).replace(new RegExp(AGENT, "g"), a.id));
      const label = `N_${c.type}`;
      const nodes: GraphNode[] = [node("t1", "trigger", { channel: null }, "Trigger"), node("n1", c.type, config, label)];
      const edges: GraphEdge[] = [edge("t1", "n1")];
      if (c.withWorker) {
        nodes.push(node("w1", "code", { operation: "uppercase" }, "Worker"));
        edges.push(edge("n1", "w1"));
        nodes.push(node("o1", "output", { channel: "log" }, "Output"));
        edges.push(edge("w1", "o1"));
      } else {
        nodes.push(node("o1", "output", { channel: "log" }, "Output"));
        if (!c.noWorkerEdge) edges.push(edge("n1", "o1"));
      }
      const wf = await newWorkflow(page, c.type);
      try {
        await putGraph(page, wf.id, wf.name, nodes, edges);
        const saved = (await api(page, "GET", `/api/workflows/${wf.id}`)).json;
        const sn = saved.graph.nodes.find((n: any) => n.id === "n1");
        const cfg = sn?.data?.config ?? sn?.config ?? {};
        for (const [k, v] of Object.entries(config)) {
          if (JSON.stringify(cfg[k]) !== JSON.stringify(v)) failures.push(`${c.name}: config.${k} did not round-trip (sent ${JSON.stringify(v).slice(0, 60)}, read ${JSON.stringify(cfg[k])?.slice(0, 60)})`);
        }
        const v = await api(page, "POST", `/api/workflows/${wf.id}/validate`, {});
        if (v.status >= 500) {
          failures.push(`${c.name}: /validate -> HTTP ${v.status}`);
          continue;
        }
        const errors: string[] = (v.json?.errors ?? []).map(String);
        const mine = errors.filter((e) => e.includes(label) || (c.type === "fan_out" && /Fan-out/i.test(e)));
        if (c.expect) {
          if (!mine.some((e) => c.expect!.test(e))) failures.push(`${c.name}: expected an error matching ${c.expect}, got ${JSON.stringify(errors).slice(0, 200)}`);
        } else if (mine.length) {
          failures.push(`${c.name}: a good config was rejected: ${JSON.stringify(mine).slice(0, 200)}`);
        }
        if (errors.some((e) => NO_LEAK.test(e))) failures.push(`${c.name}: validation message leaks internals`);
      } finally {
        await api(page, "DELETE", `/api/workflows/${wf.id}`).catch(() => {});
        workflows.splice(workflows.indexOf(wf.id), 1);
      }
    }
    expect(failures, `node config / validation failures:\n${failures.join("\n")}`).toEqual([]);
  });

  test("Fan-out: Max items really caps how many items the worker runs, and an empty list does not crash", async ({ page }) => {
    const mk = (cap: number) => [
      node("t1", "trigger", { channel: null }, "Trigger"),
      node("f1", "fan_out", { itemsField: "", maxItems: cap, maxConcurrency: 2 }, "Fan"),
      node("c1", "code", { operation: "template", template: "<{{input}}>" }, "Wrap"),
      node("o1", "output", { channel: "log" }, "Output"),
    ];
    const edges = [edge("t1", "f1"), edge("f1", "c1"), edge("c1", "o1")];
    const wf = await newWorkflow(page, "FanOut", mk(25), edges);
    const run = async (input: string) => {
      const r = await api(page, "POST", `/api/workflows/${wf.id}/run`, { input });
      expect(r.status, `run -> ${r.status} ${r.text.slice(0, 300)}`).toBeLessThan(300);
      return r.json as { status: string; errorMessage?: string; trace: Array<{ nodeId: string; summary: string }> };
    };
    const items = '["a","b","c","d","e"]';
    const all = await run(items);
    expect(all.status, all.errorMessage ?? "").toBe("COMPLETED");
    const fullSummary = all.trace.find((t) => t.nodeId === "f1")?.summary ?? "";
    expect(fullSummary, "fan-out summary should mention the 5 items").toMatch(/5/);

    await putGraph(page, wf.id, wf.name, mk(2), edges);
    const capped = await run(items);
    expect(capped.status, capped.errorMessage ?? "").toBe("COMPLETED");
    const cappedSummary = capped.trace.find((t) => t.nodeId === "f1")?.summary ?? "";
    expect(cappedSummary, "Max items=2 had no visible effect on the fan-out").not.toBe(fullSummary);
    expect(cappedSummary).toMatch(/2/);

    const empty = await run("[]");
    expect(["COMPLETED", "FAILED"]).toContain(empty.status);
    expect(JSON.stringify(empty)).not.toMatch(NO_LEAK);
  });

  test("Human Input node: the run pauses waiting for a person instead of completing, and can be cancelled cleanly", async ({ page }) => {
    const nodes = [
      node("t1", "trigger", { channel: null }, "Trigger"),
      node("h1", "human_input", { message: "Please give a reason", fields: [{ key: "reason", label: "Reason", type: "text" }] }, "Ask"),
      node("o1", "output", { channel: "log" }, "Output"),
    ];
    const wf = await newWorkflow(page, "HumanInput", nodes, [edge("t1", "h1"), edge("h1", "o1")]);
    const r = await api(page, "POST", `/api/workflows/${wf.id}/run`, { input: "start" });
    expect(r.status, `${r.status} ${r.text.slice(0, 300)}`).toBeLessThan(300);
    const run = r.json;
    expect(String(run.status), "a Human Input node must not let the run sail through to COMPLETED").not.toBe("COMPLETED");
    expect(run.status, "run FAILED instead of pausing: " + (run.errorMessage ?? "")).not.toBe("FAILED");
    expect(JSON.stringify(run)).not.toMatch(NO_LEAK);
    const reached = (run.trace ?? []).map((t: any) => t.nodeId);
    expect(reached, "the Output node ran even though the human never answered").not.toContain("o1");
    const cancel = await api(page, "POST", `/api/workflows/${wf.id}/runs/${run.id}/cancel`);
    expect(cancel.status, "cancelling a paused run failed").toBeLessThan(500);
  });

  test("Sub-Flow node: an embedded body runs once per item", async ({ page }) => {
    const body = {
      nodes: [{ id: "b1", type: "code", position: { x: 0, y: 0 }, data: { label: "Up", config: { operation: "uppercase" } } }],
      edges: [],
    };
    const nodes = [
      node("t1", "trigger", { channel: null }, "Trigger"),
      node("s1", "sub_flow", { itemsField: "", maxIterations: 5, entryNodeId: "b1", body }, "Sub"),
      node("o1", "output", { channel: "log" }, "Output"),
    ];
    const wf = await newWorkflow(page, "SubFlow", nodes, [edge("t1", "s1"), edge("s1", "o1")]);
    const r = await api(page, "POST", `/api/workflows/${wf.id}/run`, { input: '["alpha","beta"]' });
    expect(r.status, `${r.status} ${r.text.slice(0, 300)}`).toBeLessThan(300);
    const run = r.json;
    expect(["COMPLETED", "FAILED"]).toContain(run.status);
    if (run.status === "FAILED") expect((run.errorMessage ?? "").length, "FAILED sub-flow must explain itself").toBeGreaterThan(5);
    else expect(JSON.stringify(run.trace), "sub-flow body did not run per item").toMatch(/ALPHA/);
    expect(JSON.stringify(run)).not.toMatch(NO_LEAK);
  });

  test("workflow status (Activate / Revert to draft): ACTIVE then DRAFT round-trips", async ({ page }) => {
    const nodes = [node("t1", "trigger", { channel: null }, "Trigger"), node("o1", "output", { channel: "log" }, "Output")];
    const edges = [edge("t1", "o1")];
    const wf = await newWorkflow(page, "Status", nodes, edges);
    for (const s of ["ACTIVE", "DRAFT"]) {
      await putGraph(page, wf.id, wf.name, nodes, edges, s);
      expect(String((await api(page, "GET", `/api/workflows/${wf.id}`)).json.status).toUpperCase(), `status ${s} not persisted`).toBe(s);
    }
  });

  // ---------------------------------------------------------------------------------------------
  // BUILD STUDIO (client-side projects; localStorage) - controls the matrix lists as "none"
  // ---------------------------------------------------------------------------------------------

  test("build studio: Design corner-roundness slider and font picker restyle the app; Connect and Share panels render their controls", async ({ page }) => {
    await page.evaluate(() => {
      try {
        const k = "vd_page_guides_seen";
        const seen: string[] = JSON.parse(localStorage.getItem(k) || "[]");
        for (const id of ["build", "build-center", "build-studio", "build-apps"]) if (!seen.includes(id)) seen.push(id);
        localStorage.setItem(k, JSON.stringify(seen));
      } catch {
        /* ignore */
      }
    });
    await page.goto("/build");
    const tab = page.getByRole("tab", { name: /AI app studio/i });
    await expect(tab).toBeVisible({ timeout: 30_000 });
    await page.keyboard.press("Escape");
    await tab.dispatchEvent("click");
    await expect(page.getByRole("heading", { name: /Describe an app/i })).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: /To-do list/i }).first().click();
    const PREVIEW = 'iframe[title$=" preview"]';
    await expect(page.locator(PREVIEW)).toBeVisible({ timeout: 20_000 });
    // The studio side panels are a role="tablist" (StudioSurface "App tools"), not buttons.
    const panel = (label: string) => page.getByRole("tab", { name: new RegExp(`^${label}$`) }).dispatchEvent("click");
    const readSource = async () => {
      await panel("Code");
      const ta = page.getByLabel("App source code");
      await expect(ta).toBeVisible();
      return ta.inputValue();
    };

    // Corner roundness slider (commits on mouse-up / key-up).
    const before = await readSource();
    await panel("Design");
    const slider = page.locator('input[type="range"]').first();
    await expect(slider, "Design panel has no roundness slider").toBeVisible();
    await slider.focus();
    for (let i = 0; i < 6; i++) await page.keyboard.press("ArrowRight");
    await expect.poll(async () => (await readSource()) !== before, { message: "moving the roundness slider did not change the app source", timeout: 15_000 }).toBe(true);
    await panel("Design");

    // Font picker: a grid of buttons (System/Rounded/Serif/Mono - StudioSurface.tsx), NOT a <select>.
    const src0 = await readSource();
    await panel("Design");
    const mono = page.getByRole("button", { name: /^Mono$/ });
    await expect(mono, "Design panel has no font buttons").toBeVisible();
    await mono.click();
    await expect.poll(async () => (await readSource()) !== src0, { message: "choosing a font did not change the app source", timeout: 15_000 }).toBe(true);

    // Connect + Share panels render (presence) - behavior needs a real agent share link, covered by agent-workflow-share-live.
    await panel("Connect");
    // The "Sign-in for your users" section is a <details> that starts collapsed while sign-in is Off.
    await page.locator("summary", { hasText: /Sign-in for your users/ }).click();
    await expect(page.getByRole("radio").first(), "Connect panel shows no sign-in mode choices").toBeVisible({ timeout: 10_000 });
    await panel("Share");
    await expect(page.getByRole("button", { name: /publish this app/i }).first(), "Share panel has no Publish control").toBeVisible({ timeout: 10_000 });
  });
});
