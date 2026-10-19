import { test, expect, type Page, type BrowserContext } from "@playwright/test";
import * as fs from "fs";
import * as path from "path";

/**
 * Efficiency measurements on the local kind stage (area "perf-efficiency").
 * Light by design: sequential calls, never more than 5 in flight. Entities are prefixed e2e-perf-efficiency-.
 * Browser URLs of the stage are plain http (see stage-workflow-exec-live.spec.ts for the reason), so this file
 * carries its own sign-in and captures the bearer from the app's own requests.
 * Budgets are deliberately loose (stage VM is tiny); the metrics JSON is the real output.
 */
const DOMAIN = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${DOMAIN}`;
const API = `http://api.${DOMAIN}`;
const PREFIX = "e2e-perf-efficiency-";
const EMAIL = process.env.E2E_USER_EMAIL;
const PASSWORD = process.env.E2E_USER_PASSWORD;
const METRICS = path.join(process.env.E2E_OUT || "test-results-stage", "perf-efficiency-metrics.json");

type Resp = { status: number; body: any; ms: number; bytes: number; headers: Record<string, string> };
const pct = (a: number[], p: number) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
const stats = (a: number[]) => ({ n: a.length, p50: pct(a, 0.5), p95: pct(a, 0.95), max: Math.max(...a) });

test.describe.configure({ mode: "serial" });
test.describe("perf-efficiency - kind stage", () => {
  test.setTimeout(240_000);
  let ctx: BrowserContext;
  let page: Page;
  let token = "";
  let agentId = "";
  const created: string[] = [];
  const timings: Record<string, number[]> = {};
  const sizes: Record<string, number> = {};
  const consoleErrors: string[] = [];
  const failedReqs: string[] = [];
  const pageMetrics: Record<string, unknown> = {};
  const extra: Record<string, unknown> = {};

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

  async function api(method: string, p: string, body?: unknown, headers: Record<string, string> = {}): Promise<Resp> {
    const go = async (): Promise<Resp> => {
      const t0 = Date.now();
      const r = await page.request.fetch(`${API}${p}`, {
        method,
        headers: { "content-type": "application/json", authorization: `Bearer ${token}`, ...headers },
        data: body === undefined ? undefined : JSON.stringify(body),
        timeout: 90_000,
      });
      const ms = Date.now() - t0;
      const txt = await r.text();
      let parsed: any = txt;
      try { parsed = JSON.parse(txt); } catch { /* not json */ }
      const key = `${method} ${p.replace(/[0-9a-f-]{36}/g, ":id").split("?")[0]}`;
      (timings[key] ||= []).push(ms);
      sizes[key] = Math.max(sizes[key] || 0, txt.length);
      return { status: r.status(), body: parsed, ms, bytes: txt.length, headers: r.headers() };
    };
    let r = await go();
    if (r.status === 401) {
      const before = token;
      await page.goto(`${APP}/home`, { waitUntil: "domcontentloaded" });
      await expect.poll(() => token !== before, { timeout: 20_000 }).toBeTruthy().catch(() => {});
      r = await go();
    }
    return r;
  }

  test.beforeAll(async ({ browser }, testInfo) => {
    test.skip(!EMAIL || !PASSWORD, "E2E_USER_EMAIL/E2E_USER_PASSWORD not set");
    test.skip(process.env.E2E_ALLOW_MUTATIONS !== "1", "writes data - needs E2E_ALLOW_MUTATIONS=1");
    test.skip(testInfo.project.name !== "chromium", "one project only");
    ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    page = await ctx.newPage();
    await page.addInitScript(() => {
      const c: any = crypto;
      if (!c.randomUUID) c.randomUUID = () => "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (x: any) =>
        (x ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (x / 4)))).toString(16));
    });
    page.on("request", (r) => {
      const a = r.headers()["authorization"];
      if (a && r.url().startsWith(API)) token = a.replace(/^Bearer /, "");
    });
    await signIn();
    const agents = await api("GET", "/api/agents");
    agentId = (agents.body as any[]).find((a) => a.name === "load-test-agent")?.id ?? (agents.body as any[])[0]?.id ?? "";
    // listeners for the page-load test only (api() uses page.request, which these do not see)
    page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(`${new URL(page.url()).pathname} :: ${m.text().slice(0, 160)}`); });
    page.on("requestfailed", (r) => {
      if (/_rsc=|\/events$/.test(r.url())) return;
      failedReqs.push(`${new URL(page.url()).pathname} :: ${r.method()} ${r.url().slice(0, 120)} ${r.failure()?.errorText}`);
    });
  });

  test.afterAll(async () => {
    if (!page) return;
    const list = await api("GET", "/api/workflows").catch(() => null);
    const mine = ((list?.body as any[]) || []).filter((w) => String(w.name).startsWith(PREFIX)).map((w) => w.id);
    for (const id of new Set([...created, ...mine])) await api("DELETE", `/api/workflows/${id}`).catch(() => {});
    const left = await api("GET", "/api/workflows").catch(() => null);
    const stillThere = ((left?.body as any[]) || []).filter((w) => String(w.name).startsWith(PREFIX));
    const summary = Object.fromEntries(Object.entries(timings).map(([k, v]) => [k, { ...stats(v), maxBytes: sizes[k] }]));
    fs.mkdirSync(path.dirname(METRICS), { recursive: true });
    fs.writeFileSync(METRICS, JSON.stringify({ summary, pageMetrics, extra, consoleErrors, failedReqs, leftover: stillThere.length }, null, 1));
    await ctx?.close();
    expect(stillThere.length, "cleanup left e2e-perf-efficiency- workflows behind").toBe(0);
  });

  test("list endpoints: latency, payload size, ETag/compression", async () => {
    const eps = ["/api/agents", "/api/workflows", "/api/providers", "/api/tools/scripts", "/api/plugins/catalog", "/api/secrets", "/api/knowledge/collections", "/api/conversations"];
    const rows: Record<string, unknown> = {};
    for (const ep of eps) {
      const ms: number[] = [];
      let last: Resp | undefined;
      for (let i = 0; i < 5; i++) { last = await api("GET", ep); ms.push(last.ms); }
      rows[ep] = { status: last!.status, ...stats(ms), bytes: last!.bytes, enc: last!.headers["content-encoding"] || "none", etag: !!last!.headers["etag"], cache: last!.headers["cache-control"] };
    }
    extra.listEndpoints = rows;
    for (const [ep, r] of Object.entries(rows) as [string, any][]) {
      if (r.status === 200) expect(r.p95, `${ep} p95`).toBeLessThan(2000);
    }
  });

  test("workflow create + execute latency", async () => {
    const g = {
      nodes: [
        { id: "t1", type: "trigger", position: { x: 0, y: 0 }, data: { label: "Trigger", config: { channel: null } } },
        { id: "c1", type: "code", position: { x: 260, y: 0 }, data: { label: "c1", config: { operation: "template", template: "hi {{input}}" } } },
        { id: "o", type: "output", position: { x: 520, y: 0 }, data: { label: "Output", config: { channel: "log" } } },
      ],
      edges: [{ id: "e1", source: "t1", target: "c1", sourceHandle: null, targetHandle: null }, { id: "e2", source: "c1", target: "o", sourceHandle: null, targetHandle: null }],
    };
    const create: number[] = [], run: number[] = [], del: number[] = [];
    for (let i = 0; i < 6; i++) {
      const c = await api("POST", "/api/workflows", { name: `${PREFIX}wf-${i}`, description: "", graph: g });
      expect(c.status, JSON.stringify(c.body).slice(0, 200)).toBe(201);
      created.push(c.body.id); create.push(c.ms);
      const r = await api("POST", `/api/workflows/${c.body.id}/run`, { input: "x" });
      expect(r.status).toBe(200);
      expect(r.body.status).toBe("COMPLETED");
      run.push(r.ms);
    }
    for (const id of created.slice(0, 3)) { const d = await api("DELETE", `/api/workflows/${id}`); del.push(d.ms); }
    extra.workflow = { create: stats(create), run: stats(run), delete: stats(del) };
    expect(pct(create, 0.95)).toBeLessThan(2000);
    expect(pct(run, 0.95)).toBeLessThan(2000);
  });

  test("tool execution latency (script tool test endpoint, workflow tool-less code node)", async () => {
    const ms: number[] = [];
    let status = 0;
    for (let i = 0; i < 5; i++) {
      const r = await api("POST", "/api/tools/scripts/test", { expression: "input", sampleInputJson: '{"a":1}' });
      status = r.status; ms.push(r.ms);
    }
    extra.scriptToolTest = { status, ...stats(ms) };
    expect([200, 400, 403]).toContain(status);
  });

  test("chat turn latency: total + time-to-first-byte, <=3 concurrent", async () => {
    test.skip(!agentId, "no agent on the tenant");
    const oneTurn = async (msg: string) => {
      const t0 = Date.now();
      const res = await fetch(`${API}/api/agents/${agentId}/chat`, {
        method: "POST",
        headers: { "content-type": "application/json", accept: "text/event-stream", authorization: `Bearer ${token}` },
        body: JSON.stringify({ message: msg }),
      });
      const reader = res.body!.getReader();
      let ttfb = -1, bytes = 0, text = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (ttfb < 0) ttfb = Date.now() - t0;
        bytes += value.length; text += Buffer.from(value).toString("utf8");
      }
      return { status: res.status, ttfb, total: Date.now() - t0, bytes, text };
    };
    const seq: Awaited<ReturnType<typeof oneTurn>>[] = [];
    for (let i = 0; i < 6; i++) seq.push(await oneTurn(`${PREFIX}ping ${i}`));
    const conc = await Promise.all([1, 2, 3].map((i) => oneTurn(`${PREFIX}conc ${i}`)));
    extra.chat = {
      sequentialTotal: stats(seq.map((r) => r.total)), sequentialTtfb: stats(seq.map((r) => r.ttfb)),
      concurrent3Total: stats(conc.map((r) => r.total)), bytes: seq[0].bytes, sample: seq[0].text.slice(0, 300),
      statuses: [...seq, ...conc].map((r) => r.status),
    };
    for (const r of [...seq, ...conc]) expect(r.status).toBe(200);
    expect(pct(seq.map((r) => r.total), 0.95), "chat turn p95 (mock model)").toBeLessThan(5000);
  });

  test("page loads: time, request count, duplicates, payload, console errors", async () => {
    const routes = ["/home", "/agents", "/workflows", "/playground", "/tools", "/knowledge", "/providers", "/secrets", "/eval", "/insights", "/jobs", "/scheduled", "/memory", "/devices", "/billing", "/files", "/data-tables", "/integrations", "/plugins", "/shared-with-me"];
    const dups: Record<string, string[]> = {};
    for (const r of routes) {
      const reqs: { key: string; ms: number; bytes: number; status: number }[] = [];
      const started = new Map<any, number>();
      const onReq = (q: any) => started.set(q, Date.now());
      const onResp = async (resp: any) => {
        const q = resp.request();
        const u = new URL(resp.url());
        if (!/\/api\/|\.nip\.io/.test(u.host + u.pathname) || /_next\/|_rsc=/.test(u.href)) return;
        const len = Number(resp.headers()["content-length"] || 0);
        reqs.push({ key: `${q.method()} ${u.host.split(".")[0]}${u.pathname}${u.search}`, ms: Date.now() - (started.get(q) || Date.now()), bytes: len, status: resp.status() });
      };
      page.on("request", onReq); page.on("response", onResp);
      const t0 = Date.now();
      await page.goto(`${APP}${r}`, { waitUntil: "domcontentloaded" });
      const dcl = Date.now() - t0;
      await page.waitForLoadState("networkidle", { timeout: 20_000 }).catch(() => {});
      const idle = Date.now() - t0;
      page.off("request", onReq); page.off("response", onResp);
      const nav = await page.evaluate(() => {
        const n = performance.getEntriesByType("navigation")[0] as any;
        const lcp = (performance.getEntriesByType("largest-contentful-paint") as any[]).pop();
        const cls = (performance.getEntriesByType("layout-shift") as any[]).filter((e) => !e.hadRecentInput).reduce((a, e) => a + e.value, 0);
        const res = performance.getEntriesByType("resource") as any[];
        return { ttfb: Math.round(n?.responseStart || 0), lcp: Math.round(lcp?.startTime || 0), cls: +cls.toFixed(3), resources: res.length, transferKB: Math.round(res.reduce((a, e) => a + (e.transferSize || 0), 0) / 1024), path: location.pathname };
      }).catch(() => ({} as any));
      const counts: Record<string, number> = {};
      for (const q of reqs) counts[q.key] = (counts[q.key] || 0) + 1;
      const d = Object.entries(counts).filter(([, n]) => n > 1).map(([k, n]) => `${k} x${n}`);
      if (d.length) dups[r] = d;
      pageMetrics[r] = {
        landed: nav.path, dcl, idle, maxApiMs: Math.max(0, ...reqs.map((q) => q.ms)), ...nav, apiCalls: reqs.length, slowest: reqs.sort((a, b) => b.ms - a.ms).slice(0, 2).map((q) => `${q.key} ${q.ms}ms ${q.status}`),
        non2xx: reqs.filter((q) => q.status >= 400).map((q) => `${q.key} ${q.status}`),
        biggest: reqs.sort((a, b) => b.bytes - a.bytes).slice(0, 1).map((q) => `${q.key} ${q.bytes}B`),
      };
    }
    extra.duplicateRequestsPerPage = dups;
    // networkidle is NOT asserted: pages holding an SSE/iframe connection open never reach it (observed: /home /workflows
    // /integrations /plugins hit the 20s cap with 0 background requests, see the idle polling test), so it is not a signal.
    const slow = Object.entries(pageMetrics).filter(([, m]: any) => m.maxApiMs > 2000);
    expect(slow.map(([k]) => k), "pages with an API call slower than 2s").toEqual([]);
  });

  test("idle polling: pages that never go network-idle are chatty in the background", async () => {
    const out: Record<string, unknown> = {};
    for (const r of ["/workflows", "/integrations", "/plugins", "/agents"]) {
      await page.goto(`${APP}${r}`, { waitUntil: "domcontentloaded" });
      await page.waitForTimeout(4000);
      const seen: string[] = [];
      const on = (q: any) => { const u = new URL(q.url()); if (!/_next\/|_rsc=/.test(q.url())) seen.push(`${q.method()} ${u.host.split(".")[0]}${u.pathname}`); };
      page.on("request", on);
      await page.waitForTimeout(12_000);
      page.off("request", on);
      const c: Record<string, number> = {};
      for (const k of seen) c[k] = (c[k] || 0) + 1;
      out[r] = { requestsIn12s: seen.length, top: Object.entries(c).sort((a, b) => b[1] - a[1]).slice(0, 4) };
    }
    extra.idlePolling = out;
    for (const [r, v] of Object.entries(out) as [string, any][]) expect(v.requestsIn12s, `${r} background requests in 12s idle`).toBeLessThan(15);
  });
});
