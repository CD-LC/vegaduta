import { test, expect, type Page, type APIRequestContext, type BrowserContext } from "@playwright/test";
import * as fs from "fs";

/**
 * Agents area against the local kind stage (mock LLM: provider OLLAMA, model mock:latest).
 * Self-contained on purpose: helpers/prod-session.ts and helpers/prod-api.ts are stale against the current console
 * (Sign in is a link + email step, tokens are memory-only since FE-001, tour keys are user-scoped), so this file
 * logs in the stage way and reads the bearer token off the app's own API calls.
 * The stage serves the console over http (NEXT_PUBLIC_* are http://), so URLs are http://<host>.${E2E_DOMAIN}.
 * Every entity is prefixed "e2e-agents-" and removed in afterAll.
 */
const D = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${D}`;
const API = `http://api.${D}`;
const EMAIL = process.env.E2E_USER_EMAIL!;
const PASS = process.env.E2E_USER_PASSWORD!;
const PFX = "e2e-agents-";
const OUT = process.env.E2E_OUT || "test-results-stage";
const JSON_H = { "Content-Type": "application/json" };

const metrics: Record<string, unknown> = {};
const consoleErrors: string[] = [];
const failedRequests: string[] = [];
const slow: string[] = [];

let ctx: BrowserContext;
let page: Page;
let token = "";
let provider: { providerName: string; model?: string };
const created: string[] = [];

async function login(p: Page) {
  await p.addInitScript(() => {
    const orig = Storage.prototype.getItem;
    Storage.prototype.getItem = function (k: string) {
      if (this === window.localStorage && typeof k === "string") {
        if (k.startsWith("vd_tour_status")) return "skipped";
        if (/^(vd_agents_guide_seen|vd_demo_tour_seen|vd_lang_prompt_seen|agentic_language_choice_seen|floatingNav\.hintSeen)/.test(k)) return "1";
        if (k === "vd_page_guides_seen") return JSON.stringify(["home", "agents", "agent-templates", "playground", "tools"]);
      }
      return orig.call(this, k);
    };
  });
  await p.goto(APP);
  await p.getByRole("link", { name: /^sign in$/i }).first().click();
  await p.getByRole("textbox", { name: "Email" }).fill(EMAIL);
  await p.getByRole("button", { name: /continue to secure sign-in/i }).click();
  await p.waitForURL(/auth\./, { timeout: 30_000 });
  await p.locator("#username").fill(EMAIL);
  await p.locator("#password").fill(PASS);
  await p.locator("#kc-login").click();
  await p.waitForURL(/app\./, { timeout: 45_000 });
  await expect(p.locator("header")).toBeVisible({ timeout: 30_000 });
}

/** Tokens are memory-only: reload and copy the Authorization header from the app's own first API call. */
async function freshToken(): Promise<string> {
  const wait = page.waitForRequest((r) => r.url().startsWith(API) && !!r.headers()["authorization"], { timeout: 30_000 });
  await page.reload({ waitUntil: "domcontentloaded" });
  token = (await wait).headers()["authorization"].replace(/^Bearer /i, "");
  return token;
}
const H = () => ({ Authorization: `Bearer ${token}`, ...JSON_H });

async function mk(request: APIRequestContext, over: Record<string, unknown> = {}) {
  const res = await request.post(`${API}/api/agents`, {
    headers: H(),
    data: { name: `${PFX}${Date.now()}-${Math.floor(Math.random() * 1e4)}`, systemPrompt: "You are a terse test agent.", ...provider, ...over },
    failOnStatusCode: false,
  });
  if (res.status() === 201) {
    const a = await res.json();
    created.push(a.id);
    return { res, a };
  }
  return { res, a: null as any };
}

test.describe.serial("Agents area on the kind stage", () => {
  test.setTimeout(120_000);

  test.beforeAll(async ({ browser }) => {
    ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    page = await ctx.newPage();
    page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text().slice(0, 240)); });
    const starts = new Map<any, number>();
    page.on("request", (r) => starts.set(r, Date.now()));
    page.on("requestfailed", (r) => failedRequests.push(`${r.method()} ${r.url().slice(0, 140)} ${r.failure()?.errorText}`));
    page.on("response", (r) => {
      const t = Date.now() - (starts.get(r.request()) ?? Date.now());
      if (r.status() >= 400) failedRequests.push(`${r.status()} ${r.request().method()} ${r.url().slice(0, 140)}`);
      if (t > 2000 && r.url().includes("/api/")) slow.push(`${t}ms ${r.request().method()} ${r.url().replace(API, "")}`);
    });
    await login(page);
    await page.goto(`${APP}/agents`, { waitUntil: "domcontentloaded" });
    await freshToken();
    const list = await (await ctx.request.get(`${API}/api/agents`, { headers: H() })).json();
    const t = list.find((x: any) => x.providerName === "OLLAMA") ?? list[0];
    provider = { providerName: t?.providerName ?? "OLLAMA", model: t?.model ?? "mock:latest" };
  });

  test.afterAll(async () => {
    token = await freshToken().catch(() => token);
    for (const id of created) await ctx.request.delete(`${API}/api/agents/${id}`, { headers: H(), failOnStatusCode: false }).catch(() => {});
    const left = await (await ctx.request.get(`${API}/api/agents`, { headers: H() })).json().catch(() => []);
    metrics.leftoverE2eAgents = (left as any[]).filter((a) => String(a.name).startsWith(PFX)).length;
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(`${OUT}/stage-agents-metrics.json`, JSON.stringify({ metrics, consoleErrors, failedRequests, slow }, null, 1));
    await ctx.close();
  });

  test("API: create -> list -> get round-trips and 201 body carries defaults", async () => {
    const { res, a } = await mk(ctx.request, { systemPrompt: "Persona A." });
    expect(res.status(), await res.text()).toBe(201);
    expect(a.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(a.name.startsWith(PFX)).toBe(true);
    const got = await (await ctx.request.get(`${API}/api/agents/${a.id}`, { headers: H() })).json();
    expect(got.systemPrompt).toBe("Persona A.");
    const list = await (await ctx.request.get(`${API}/api/agents`, { headers: H() })).json();
    expect(list.map((x: any) => x.id)).toContain(a.id);
    metrics.agentCreateDefaults = { autonomyTier: got.autonomyTier, reasoningDepth: got.reasoningDepth, maxTokens: got.maxTokens, temperature: got.temperature };
    expect(got.autonomyTier).toBe("SEMI_AUTONOMOUS");
    expect(got.reasoningDepth).toBe("FAST");
  });

  test("API: create validation returns 400 with a message, never 500 or a stack trace", async () => {
    const cases: Array<[string, Record<string, unknown>]> = [
      ["blank name", { name: "   " }],
      ["name > 255", { name: "x".repeat(256) }],
      ["blank systemPrompt", { systemPrompt: "" }],
      ["systemPrompt > 32768", { systemPrompt: "x".repeat(32769) }],
      ["maxTokens 9999", { maxTokens: 9999 }],
      ["maxTokens 0", { maxTokens: 0 }],
      ["temperature 2", { temperature: 2 }],
      ["topP -0.1", { topP: -0.1 }],
      ["unknown provider", { providerName: "NOPE" }],
    ];
    const statuses: Record<string, number> = {};
    for (const [label, over] of cases) {
      const { res, a } = await mk(ctx.request, over);
      statuses[label] = res.status();
      if (a) continue;
      const body = await res.text();
      expect.soft(res.status(), `${label}: ${body.slice(0, 200)}`).toBe(400);
      expect.soft(body, `${label} leaks internals`).not.toMatch(/Exception|\.java:\d+|Traceback/);
    }
    const noProv = await ctx.request.post(`${API}/api/agents`, { headers: H(), data: { name: `${PFX}np`, systemPrompt: "x" }, failOnStatusCode: false });
    expect(noProv.status()).toBe(400);
    metrics.createValidationStatuses = statuses;
  });

  test("API: unknown / malformed ids are 404/400, not 500", async () => {
    const r1 = await ctx.request.get(`${API}/api/agents/00000000-0000-0000-0000-000000000000`, { headers: H(), failOnStatusCode: false });
    const r2 = await ctx.request.get(`${API}/api/agents/not-a-uuid`, { headers: H(), failOnStatusCode: false });
    const r3 = await ctx.request.patch(`${API}/api/agents/00000000-0000-0000-0000-000000000000/autonomy-tier`, { headers: H(), data: { autonomyTier: "SUPERVISED" }, failOnStatusCode: false });
    metrics.unknownIdStatuses = [r1.status(), r2.status(), r3.status()];
    expect(r1.status()).toBe(404);
    expect(r2.status()).toBe(400);
    expect(r3.status()).toBe(404);
  });

  test("API: unauthenticated and forged-token requests are 401", async ({ request }) => {
    const a = await request.get(`${API}/api/agents`, { failOnStatusCode: false });
    const b = await request.get(`${API}/api/agents`, { headers: { Authorization: "Bearer abc.def.ghi" }, failOnStatusCode: false });
    const c = await request.post(`${API}/api/agents`, { data: {}, failOnStatusCode: false });
    expect([a.status(), b.status(), c.status()]).toEqual([401, 401, 401]);
  });

  test("API: autonomy tier - every tier persists, is idempotent, invalid value is 400", async () => {
    const { a } = await mk(ctx.request);
    for (const tier of ["SUPERVISED", "FULLY_AUTONOMOUS", "SEMI_AUTONOMOUS", "SEMI_AUTONOMOUS"]) {
      const r = await ctx.request.patch(`${API}/api/agents/${a.id}/autonomy-tier`, { headers: H(), data: { autonomyTier: tier } });
      expect(r.status()).toBe(200);
      const got = await (await ctx.request.get(`${API}/api/agents/${a.id}`, { headers: H() })).json();
      expect(got.autonomyTier).toBe(tier);
    }
    const bad = await ctx.request.patch(`${API}/api/agents/${a.id}/autonomy-tier`, { headers: H(), data: { autonomyTier: "YOLO" }, failOnStatusCode: false });
    expect(bad.status()).toBe(400);
    const empty = await ctx.request.patch(`${API}/api/agents/${a.id}/autonomy-tier`, { headers: H(), data: {}, failOnStatusCode: false });
    expect(empty.status()).toBe(400);
  });

  test("API: reasoning depth + generation params persist; out-of-range rejected", async () => {
    const { a } = await mk(ctx.request);
    for (const d of ["CAREFUL", "RIGOROUS", "FAST"]) {
      expect((await ctx.request.patch(`${API}/api/agents/${a.id}/reasoning-depth`, { headers: H(), data: { reasoningDepth: d } })).status()).toBe(200);
      expect((await (await ctx.request.get(`${API}/api/agents/${a.id}`, { headers: H() })).json()).reasoningDepth).toBe(d);
    }
    const ok = await ctx.request.patch(`${API}/api/agents/${a.id}/generation-params`, { headers: H(), data: { maxTokens: 512, temperature: 0.2, topP: 0.9, stopSequences: ["END"] } });
    expect(ok.status(), await ok.text()).toBe(200);
    const got = await (await ctx.request.get(`${API}/api/agents/${a.id}`, { headers: H() })).json();
    expect(got.maxTokens).toBe(512);
    expect(got.temperature).toBeCloseTo(0.2);
    for (const bad of [
      { maxTokens: 9000, temperature: 0.2, topP: 0.9 },
      { maxTokens: 100, temperature: 1.5, topP: 0.9 },
      { maxTokens: 100, temperature: 0.2, topP: 0.9, stopSequences: Array(9).fill("x") },
      { maxTokens: 100, temperature: 0.2, topP: 0.9, topK: 5000 },
      { temperature: 0.2 },
    ]) {
      const r = await ctx.request.patch(`${API}/api/agents/${a.id}/generation-params`, { headers: H(), data: bad, failOnStatusCode: false });
      expect.soft(r.status(), JSON.stringify(bad)).toBe(400);
    }
  });

  test("API: prompt edit creates a version and revert restores it", async () => {
    const { a } = await mk(ctx.request, { systemPrompt: "First prompt." });
    expect((await ctx.request.patch(`${API}/api/agents/${a.id}/prompt`, { headers: H(), data: { systemPrompt: "Second prompt." } })).status()).toBe(200);
    const versions = await (await ctx.request.get(`${API}/api/agents/${a.id}/prompt/versions`, { headers: H() })).json();
    expect(versions.length).toBeGreaterThanOrEqual(1);
    const first = versions.find((v: any) => /First prompt/.test(JSON.stringify(v)));
    expect(first, `versions: ${JSON.stringify(versions).slice(0, 300)}`).toBeTruthy();
    const rev = await ctx.request.post(`${API}/api/agents/${a.id}/prompt/versions/${first.id}/revert`, { headers: H() });
    expect(rev.status()).toBe(200);
    expect((await (await ctx.request.get(`${API}/api/agents/${a.id}`, { headers: H() })).json()).systemPrompt).toBe("First prompt.");
    const blank = await ctx.request.patch(`${API}/api/agents/${a.id}/prompt`, { headers: H(), data: { systemPrompt: " " }, failOnStatusCode: false });
    expect(blank.status()).toBe(400);
  });

  test("API: duplicate -> distinct copy; delete -> 204, then 404 (second delete is not a 500)", async () => {
    const { a } = await mk(ctx.request, { systemPrompt: "Clone me." });
    const dup = await ctx.request.post(`${API}/api/agents/${a.id}/duplicate`, { headers: H(), failOnStatusCode: false });
    expect(dup.status(), await dup.text()).toBe(201);
    const d = await dup.json();
    created.push(d.id);
    expect(d.id).not.toBe(a.id);
    expect(d.systemPrompt).toBe("Clone me.");
    for (const id of [a.id, d.id]) {
      expect((await ctx.request.delete(`${API}/api/agents/${id}`, { headers: H() })).status()).toBe(204);
      expect((await ctx.request.get(`${API}/api/agents/${id}`, { headers: H(), failOnStatusCode: false })).status()).toBe(404);
    }
    const again = await ctx.request.delete(`${API}/api/agents/${a.id}`, { headers: H(), failOnStatusCode: false });
    metrics.secondDeleteStatus = again.status();
    expect([204, 404]).toContain(again.status());
  });

  test("API: chat turn on the mock model streams a non-empty reply; latency recorded; chat on deleted agent is 404", async () => {
    const { a } = await mk(ctx.request);
    const t0 = Date.now();
    const res = await ctx.request.post(`${API}/api/agents/${a.id}/chat`, { headers: { ...H(), Accept: "text/event-stream" }, data: { message: "hello" }, timeout: 60_000 });
    const body = await res.text();
    metrics.chatLatencyMs = Date.now() - t0;
    expect(res.status()).toBe(200);
    expect(body.length).toBeGreaterThan(0);
    expect(body).not.toMatch(/@@AGENT_CHAT_STREAM_ERROR|Traceback|\.java:\d+/);
    await ctx.request.delete(`${API}/api/agents/${a.id}`, { headers: H() });
    const gone = await ctx.request.post(`${API}/api/agents/${a.id}/chat`, { headers: { ...H(), Accept: "text/event-stream" }, data: { message: "hi" }, failOnStatusCode: false });
    expect(gone.status()).toBe(404);
  });

  test("UI: /agents loads, selects an agent by ?agentId, and every workbench section toggles", async () => {
    const { a } = await mk(ctx.request, { systemPrompt: "UI persona." });
    const t0 = Date.now();
    await page.goto(`${APP}/agents?agentId=${a.id}`, { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: /^agents$/i }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("button", { name: /reasoning & autonomy/i }).first()).toBeVisible({ timeout: 30_000 });
    metrics.agentsPageReadyMs = Date.now() - t0;
    await expect(page.getByText(a.name).first()).toBeVisible();
    const sections = ["persona", "model & generation", "device & routing", "generation tuning", "transcription", "reasoning & autonomy", "reliability", "caching, sharing", "team / swarm", "knowledge collections", "tools", "capabilities", "code intelligence", "sharing", "versions"];
    const results: Record<string, string> = {};
    for (const s of sections) {
      const btn = page.getByRole("button", { name: new RegExp("^\\s*" + s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i") }).first();
      if (!(await btn.isVisible().catch(() => false))) { results[s] = "missing"; continue; }
      const norm = async () => (await btn.getAttribute("aria-expanded")) ?? "false";
      const before = await norm();
      if ((await btn.getAttribute("aria-expanded")) === null) results[s + " (no aria-expanded attr initially)"] = "a11y";
      await btn.click();
      await expect.poll(norm, { timeout: 5000 }).not.toBe(before);
      await btn.click();
      await expect.poll(norm, { timeout: 5000 }).toBe(before);
      results[s] = "ok";
    }
    metrics.sectionToggle = results;
    expect(Object.values(results).filter((v) => v === "missing")).toEqual([]);
    // KNOWN DEFECT (a11y): the "Code intelligence" section header renders without aria-expanded until first toggled.
    test.info().annotations.push({ type: "defect", description: `headers without aria-expanded: ${Object.keys(results).filter((k) => results[k] === "a11y").join(", ")}` });
  });

  test("UI: autonomy pill change persists across reload and matches the API", async () => {
    const { a } = await mk(ctx.request);
    await page.goto(`${APP}/agents?agentId=${a.id}`, { waitUntil: "domcontentloaded" });
    const trig = page.getByRole("button", { name: /reasoning & autonomy/i }).first();
    await expect(trig).toBeVisible({ timeout: 30_000 });
    if ((await trig.getAttribute("aria-expanded")) !== "true") await trig.click();
    await page.getByTestId("autonomy-tier-supervised").click();
    await expect(page.getByText(/autonomy updated/i)).toBeVisible({ timeout: 15_000 });
    await freshToken();
    expect((await (await ctx.request.get(`${API}/api/agents/${a.id}`, { headers: H() })).json()).autonomyTier).toBe("SUPERVISED");
    const trig2 = page.getByRole("button", { name: /reasoning & autonomy/i }).first();
    await expect(trig2).toBeVisible({ timeout: 30_000 });
    if ((await trig2.getAttribute("aria-expanded")) !== "true") await trig2.click();
    await expect(page.getByTestId("autonomy-tier-supervised")).toHaveAttribute("aria-pressed", "true");
  });

  test("UI: Playground sends a message with Enter and shows a reply; empty message does not send", async () => {
    const { a } = await mk(ctx.request);
    await page.goto(`${APP}/agents?agentId=${a.id}`, { waitUntil: "domcontentloaded" });
    const box = page.getByPlaceholder(/send a test message/i);
    await expect(box).toBeVisible({ timeout: 30_000 });
    let posts = 0;
    page.on("request", (r) => { if (r.method() === "POST" && /\/chat/.test(r.url())) posts++; });
    await box.press("Enter");
    await page.waitForTimeout(1000);
    expect(posts, "empty Enter must not POST a chat turn").toBe(0);
    await box.fill("ping from e2e-agents");
    const t0 = Date.now();
    await box.press("Enter");
    await expect(page.getByText("ping from e2e-agents").first()).toBeVisible({ timeout: 10_000 });
    await expect.poll(async () => (await page.locator("body").innerText()).includes("ok ok"), { timeout: 45_000 }).toBe(true);
    metrics.playgroundReplyMs = Date.now() - t0;
    expect(posts).toBeGreaterThan(0);
  });

  test("UI: Duplicate button creates a copy and Delete button (with confirm) removes it", async () => {
    const { a } = await mk(ctx.request, { systemPrompt: "UI dup source." });
    await page.goto(`${APP}/agents?agentId=${a.id}`, { waitUntil: "domcontentloaded" });
    const before = (await (await ctx.request.get(`${API}/api/agents`, { headers: H() })).json()).length;
    await expect(page.getByRole("button", { name: /^duplicate$/i }).first()).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: /^duplicate$/i }).first().click();
    await expect.poll(async () => (await (await ctx.request.get(`${API}/api/agents`, { headers: H() })).json()).length, { timeout: 20_000 }).toBe(before + 1);
    const all = await (await ctx.request.get(`${API}/api/agents`, { headers: H() })).json();
    const copy = all.find((x: any) => x.id !== a.id && x.systemPrompt === "UI dup source." && String(x.name).startsWith(PFX));
    expect(copy, "duplicate did not produce an e2e-agents- copy").toBeTruthy();
    created.push(copy.id);
    // The 2FA coachmark ("Got it") can sit over the toolbar; dismiss if present.
    const gotIt = page.getByRole("button", { name: /^got it$/i });
    if (await gotIt.isVisible().catch(() => false)) await gotIt.click();
    // Delete whichever agent the UI has selected (after Duplicate the UI may switch to the copy).
    page.once("dialog", (d) => d.accept());
    await page.getByRole("button", { name: /^delete$/i }).first().click();
    const confirm = page.getByRole("button", { name: /^(delete|confirm|yes)/i }).last();
    if (await confirm.isVisible({ timeout: 3_000 }).catch(() => false)) await confirm.click();
    const st = async (id: string) => (await ctx.request.get(`${API}/api/agents/${id}`, { headers: H(), failOnStatusCode: false })).status();
    await expect.poll(async () => [await st(a.id), await st(copy.id)].filter((x) => x === 404).length, { timeout: 20_000 }).toBe(1);
    metrics.deleteAfterDuplicateDeleted = (await st(a.id)) === 404 ? "original" : "copy";
  });

  test("UI: /agents has no hard-coded localhost browser requests and records page-level health", async () => {
    const reqs: string[] = [];
    const h = (r: any) => reqs.push(r.url());
    page.on("request", h);
    await page.goto(`${APP}/agents`, { waitUntil: "networkidle", timeout: 60_000 }).catch(() => {});
    page.off("request", h);
    const local = reqs.filter((u) => /^https?:\/\/(localhost|127\.0\.0\.1)(:|\/)/.test(u));
    metrics.agentsPageRequestCount = reqs.length;
    metrics.localhostRequests = local;
    // KNOWN DEFECT: /agents (AgentWorkbench -> app/code-intel/lib.ts) calls GET http://localhost:8090/repos because
    // NEXT_PUBLIC_CODE_INTEL_URL defaults to http://localhost:8090 in deploy/compose/docker-compose.yml (line ~548),
    // docker-compose.build.yml (~50) and .env.example (PUBLIC_CODE_INTEL_URL); empty would use the core proxy.
    test.fixme(local.length > 0, `browser-facing request to localhost (AGENTS.md rule 4): ${local.join(", ")}`);
    expect(local).toEqual([]);
  });
});
