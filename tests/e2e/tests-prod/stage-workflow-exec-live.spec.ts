import { test, expect, type Page, type BrowserContext } from "@playwright/test";
import * as fs from "fs";
import * as path from "path";
import { runWorkflow } from "../helpers/workflow-graph";

/**
 * Workflow EXECUTION on the local kind stage (area "workflow-exec").
 *
 * Stage facts this file encodes (see the findings that came with it):
 *  - The browser-facing URLs of the stage are PLAIN HTTP (http://app|api|auth.127.0.0.1.nip.io): the web image is
 *    built with NEXT_PUBLIC_API_BASE_URL/KEYCLOAK_URL = http://..., so loading the console over the ingress'
 *    https:// hits Mixed Content and every API call is blocked. tests-prod helpers hard-code https and the old
 *    "Sign in" button + sessionStorage token, so this spec carries its own sign-in and captures the bearer from
 *    the app's own requests instead of vd_kc_session.
 *  - Plain http is an INSECURE context: crypto.randomUUID is undefined, which breaks the designer's Run button
 *    (WorkflowCanvas.tsx runWithRuntimeInputs). UI tests polyfill it; the defect itself is the test.fixme below.
 *  - Every entity is prefixed e2e-workflow-exec- and removed in afterAll.
 */
const DOMAIN = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${DOMAIN}`;
const API = `http://api.${DOMAIN}`;
const PREFIX = "e2e-workflow-exec-";
const EMAIL = process.env.E2E_USER_EMAIL;
const PASSWORD = process.env.E2E_USER_PASSWORD;
const METRICS = path.join(process.env.E2E_OUT || "test-results-stage", "workflow-exec-metrics.json");

type Resp = { status: number; body: any; ms: number; headers: Record<string, string> };
type N = { id: string; type: string; config?: Record<string, unknown>; label?: string };

const trig: N = { id: "t1", type: "trigger", config: { channel: null }, label: "Trigger" };
const out: N = { id: "o", type: "output", config: { channel: "log" }, label: "Output" };
const code = (id: string, config: Record<string, unknown>): N => ({ id, type: "code", config, label: id });
const tmpl = (id: string, template: string) => code(id, { operation: "template", template });
const E = (source: string, target: string, sourceHandle: string | null = null) => ({
  id: `e-${source}-x-${target}`, source, target, sourceHandle, targetHandle: null,
});
const graph = (nodes: N[], edges: ReturnType<typeof E>[]) => ({
  nodes: nodes.map((n, i) => ({
    id: n.id, type: n.type, position: { x: i * 260, y: 0 }, data: { label: n.label ?? n.type, config: n.config ?? {} },
  })),
  edges,
});

test.describe.configure({ mode: "serial" });
test.describe("workflow execution - kind stage", () => {
  test.setTimeout(120_000);
  let ctx: BrowserContext;
  let page: Page;
  let token = "";
  let tokenAt = 0;
  const created: string[] = [];
  const timings: Record<string, number[]> = {};
  const consoleErrors: string[] = [];
  const failedReqs: string[] = [];
  const bigResponses: string[] = [];
  let agentId = "";

  async function signIn() {
    await page.goto(APP);
    await page.getByRole("link", { name: /^sign in$/i }).first().click();
    await page.getByRole("textbox", { name: "Email" }).fill(EMAIL!);
    await page.getByRole("button", { name: /continue to secure sign-in/i }).click();
    await page.waitForURL(/auth\./, { timeout: 30_000 });
    await page.locator("#username").fill(EMAIL!);
    await page.locator("#password").fill(PASSWORD!);
    await page.locator("#kc-login").click();
    await page.waitForURL(/app\./, { timeout: 45_000 });
    await expect.poll(() => token, { timeout: 30_000 }).not.toBe("");
  }

  async function dismissOverlaysOn(pg: Page) {
    for (const re of [/^skip for now$/i, /^not now$/i, /^skip, i'll explore$/i, /^got it$/i]) {
      await pg.getByRole("button", { name: re }).first().click({ timeout: 1500 }).catch(() => {});
    }
    await pg.getByRole("button", { name: /close the tour/i }).click({ timeout: 500 }).catch(() => {});
  }
  const dismissOverlays = () => dismissOverlaysOn(page);

  async function api(method: string, p: string, body?: unknown, headers: Record<string, string> = {}, auth = true): Promise<Resp> {
    const go = async (): Promise<Resp> => {
      const t0 = Date.now();
      const r = await page.request.fetch(`${API}${p}`, {
        method,
        headers: { "content-type": "application/json", ...(auth ? { authorization: `Bearer ${token}` } : {}), ...headers },
        data: body === undefined ? undefined : JSON.stringify(body),
        timeout: 90_000,
      });
      const ms = Date.now() - t0;
      const txt = await r.text();
      let parsed: any = txt;
      try { parsed = JSON.parse(txt); } catch { /* not json */ }
      const key = `${method} ${p.replace(/[0-9a-f-]{36}/g, ":id").split("?")[0]}`;
      (timings[key] ||= []).push(ms);
      if (txt.length > 100_000) bigResponses.push(`${key} ${txt.length}B`);
      return { status: r.status(), body: parsed, ms, headers: r.headers() };
    };
    let r = await go();
    if (r.status === 401 && auth) {
      // token aged out while the page sat idle: let the app refresh it, then retry once
      const before = token;
      await page.goto(`${APP}/workflows`, { waitUntil: "domcontentloaded" });
      await expect.poll(() => token !== before, { timeout: 20_000 }).toBeTruthy().catch(() => {});
      r = await go();
    }
    return r;
  }

  async function mk(name: string, nodes: N[], edges: ReturnType<typeof E>[]): Promise<string> {
    const r = await api("POST", "/api/workflows", { name: PREFIX + name, description: "", graph: graph(nodes, edges) });
    expect(r.status, JSON.stringify(r.body).slice(0, 300)).toBe(201);
    created.push(r.body.id);
    return r.body.id;
  }
  const run = (id: string, input: string, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}) =>
    api("POST", `/api/workflows/${id}/run`, { input, ...extra }, headers);
  const trace = (r: Resp) => (r.body.trace as any[]).map((t) => t.nodeId);
  const step = (r: Resp, id: string) => (r.body.trace as any[]).find((t) => t.nodeId === id);

  test.beforeAll(async ({ browser }, testInfo) => {
    test.skip(!EMAIL || !PASSWORD, "E2E_USER_EMAIL/E2E_USER_PASSWORD not set");
    test.skip(process.env.E2E_ALLOW_MUTATIONS !== "1", "writes data - needs E2E_ALLOW_MUTATIONS=1");
    test.skip(testInfo.project.name !== "chromium", "one project only");
    ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    page = await ctx.newPage();
    // insecure-context polyfill so the designer's Run button can be exercised at all on http://
    await page.addInitScript(() => {
      const c: any = crypto;
      if (!c.randomUUID) {
        c.randomUUID = () =>
          "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (x: any) =>
            (x ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (x / 4)))).toString(16));
      }
    });
    page.on("request", (r) => {
      const a = r.headers()["authorization"];
      if (a && r.url().startsWith(API)) { token = a.replace(/^Bearer /, ""); tokenAt = Date.now(); }
    });
    page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(`${new URL(page.url()).pathname} :: ${m.text().slice(0, 160)}`); });
    page.on("requestfailed", (r) => {
      if (/_rsc=|\/events$/.test(r.url())) return; // Next prefetch aborts and SSE teardown are expected
      failedReqs.push(`${r.method()} ${r.url()} ${r.failure()?.errorText}`);
    });
    await signIn();
    const agents = await api("GET", "/api/agents");
    agentId = (agents.body as any[]).find((a) => a.name === "load-test-agent")?.id ?? (agents.body as any[])[0]?.id ?? "";
  });

  test.afterAll(async () => {
    if (!page) return;
    // sweep by prefix so a half-failed run never leaves entities behind
    const list = await api("GET", "/api/workflows").catch(() => null);
    const mine = ((list?.body as any[]) || []).filter((w) => String(w.name).startsWith(PREFIX)).map((w) => w.id);
    for (const id of new Set([...created, ...mine])) {
      await api("DELETE", `/api/workflows/${id}/schedule`).catch(() => {});
      await api("DELETE", `/api/workflows/${id}`).catch(() => {});
    }
    const left = await api("GET", "/api/workflows").catch(() => null);
    const stillThere = ((left?.body as any[]) || []).filter((w) => String(w.name).startsWith(PREFIX));
    const summary = Object.fromEntries(
      Object.entries(timings).map(([k, v]) => [k, { n: v.length, max: Math.max(...v), avg: Math.round(v.reduce((a, b) => a + b, 0) / v.length) }])
    );
    fs.mkdirSync(path.dirname(METRICS), { recursive: true });
    fs.writeFileSync(METRICS, JSON.stringify({ summary, consoleErrors, failedReqs, bigResponses, leftover: stillThere.length }, null, 1));
    await ctx?.close();
    expect(stillThere.length, "cleanup left e2e-workflow-exec- workflows behind").toBe(0);
  });

  // ------------------------------------------------------------------ run + per-node output
  test("linear run: per-node input/output trace, run history, persistence, cross-workflow isolation", async () => {
    const id = await mk("linear", [trig, tmpl("c1", "hi {{input}}"), code("c2", { operation: "uppercase" }), out],
      [E("t1", "c1"), E("c1", "c2"), E("c2", "o")]);
    const r = await run(id, "bob");
    expect(r.status).toBe(200);
    expect(r.body.status).toBe("COMPLETED");
    expect(trace(r)).toEqual(["t1", "c1", "c2", "o"]);
    expect(step(r, "c1")).toMatchObject({ status: "OK", inputJson: "bob", outputJson: "hi bob" });
    expect(step(r, "c2")).toMatchObject({ inputJson: "hi bob", outputJson: "HI BOB" });
    expect(r.body.mode).toBe("production");
    expect(r.ms, "trivial 4-node run should be well under 2s").toBeLessThan(2000);
    // history + single fetch agree, survive "reload" (fresh GET)
    const list = await api("GET", `/api/workflows/${id}/runs`);
    expect(list.status).toBe(200);
    expect(list.body.map((x: any) => x.id)).toContain(r.body.id);
    expect(list.headers["x-total-count"]).toBe("1");
    const one = await api("GET", `/api/workflows/${id}/runs/${r.body.id}`);
    expect(one.body.trace.length).toBe(4);
    expect(one.body.status).toBe("COMPLETED");
    // a run id is only reachable through ITS workflow
    const other = await mk("linear-other", [trig, out], [E("t1", "o")]);
    const cross = await api("GET", `/api/workflows/${other}/runs/${r.body.id}`);
    expect(cross.status, "run readable through a different workflow id").toBe(404);
    // paging params are clamped, not trusted
    expect((await api("GET", `/api/workflows/${id}/runs?limit=1&offset=0`)).body.length).toBe(1);
    expect((await api("GET", `/api/workflows/${id}/runs?limit=abc`)).status).toBe(400);
  });

  test("condition branching executes exactly one branch", async () => {
    const id = await mk("cond",
      [trig, { id: "k", type: "condition", config: { contains: "refund" } }, tmpl("a", "BILL:{{input}}"), tmpl("b", "GEN:{{input}}"), out],
      [E("t1", "k"), E("k", "a", "true"), E("k", "b", "false"), E("a", "o"), E("b", "o")]);
    const yes = await run(id, "a refund please");
    expect(trace(yes)).toEqual(["t1", "k", "a", "o"]);
    expect(step(yes, "o").outputJson).toBe("BILL:a refund please");
    const no = await run(id, "hello");
    expect(trace(no)).toEqual(["t1", "k", "b", "o"]);
    expect(step(no, "o").outputJson).toBe("GEN:hello");
  });

  test("switch routes to the matching case and to default", async () => {
    const id = await mk("switch",
      [trig,
        { id: "s", type: "switch", config: { cases: [
          { name: "big", conditions: [{ field: "amount", operator: "gt", value: "100" }] },
          { name: "small", conditions: [{ field: "amount", operator: "lte", value: "100" }] }] } },
        tmpl("a", "BIG"), tmpl("b", "SMALL"), tmpl("d", "DEF"), out],
      [E("t1", "s"), E("s", "a", "big"), E("s", "b", "small"), E("s", "d", "default"), E("a", "o"), E("b", "o"), E("d", "o")]);
    expect(step(await run(id, '{"amount":500}'), "o").outputJson).toBe("BIG");
    expect(step(await run(id, '{"amount":5}'), "o").outputJson).toBe("SMALL");
    const def = await run(id, '{"x":1}');
    expect(step(def, "o").outputJson).toBe("DEF");
    expect(step(def, "s").summary).toMatch(/default/i);
  });

  test("loop repeats its body per item, honours maxIterations, and says so", async () => {
    const id = await mk("loop", [trig, { id: "l", type: "loop", config: { itemsField: "", maxIterations: 3 } }, tmpl("c", "<{{input}}>"), out],
      [E("t1", "l"), E("l", "c"), E("c", "o")]);
    const r = await run(id, '["a","b","c","d","e"]');
    expect(r.body.status).toBe("COMPLETED");
    expect(step(r, "o").outputJson).toBe('["<a>","<b>","<c>"]');
    expect(step(r, "l").summary).toMatch(/3 of 5 item\(s\) \(capped at 3\)/);
    expect((r.body.trace as any[]).filter((t) => t.nodeId === "c").length).toBe(3);
    // non-array input: observed behaviour is a silent empty result (see findings)
    const na = await run(id, "plain");
    expect(step(na, "l").summary).toMatch(/0 of 0/);
    expect(step(na, "o").outputJson).toBe("[]");
  });

  test("parallel fan-out merges every branch", async () => {
    const id = await mk("parallel", [trig, { id: "p", type: "parallel" }, code("u", { operation: "uppercase" }), code("l", { operation: "lowercase" }), out],
      [E("t1", "p"), E("p", "u"), E("p", "l"), E("u", "o"), E("l", "o")]);
    const r = await run(id, "MiXed");
    expect(r.body.status).toBe("COMPLETED");
    expect(JSON.parse(step(r, "o").outputJson).sort()).toEqual(["MIXED", "mixed"]);
  });

  // ------------------------------------------------------------------ failure / limits
  test("a failing transform and a blocked HTTP call degrade to SKIPPED nodes with a reason (run stays COMPLETED)", async () => {
    const id = await mk("fail", [trig, code("x", { operation: "regex_replace", pattern: "([unclosed", replacement: "x" }), out], [E("t1", "x"), E("x", "o")]);
    const r = await run(id, "abc");
    expect(step(r, "x").status).toBe("SKIPPED");
    expect(step(r, "x").summary).toMatch(/Transform failed/);
    expect(step(r, "o").outputJson, "downstream receives the untouched input").toBe("abc");
    expect(r.body.status, "characterisation: node failure does not fail the run").toBe("COMPLETED");
    for (const [name, url] of [["ssrf-meta", "http://169.254.169.254/latest/meta-data/"], ["ssrf-local", "http://localhost:8080/actuator/health"]]) {
      const h = await mk(name, [trig, { id: "h", type: "http", config: { method: "GET", url } }, out], [E("t1", "h"), E("h", "o")]);
      const hr = await run(h, "x");
      expect(step(hr, "h").status, `${url} must never be fetched`).toBe("SKIPPED");
      expect(step(hr, "h").summary).toMatch(/private or internal|internal host/i);
    }
  });

  test("execution limits: maxStepsPerRun aborts a run, bounds are validated per field, reset restores runs", async () => {
    const id = await mk("limits", [trig, code("c", { operation: "uppercase" }), out], [E("t1", "c"), E("c", "o")]);
    const lim = (a: number, b: number, c: number) => api("PATCH", `/api/workflows/${id}/execution-limits`, { maxStepsPerRun: a, stepTimeoutSeconds: b, maxRetryAttempts: c });
    expect((await lim(1, 30, 0)).status).toBe(200);
    const capped = await run(id, "abc");
    expect(capped.body.status).toBe("FAILED");
    expect(capped.body.errorMessage).toMatch(/maximum of 1 node executions/);
    for (const [args, field] of [[[0, 30, 0], "maxStepsPerRun"], [[201, 30, 0], "maxStepsPerRun"], [[10, 0, 0], "stepTimeoutSeconds"],
      [[10, 301, 0], "stepTimeoutSeconds"], [[10, 30, -1], "maxRetryAttempts"], [[10, 30, 6], "maxRetryAttempts"]] as const) {
      const r = await lim(...(args as [number, number, number]));
      expect(r.status, JSON.stringify(args)).toBe(400);
      expect(String(r.body.detail)).toContain(field);
    }
    // persisted, then restored
    expect((await api("GET", `/api/workflows/${id}`)).body.maxStepsPerRun).toBe(1);
    expect((await lim(50, 30, 0)).status).toBe(200);
    expect((await run(id, "abc")).body.status).toBe("COMPLETED");
  });

  test.fixme("execution limits: a partial PATCH (only maxStepsPerRun) is rejected with a message about the OTHER fields", async () => {
    // DEFECT (api, low): PATCH /execution-limits requires all three fields; sending {maxStepsPerRun:-5} reports
    // "stepTimeoutSeconds must be at least 1" first, so a caller fixing the field it actually sent still gets 400.
    const id = created[created.length - 1];
    const r = await api("PATCH", `/api/workflows/${id}/execution-limits`, { maxStepsPerRun: 10 });
    expect(r.status).toBe(200);
  });

  test("max-steps failure should be attributed to the node that was refused, not duplicate the last one", async () => {
    test.fixme(true, "DEFECT (low): with maxStepsPerRun=1 the trace holds t1 twice (OK then ERROR); the ERROR row reuses the last executed node id instead of the node that would have been step 2");
    const id = await mk("limits-attrib", [trig, code("c", { operation: "uppercase" }), out], [E("t1", "c"), E("c", "o")]);
    await api("PATCH", `/api/workflows/${id}/execution-limits`, { maxStepsPerRun: 1, stepTimeoutSeconds: 30, maxRetryAttempts: 0 });
    const r = await run(id, "abc");
    expect(trace(r)).toEqual(["t1", "c"]);
  });

  test("idempotency: Idempotency-Key replays 409, over-long key 400, clientRunId dedupes to the same run", async () => {
    const id = await mk("idem", [trig, out], [E("t1", "o")]);
    const key = `e2e-wfx-${Date.now()}`;
    expect((await run(id, "x", {}, { "Idempotency-Key": key })).status).toBe(200);
    const dup = await run(id, "x", {}, { "Idempotency-Key": key });
    expect(dup.status).toBe(409);
    expect(dup.body.detail).toMatch(/already used/);
    expect((await run(id, "x", {}, { "Idempotency-Key": "k".repeat(129) })).status).toBe(400);
    const cid = "5b6c1f0e-1d2a-4c3b-9e8f-0a1b2c3d4e5f";
    const a = await run(id, "x", { clientRunId: cid });
    const b = await run(id, "x", { clientRunId: cid });
    expect(a.body.id).toBe(b.body.id);
    expect((await api("GET", `/api/workflows/${id}/runs`)).headers["x-total-count"], "dedupe must not create two rows").toBe("2");
  });

  test("background run returns RUNNING at once; Stop cancels mid-run and no further nodes execute", async () => {
    test.skip(!agentId, "no agent in tenant for a slow body node");
    const id = await mk("cancel", [trig, { id: "l", type: "loop", config: { itemsField: "", maxIterations: 25 } }, { id: "g", type: "agent", config: { agentId } }, out],
      [E("t1", "l"), E("l", "g"), E("g", "o")]);
    const items = JSON.stringify(Array.from({ length: 25 }, (_, i) => `item${i}`));
    const start = await run(id, items, { background: true });
    expect(start.status).toBe(200);
    expect(start.body.status).toBe("RUNNING");
    expect(start.ms, "background start must return fast").toBeLessThan(2000);
    await page.waitForTimeout(2500);
    const mid = await api("GET", `/api/workflows/${id}/runs/${start.body.id}`);
    expect(mid.body.status).toBe("RUNNING");
    const c = await api("POST", `/api/workflows/${id}/runs/${start.body.id}/cancel`);
    expect(c.status).toBe(200);
    await expect.poll(async () => (await api("GET", `/api/workflows/${id}/runs/${start.body.id}`)).body.status, { timeout: 15_000 }).toBe("CANCELLED");
    const n1 = (await api("GET", `/api/workflows/${id}/runs/${start.body.id}`)).body.trace.length;
    await page.waitForTimeout(3000);
    const fin = await api("GET", `/api/workflows/${id}/runs/${start.body.id}`);
    expect(fin.body.trace.length, "nodes kept executing after cancel").toBe(n1);
    expect(fin.body.status).toBe("CANCELLED");
    expect(fin.body.errorMessage).toMatch(/Cancelled/);
    // terminal runs: cancel/pause/resume are accepted no-ops (characterisation, see findings)
    for (const verb of ["cancel", "pause", "resume"]) {
      const r = await api("POST", `/api/workflows/${id}/runs/${start.body.id}/${verb}`);
      expect(r.status).toBe(200);
      expect(r.body.status).toBe("CANCELLED");
    }
  });

  test("request validation: unknown workflow 404, bad id 400, bad mode 400, empty/no-trigger/cyclic graph 400, anonymous 401", async () => {
    const id = await mk("valid", [trig, out], [E("t1", "o")]);
    expect((await run("00000000-0000-4000-8000-000000000000", "x")).status).toBe(404);
    expect((await run("notauuid", "x")).status).toBe(400);
    const bad = await run(id, "x", { mode: "bogus" });
    expect(bad.status).toBe(400);
    expect(bad.body.detail).toMatch(/draft|production/);
    expect((await api("GET", `/api/workflows/${id}/runs/00000000-0000-4000-8000-000000000000`)).status).toBe(404);
    const empty = await mk("empty", [], []);
    const e1 = await run(empty, "x");
    expect(e1.status).toBe(400);
    expect(e1.body.detail).toMatch(/no nodes/);
    const nt = await mk("notrigger", [code("c", { operation: "uppercase" })], []);
    expect((await run(nt, "x")).body.detail).toMatch(/at least one Trigger/);
    const cyc = await mk("cycle", [trig, code("c", { operation: "uppercase" }), code("d", { operation: "lowercase" })], [E("t1", "c"), E("c", "d"), E("d", "c")]);
    expect((await run(cyc, "x")).body.detail).toMatch(/cycle/);
    expect((await api("POST", `/api/workflows/${id}/run`, { input: "x" }, {}, false)).status).toBe(401);
    expect((await api("GET", `/api/workflows/${id}/runs`, undefined, {}, false)).status).toBe(401);
    const draft = await run(id, "x", { mode: "draft" });
    expect(draft.status).toBe(200);
    expect(draft.body.mode).toBe("draft");
  });

  test("lint and validate report a clean graph; validate flags a trigger-less one", async () => {
    const ok = await mk("lint-ok", [trig, out], [E("t1", "o")]);
    expect((await api("POST", `/api/workflows/${ok}/validate`)).body).toMatchObject({ valid: true });
    const l = await api("POST", `/api/workflows/${ok}/lint`, {});
    expect(l.status).toBe(200);
    expect(l.body.summary.errors).toBe(0);
    const bad = await mk("lint-bad", [code("c", { operation: "uppercase" })], []);
    const v = await api("POST", `/api/workflows/${bad}/validate`);
    expect(v.status).toBe(200);
    expect(v.body.valid).toBe(false);
    expect(v.body.errors.join(" ")).toMatch(/Trigger/);
  });

  // ------------------------------------------------------------------ schedules
  test("schedule: needs an ACTIVE workflow, normalises 5-field cron, rejects garbage, disable clears it", async () => {
    const nodes = [trig, code("c", { operation: "uppercase" }), out];
    const edges = [E("t1", "c"), E("c", "o")];
    const id = await mk("sched", nodes, edges);
    const draft = await api("PUT", `/api/workflows/${id}/schedule`, { cron: "0 9 * * *" });
    expect(draft.status).toBe(400);
    expect(draft.body.detail).toMatch(/Activate this workflow/);
    const act = await api("PUT", `/api/workflows/${id}`, { name: PREFIX + "sched", description: "", graph: graph(nodes, edges), status: "ACTIVE" });
    expect(act.body.status).toBe("ACTIVE");
    const ok = await api("PUT", `/api/workflows/${id}/schedule`, { cron: "0 9 * * *" });
    expect(ok.status).toBe(200);
    expect(ok.body).toMatchObject({ cron: "0 0 9 * * *", enabled: true });
    expect(new Date(ok.body.nextRunAt).getTime()).toBeGreaterThan(Date.now());
    expect((await api("GET", `/api/workflows/${id}/schedule`)).body.cron).toBe("0 0 9 * * *");
    for (const bad of ["bogus", "0 0 25 * * *", "61 * * * * *"]) {
      const r = await api("PUT", `/api/workflows/${id}/schedule`, { cron: bad });
      expect(r.status, bad).toBe(400);
      expect(r.body.detail).toMatch(/Invalid schedule/);
    }
    expect((await api("PUT", `/api/workflows/${id}/schedule`, { cron: "" })).status).toBe(400);
    expect((await api("GET", `/api/workflows/${id}/schedule`)).body.cron, "a rejected cron must not clobber the saved one").toBe("0 0 9 * * *");
    const off = await api("DELETE", `/api/workflows/${id}/schedule`);
    expect(off.body.enabled).toBe(false);
    expect((await api("GET", `/api/workflows/${id}/schedule`)).body.nextRunAt).toBeNull();
  });

  test("schedule: sub-minute cron is refused", async () => {
    test.fixme(true, "DEFECT (medium): PUT /schedule accepts '* * * * * *' (every second, nextRunAt = now+1s) - no minimum interval, unlike scheduled-tasks (minIntervalMinutes=15). WorkflowService.enableSchedule only parses the cron.");
    const nodes = [trig, out];
    const id = await mk("sched-fast", nodes, [E("t1", "o")]);
    await api("PUT", `/api/workflows/${id}`, { name: PREFIX + "sched-fast", description: "", graph: graph(nodes, [E("t1", "o")]), status: "ACTIVE" });
    const r = await api("PUT", `/api/workflows/${id}/schedule`, { cron: "* * * * * *" });
    expect(r.status).toBe(400);
  });

  // ------------------------------------------------------------------ intelligence nodes (mock LLM)
  test("agent node runs the in-cluster mock model and records output; classifier/extractor degrade gracefully on a non-JSON model reply", async () => {
    test.skip(!agentId, "no agent in tenant");
    const ag = await mk("agent", [trig, { id: "g", type: "agent", config: { agentId } }, out], [E("t1", "g"), E("g", "o")]);
    const r = await run(ag, "say hi");
    expect(r.body.status).toBe("COMPLETED");
    expect(step(r, "g").status).toBe("OK");
    expect(step(r, "g").outputJson.length).toBeGreaterThan(0);
    expect(step(r, "o").outputJson).toBe(step(r, "g").outputJson);
    expect(r.ms, "single mock-LLM agent node").toBeLessThan(5000);

    const qc = await mk("classifier",
      [trig, { id: "q", type: "question_classifier", config: { classes: [{ name: "billing", description: "money" }, { name: "tech", description: "bugs" }] } },
        tmpl("a", "B"), tmpl("b", "T"), tmpl("d", "D"), out],
      [E("t1", "q"), E("q", "a", "billing"), E("q", "b", "tech"), E("q", "d", "default"), E("a", "o"), E("b", "o"), E("d", "o")]);
    const c = await run(qc, "I was charged twice");
    expect(c.body.status).toBe("COMPLETED");
    expect(step(c, "q").summary, "mock model returns prose: classifier must fall back, not fail").toMatch(/default class/i);

    const pe = await mk("extractor", [trig, { id: "p", type: "parameter_extractor", config: { parameters: [{ name: "city", type: "string", description: "city" }] } }, out],
      [E("t1", "p"), E("p", "o")]);
    const x = await run(pe, "I live in Paris");
    expect(x.body.status).toBe("COMPLETED");
    expect(JSON.parse(step(x, "o").outputJson)).toHaveProperty("city");
  });

  test("5 concurrent runs of one workflow all complete independently and quickly", async () => {
    const id = await mk("concurrent", [trig, code("c", { operation: "uppercase" }), out], [E("t1", "c"), E("c", "o")]);
    const t0 = Date.now();
    const rs = await Promise.all([1, 2, 3, 4, 5].map((i) => run(id, `n${i}`)));
    expect(rs.map((r) => r.body.status)).toEqual(Array(5).fill("COMPLETED"));
    expect(rs.map((r) => step(r, "o").outputJson).sort()).toEqual(["N1", "N2", "N3", "N4", "N5"]);
    expect(new Set(rs.map((r) => r.body.id)).size).toBe(5);
    expect(Date.now() - t0).toBeLessThan(8000);
  });

  // ------------------------------------------------------------------ UI
  let uiId = "";
  test("UI /workflows: seeded workflow is listed, counters match, row opens the designer, Back returns", async () => {
    uiId = await mk("ui", [trig, { id: "k", type: "condition", config: { contains: "refund" } }, tmpl("a", "BILL:{{input}}"), tmpl("b", "GEN:{{input}}"), out],
      [E("t1", "k"), E("k", "a", "true"), E("k", "b", "false"), E("a", "o"), E("b", "o")]);
    await page.goto(`${APP}/workflows`, { waitUntil: "domcontentloaded" });
    await dismissOverlays();
    const row = page.getByText(PREFIX + "ui", { exact: true }).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/TOTAL WORKFLOWS/i).first()).toBeVisible();
    const total = (await api("GET", "/api/workflows")).body.length;
    await expect(page.getByRole("button", { name: new RegExp(`^All\\s*${total}$`) }).first()).toBeVisible();
    await row.click();
    await page.waitForURL(new RegExp(`/workflows/${uiId}`), { timeout: 20_000 });
    await dismissOverlays();
    await expect(page.getByRole("button", { name: /run now/i })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(PREFIX + "ui").first()).toBeVisible();
    await page.getByRole("button", { name: /← back/i }).click();
    await page.waitForURL(/\/workflows$/, { timeout: 15_000 });
  });

  test("UI designer: Run modal Cancel closes without running; Run executes, inspector shows steps/raw trace/history, run log page opens, history survives reload", async () => {
    await page.goto(`${APP}/workflows/${uiId}`, { waitUntil: "domcontentloaded" });
    await dismissOverlays();
    await expect(page.getByText(/No run yet/i).first()).toBeAttached({ timeout: 20_000 });
    await expect(page.getByRole("button", { name: /run now/i })).toBeEnabled({ timeout: 20_000 });
    // Cancel path
    await page.getByRole("button", { name: /run now/i }).click();
    const prompt = page.getByPlaceholder(/e\.g\. a sample inbound message/i);
    await expect(prompt).toBeVisible();
    await prompt.fill("should not run");
    await page.getByRole("button", { name: /^cancel$/i }).click();
    await expect(prompt).toBeHidden();
    expect((await api("GET", `/api/workflows/${uiId}/runs`)).body.length, "Cancel must not start a run").toBe(0);
    // Run path
    await page.getByRole("button", { name: /run now/i }).click();
    await page.getByPlaceholder(/e\.g\. a sample inbound message/i).fill("please refund me");
    await page.getByPlaceholder(/e\.g\. testing the new refund policy/i).fill("e2e comment");
    await page.getByRole("button", { name: /\+ add parameter/i }).click();
    const t0 = Date.now();
    const reqP = page.waitForRequest((r) => r.method() === "POST" && /\/runs?$|\/run$/.test(r.url()) && r.url().includes(uiId));
    await page.getByRole("button", { name: /run$/i }).click();
    const req = await reqP;
    const sent = JSON.parse(req.postData() || "{}");
    // characterisation (see findings): the optional "Run comment" is appended to the run INPUT, so nodes see it
    expect(sent.input).toBe("please refund me\n\nRun comment: e2e comment");
    expect(sent.background, "UI must use fire-and-poll").toBe(true);
    expect(sent.clientRunId).toMatch(/^[0-9a-f-]{36}$/);
    // after completion the designer opens the run replay: banner, flight recorder (one bar per EXECUTED node), flight log
    await expect(page.getByText(/Viewing run #1/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("Completed", { exact: true }).first()).toBeVisible();
    expect(Date.now() - t0, "UI click-Run to replay shown, 5-node transform workflow").toBeLessThan(10_000);
    await expect(page.getByText(/FLIGHT RECORDER/i).first()).toBeVisible();
    await expect(page.getByText(/4 steps/).first(), "branch not taken must not count as a step").toBeVisible();
    await expect(page.getByText("Not run", { exact: true }).first(), "the untaken branch node is marked Not run").toBeVisible();
    await expect(page.getByText(/Flight log/i).first()).toBeVisible();
    await page.getByRole("button", { name: /back to editing/i }).click();
    await expect(page.getByRole("region", { name: "Inspector" })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("tab", { name: "Steps" })).toHaveAttribute("aria-selected", "true");
    await page.getByRole("tab", { name: /raw trace/i }).click();
    await expect(page.getByText(/Condition 'refund' -> true/).first()).toBeVisible();
    await expect(page.getByText(/Received: please refund me/).first()).toBeVisible();
    await page.getByRole("tab", { name: /history/i }).click();
    const runs = (await api("GET", `/api/workflows/${uiId}/runs`)).body;
    expect(runs.length).toBe(1);
    expect(runs[0].requestedBy).toBeTruthy();
    await expect(page.getByRole("button", { name: new RegExp(`#${runs[0].id.slice(0, 8)}\\s+COMPLETED`) })).toBeVisible();
    // full run log page
    await page.getByRole("link", { name: /view full run log/i }).click();
    await page.waitForURL(new RegExp(`/workflows/${uiId}/runs/${runs[0].id}`), { timeout: 15_000 });
    await expect(page.getByText(/COMPLETED/).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/BILL:please refund me/).first()).toBeVisible();
    // reload persistence
    await page.goto(`${APP}/workflows/${uiId}`, { waitUntil: "domcontentloaded" });
    await dismissOverlays();
    await page.getByRole("button", { name: /replay and inspect past runs|^runs$/i }).click();
    await expect(page.getByText(/Flight log/i).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/#1\s*Completed/).first(), "run persisted across reload").toBeVisible({ timeout: 20_000 });
  });

  test("UI designer: Validate, Save Draft, Activate and the Intelligence limits modal act on the real workflow", async () => {
    await page.goto(`${APP}/workflows/${uiId}`, { waitUntil: "domcontentloaded" });
    await dismissOverlays();
    await expect(page.getByRole("button", { name: /run now/i })).toBeEnabled({ timeout: 20_000 });
    const validateP = page.waitForResponse((r) => /\/validate$/.test(r.url()) && r.request().method() === "POST");
    await page.getByRole("button", { name: /^validate$/i }).click();
    expect((await validateP).status()).toBe(200);
    const putP = page.waitForResponse((r) => r.url().endsWith(`/api/workflows/${uiId}`) && r.request().method() === "PUT");
    await page.getByRole("button", { name: /save draft/i }).click();
    expect((await putP).status()).toBe(200);
    // Intelligence -> execution limits
    await page.getByRole("button", { name: /^more$/i }).click();
    await page.getByRole("button", { name: /intelligence/i }).last().click();
    const field = page.getByLabel(/max steps per run/i);
    await expect(field).toBeVisible({ timeout: 15_000 });
    await field.fill("7");
    const patchP = page.waitForResponse((r) => /execution-limits$/.test(r.url()) && r.request().method() === "PATCH");
    await page.locator("text=Execution limits").locator("xpath=ancestor::div[1]").getByRole("button", { name: /^save$/i }).click();
    const patch = await patchP;
    expect(patch.status(), await patch.text()).toBe(200);
    expect((await api("GET", `/api/workflows/${uiId}`)).body.maxStepsPerRun).toBe(7);
    await page.getByRole("button", { name: "Close", exact: true }).first().click();
    // out-of-range value is not silently persisted
    await api("PATCH", `/api/workflows/${uiId}/execution-limits`, { maxStepsPerRun: 50, stepTimeoutSeconds: 30, maxRetryAttempts: 0 });
    // Activate -> ACTIVE in the API and badge
    const actP = page.waitForResponse((r) => r.url().endsWith(`/api/workflows/${uiId}`) && r.request().method() === "PUT");
    await page.getByRole("button", { name: /^activate$/i }).click();
    await actP;
    await expect.poll(async () => (await api("GET", `/api/workflows/${uiId}`)).body.status).toBe("ACTIVE");
    await expect(page.getByText("ACTIVE").first()).toBeVisible({ timeout: 10_000 });
  });

  test("UI /scheduled: shows the 'not turned on' state that /api/scheduled-tasks/capabilities reports", async () => {
    const cap = await api("GET", "/api/scheduled-tasks/capabilities");
    expect(cap.status).toBe(200);
    await page.goto(`${APP}/scheduled`, { waitUntil: "domcontentloaded" });
    await dismissOverlays();
    if (cap.body.enabled === false) {
      await expect(page.getByText(/Scheduled tasks aren't turned on/i)).toBeVisible({ timeout: 20_000 });
      await expect(page.getByRole("button", { name: /new (scheduled )?task|add/i })).toHaveCount(0);
    } else {
      test.info().annotations.push({ type: "blocked", description: "scheduled tasks enabled on this stage - extend this test" });
      await expect(page.getByRole("heading", { name: /scheduled tasks/i })).toBeVisible();
    }
  });

  test("UI designer Run button completes a run on an insecure (http) origin", async () => {
    // A fresh page WITHOUT the polyfill; the secure-origin browser flag would otherwise keep randomUUID alive.
    const p2 = await ctx.newPage();
    await p2.addInitScript(() => { try { Object.defineProperty(crypto, "randomUUID", { value: undefined, configurable: true }); } catch { /* keep going */ } });
    try {
      await p2.goto(`${APP}/workflows/${uiId}`, { waitUntil: "domcontentloaded" });
      await dismissOverlaysOn(p2);
      expect(await p2.evaluate(() => typeof (crypto as any).randomUUID), "crypto.randomUUID must be absent to model an http origin").toBe("undefined");
      const before = (await api("GET", `/api/workflows/${uiId}/runs`)).body.length;
      const status = await runWorkflow(p2, "hello from the http origin");
      expect(status).toBe("COMPLETED");
      expect((await api("GET", `/api/workflows/${uiId}/runs`)).body.length).toBe(before + 1);
    } finally {
      await p2.close();
    }
  });
});
