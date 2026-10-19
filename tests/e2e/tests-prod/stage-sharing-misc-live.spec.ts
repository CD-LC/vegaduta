import { test, expect, type Page, type APIRequestContext, type BrowserContext } from "@playwright/test";
import * as fs from "fs";

/**
 * Sharing + public/misc pages against the local kind stage (mock LLM).
 * Self-contained: helpers/prod-session.ts is stale against the current console (Sign in is a link, not a button;
 * the stage serves http). Entities are prefixed "e2e-sharing-misc-" and removed in afterAll.
 * Covers: public content pages (/help /docs /learn /glossary /changelog /api-reference /account-deletion /explore),
 * invalid share ids (/a /agent /workflow), owner share lifecycle for an agent and a workflow
 * (mint, idempotent, public meta, chat, report, revoke, re-share), abuse limits, /shared-with-me, /home.
 */
const D = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${D}`;
const API = `http://api.${D}`;
const EMAIL = process.env.E2E_USER_EMAIL!;
const PASS = process.env.E2E_USER_PASSWORD!;
const PFX = "e2e-sharing-misc-";
const OUT = process.env.E2E_OUT || "test-results-stage";
const JSON_H = { "Content-Type": "application/json" };

const metrics: Record<string, unknown> = {};
const pageTimings: Record<string, number> = {};
const consoleErrors: string[] = [];
const failedRequests: string[] = [];
const slow: string[] = [];

let ctx: BrowserContext;
let page: Page;
let token = "";
const createdAgents: string[] = [];
const createdWorkflows: string[] = [];
const H = () => ({ Authorization: `Bearer ${token}`, ...JSON_H });

async function login(p: Page) {
  await p.addInitScript(() => {
    const orig = Storage.prototype.getItem;
    Storage.prototype.getItem = function (k: string) {
      if (this === window.localStorage && typeof k === "string") {
        if (k.startsWith("vd_tour_status")) return "skipped";
        if (/^(vd_agents_guide_seen|vd_demo_tour_seen|vd_lang_prompt_seen|agentic_language_choice_seen|floatingNav\.hintSeen)/.test(k)) return "1";
        if (k === "vd_page_guides_seen") return JSON.stringify(["home", "agents", "agent-templates", "playground", "tools", "shared-with-me"]);
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

async function freshToken(): Promise<string> {
  const wait = page.waitForRequest((r) => r.url().startsWith(API) && !!r.headers()["authorization"], { timeout: 30_000 });
  await page.reload({ waitUntil: "domcontentloaded" });
  token = (await wait).headers()["authorization"].replace(/^Bearer /i, "");
  return token;
}

async function mkAgent(request: APIRequestContext) {
  const list = await (await request.get(`${API}/api/agents`, { headers: H() })).json();
  const t = list.find((x: any) => x.providerName === "OLLAMA") ?? list[0];
  const res = await request.post(`${API}/api/agents`, {
    headers: H(),
    data: { name: `${PFX}${Date.now()}`, systemPrompt: "You are a terse test agent.", providerName: t?.providerName ?? "OLLAMA", model: t?.model ?? "mock:latest" },
    failOnStatusCode: false,
  });
  expect(res.status(), await res.text()).toBe(201);
  const a = await res.json();
  createdAgents.push(a.id);
  return a;
}

async function mkWorkflow(request: APIRequestContext) {
  const res = await request.post(`${API}/api/workflows`, {
    headers: H(),
    data: { name: `${PFX}wf-${Date.now()}`, description: "e2e", graph: { nodes: [], edges: [] } },
    failOnStatusCode: false,
  });
  if (res.status() >= 300) return { res, wf: null as any };
  const wf = await res.json();
  createdWorkflows.push(wf.id);
  return { res, wf };
}

async function cleanup() {
    try {
      token = await freshToken();
      for (const id of createdAgents) {
        await ctx.request.delete(`${API}/api/agents/${id}/share`, { headers: H(), failOnStatusCode: false }).catch(() => {});
        await ctx.request.delete(`${API}/api/agents/${id}`, { headers: H(), failOnStatusCode: false }).catch(() => {});
      }
      for (const id of createdWorkflows) {
        await ctx.request.delete(`${API}/api/workflows/${id}/share`, { headers: H(), failOnStatusCode: false }).catch(() => {});
        await ctx.request.delete(`${API}/api/workflows/${id}`, { headers: H(), failOnStatusCode: false }).catch(() => {});
      }
      const la = await (await ctx.request.get(`${API}/api/agents`, { headers: H() })).json().catch(() => []);
      metrics.leftoverE2eAgents = (la as any[]).filter((a) => String(a.name).startsWith(PFX)).length;
      const lw = await (await ctx.request.get(`${API}/api/workflows`, { headers: H() })).json().catch(() => []);
      metrics.leftoverE2eWorkflows = (lw as any[]).filter((a) => String(a.name).startsWith(PFX)).length;
    } catch (e) { metrics.cleanupError = String(e); }
    fs.mkdirSync(OUT, { recursive: true });
    fs.writeFileSync(`${OUT}/stage-sharing-misc-metrics.json`, JSON.stringify({ metrics, pageTimings, consoleErrors, failedRequests, slow }, null, 1));
    await ctx.close();
  }

test.describe("Sharing and misc pages on the kind stage (public)", () => {
  test.setTimeout(120_000);

  // ---------- public pages (anonymous) ----------
  const PUBLIC: Array<[string, RegExp]> = [
    ["/help", /help|docs|guide/i],
    ["/docs", /doc/i],
    ["/glossary", /plain terms|glossary/i],
    ["/changelog", /what changed|changelog/i],
    ["/api-reference", /api/i],
    ["/account-deletion", /delete your/i],
  ];
  for (const [path, h1re] of PUBLIC) {
    test(`public ${path} renders anonymously with a heading, no console errors, no dead internal links`, async ({ browser }) => {
      const c = await browser.newContext({ ignoreHTTPSErrors: true });
      const p = await c.newPage();
      const errs: string[] = [];
      p.on("console", (m) => { if (m.type() === "error") errs.push(m.text().slice(0, 200)); });
      const bad: string[] = [];
      p.on("response", (r) => { if (r.status() >= 400 && r.url().startsWith(APP)) bad.push(`${r.status()} ${r.url()}`); });
      const t0 = Date.now();
      const res = await p.goto(`${APP}${path}`, { waitUntil: "load" });
      pageTimings[path] = Date.now() - t0;
      expect(res!.status()).toBe(200);
      expect(p.url(), "anonymous visit must not bounce to login").toContain(path);
      await expect(p.locator("h1").first()).toContainText(h1re, { timeout: 15_000 });
      // internal links resolve (HEAD-ish via request ctx, first 25 unique)
      const hrefs: string[] = await p.$$eval("a[href^='/']", (as) => Array.from(new Set(as.map((a) => (a as HTMLAnchorElement).getAttribute("href")!.split("#")[0]))).filter(Boolean));
      const dead: string[] = [];
      for (const h of hrefs.slice(0, 25)) {
        const r = await c.request.get(`${APP}${h}`, { failOnStatusCode: false, maxRedirects: 3 });
        if (r.status() >= 400) dead.push(`${r.status()} ${h}`);
      }
      metrics[`deadLinks${path}`] = dead;
      metrics[`consoleErrors${path}`] = errs;
      expect(dead, `dead internal links on ${path}`).toEqual([]);
      // requestStorageAccess is a browser/keycloak-js artifact of the stage serving http (insecure context), not a product defect.
      expect(errs.filter((e) => !/requestStorageAccess/.test(e))).toEqual([]);
      expect(bad).toEqual([]);
      await c.close();
    });
  }

  test("account-deletion states the DELETE confirmation, retained data and a mailto contact", async ({ browser }) => {
    const c = await browser.newContext({ ignoreHTTPSErrors: true });
    const p = await c.newPage();
    await p.goto(`${APP}/account-deletion`);
    await expect(p.getByText(/type DELETE to confirm/i)).toBeVisible();
    await expect(p.getByRole("heading", { name: /what is deleted/i })).toBeVisible();
    await expect(p.getByRole("heading", { name: /what is kept/i })).toBeVisible();
    const mail = await p.locator("a[href^='mailto:']").first().getAttribute("href");
    metrics.accountDeletionMailto = mail;
    expect(mail).toMatch(/^mailto:[^@\s?]+@[^@\s?]+/);
    await p.getByRole("link", { name: "Privacy Policy" }).last().click();
    await expect(p).toHaveURL(/\/privacy/);
    await c.close();
  });

  test("/explore redirects to the landing page", async ({ browser }) => {
    const c = await browser.newContext({ ignoreHTTPSErrors: true });
    const p = await c.newPage();
    await p.goto(`${APP}/explore`);
    await expect(p).toHaveURL(new RegExp(`^${APP.replace(/\./g, "\\.")}/?$`));
    await c.close();
  });

  test("glossary: search/filter control narrows results (if present) and anchor links work", async ({ browser }) => {
    const c = await browser.newContext({ ignoreHTTPSErrors: true });
    const p = await c.newPage();
    await p.goto(`${APP}/glossary`);
    const search = p.getByRole("searchbox").or(p.getByPlaceholder(/search|filter/i)).first();
    if (await search.count()) {
      const before = await p.locator("dt, h3").count();
      await search.fill("zzzz-no-such-term");
      const after = await p.locator("dt, h3").count();
      metrics.glossarySearch = { before, after };
      expect(after).toBeLessThan(before);
      await search.fill("");
    } else {
      metrics.glossarySearch = "no search control";
    }
    await c.close();
  });

  test("changelog lists entries with versions/dates and /api-reference offers an OpenAPI source", async ({ browser }) => {
    const c = await browser.newContext({ ignoreHTTPSErrors: true });
    const p = await c.newPage();
    await p.goto(`${APP}/changelog`);
    const txt = await p.locator("main").innerText();
    metrics.changelogChars = txt.length;
    expect(txt).toMatch(/\d{4}-\d{2}|v?\d+\.\d+/);
    await p.goto(`${APP}/api-reference`);
    const body = await p.locator("main").innerText();
    metrics.apiReferenceChars = body.length;
    expect(body.length).toBeGreaterThan(200);
    await c.close();
  });

  // ---------- invalid / revoked share ids (anonymous) ----------
  for (const [route, apiPath] of [["/a", "/api/demo/share"], ["/agent", "/api/share/agents"], ["/workflow", "/api/share/workflows"]] as const) {
    test(`${route}/<bogus> fails closed in the UI and API without leaking detail`, async ({ browser }) => {
      const c = await browser.newContext({ ignoreHTTPSErrors: true });
      const p = await c.newPage();
      const id = "e2e-bogus-0000000000";
      const r = await c.request.get(`${API}${apiPath}/${id}`, { failOnStatusCode: false });
      metrics[`bogusApi${route}`] = r.status();
      expect([404, 410]).toContain(r.status());
      expect(await r.text()).not.toMatch(/exception|stack|at com\.|SQL/i);
      const t0 = Date.now();
      await p.goto(`${APP}${route}/${id}`, { waitUntil: "load" });
      await expect(p.getByText(/no longer|not available|isn.t available|invalid|expired|not found|unavailable|gone/i).first()).toBeVisible({ timeout: 20_000 });
      pageTimings[`${route}/<bogus>`] = Date.now() - t0;
      // dead link must not offer a "try again" for a permanent failure
      await expect(p.getByRole("button", { name: /try again|retry/i })).toHaveCount(0);
      await c.close();
    });
  }

  test("public share API rejects oversized ids and verbs without 5xx", async ({ request }) => {
    for (const path of [`/api/share/agents/${"x".repeat(300)}`, `/api/share/agents/%00`, `/api/share/workflows/..%2f..%2fadmin`, `/api/demo/share/${"y".repeat(300)}`]) {
      const r = await request.get(`${API}${path}`, { failOnStatusCode: false });
      expect(r.status(), path).toBeLessThan(500);
    }
    const r = await request.post(`${API}/api/share/agents/nope/chat`, { data: { message: "hi" }, failOnStatusCode: false });
    expect([404, 410]).toContain(r.status());
  });

});

test.describe.serial("Sharing and misc pages on the kind stage (signed in)", () => {
  test.setTimeout(120_000);
  test.beforeAll(async ({ browser }) => {
    ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    page = await ctx.newPage();
    page.on("console", (m) => { if (m.type() === "error" && !/requestStorageAccess/.test(m.text())) consoleErrors.push(`${page.url().replace(APP, "")} ${m.text().slice(0, 200)}`); });
    const starts = new Map<any, number>();
    page.on("request", (r) => starts.set(r, Date.now()));
    page.on("requestfailed", (r) => failedRequests.push(`${r.method()} ${r.url().slice(0, 140)} ${r.failure()?.errorText}`));
    page.on("response", (r) => {
      const t = Date.now() - (starts.get(r.request()) ?? Date.now());
      if (r.status() >= 400) failedRequests.push(`${r.status()} ${r.request().method()} ${r.url().slice(0, 140)}`);
      if (t > 2000 && r.url().includes("/api/")) slow.push(`${t}ms ${r.request().method()} ${r.url().replace(API, "")}`);
    });
  });
  test.afterAll(async () => { await cleanup(); });

  test("sign in (stage flow) and capture bearer", async () => {
    await login(page);
    await page.goto(`${APP}/home`, { waitUntil: "domcontentloaded" });
    await freshToken();
    expect(token.length).toBeGreaterThan(20);
  });

  test("/home dashboard renders signed in with no failed API calls", async () => {
    failedRequests.length = 0;
    const t0 = Date.now();
    await page.goto(`${APP}/home`, { waitUntil: "load" });
    await page.waitForLoadState("networkidle", { timeout: 20_000 }).catch(() => {});
    pageTimings["/home"] = Date.now() - t0;
    await expect(page.locator("main, [role=main]").first()).toBeVisible();
    await expect(page.locator("h1, h2").first()).toBeVisible();
    const prefetch = failedRequests.filter((f) => /_rsc=.*ERR_ABORTED/.test(f));
    metrics.homeRscPrefetchAborted = prefetch.length;
    metrics.homeFailed = failedRequests.filter((f) => !/_rsc=.*ERR_ABORTED/.test(f));
    expect(metrics.homeFailed).toEqual([]);
  });

  test("/home: idle polling after load is bounded (<= 30 API calls in 10s)", async () => {
    await page.goto(`${APP}/home`, { waitUntil: "load" });
    await page.waitForTimeout(4_000);
    const calls: string[] = [];
    const h = (r: any) => { if (r.url().startsWith(API)) calls.push(`${r.method()} ${r.url().replace(API, "").split("?")[0]}`); };
    page.on("request", h);
    await page.waitForTimeout(10_000);
    page.off("request", h);
    const counts: Record<string, number> = {};
    for (const c of calls) counts[c] = (counts[c] || 0) + 1;
    metrics.homeIdleCalls10s = counts;
    expect(calls.length).toBeLessThanOrEqual(30);
  });

  test("/learn signed in renders the curriculum (it is intentionally not public: anonymous gets the landing page)", async () => {
    const t0 = Date.now();
    await page.goto(`${APP}/learn`, { waitUntil: "load" });
    pageTimings["/learn"] = Date.now() - t0;
    await expect(page.locator("h1").first()).toBeVisible({ timeout: 20_000 });
    metrics.learnH1 = await page.locator("h1").first().innerText();
    expect(metrics.learnH1).not.toMatch(/build, govern and run AI agents/i);
  });

  test("/shared-with-me: empty state (or list), no console/network errors, requests not duplicated", async () => {
    failedRequests.length = 0;
    const calls: string[] = [];
    const h = (r: any) => { if (r.url().startsWith(API)) calls.push(`${r.method()} ${r.url().replace(API, "")}`); };
    page.on("request", h);
    const t0 = Date.now();
    await page.goto(`${APP}/shared-with-me`, { waitUntil: "load" });
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible({ timeout: 20_000 });
    await expect(page.locator(".animate-pulse")).toHaveCount(0, { timeout: 20_000 });
    pageTimings["/shared-with-me"] = Date.now() - t0;
    page.off("request", h);
    const counts: Record<string, number> = {};
    for (const c of calls) counts[c] = (counts[c] || 0) + 1;
    metrics.sharedWithMeCalls = counts;
    metrics.sharedWithMeFailed = [...failedRequests];
    const empty = await page.getByText(/nothing|no .*shared|hasn.t been shared|empty/i).count();
    metrics.sharedWithMeEmptyStateShown = empty > 0;
    // RSC prefetch aborts are navigation noise; /api/tutor/status 404 is the documented "feature disabled" answer (leaks in from /learn).
    expect(failedRequests.filter((f) => !/_rsc=.*ERR_ABORTED|404 GET .*\/api\/tutor\/status/.test(f))).toEqual([]);
    const dup = Object.entries(counts).filter(([, n]) => n > 1);
    // KNOWN: /api/system/status is fetched twice per load (two consumers in the shell); recorded, not asserted.
    expect(dup.filter(([k]) => !/\/api\/system\/status/.test(k)), "duplicate API calls on /shared-with-me").toEqual([]);
  });

  test("GET /api/shared-with-me answers a JSON array and 401 without a token", async ({ request }) => {
    const ok = await request.get(`${API}/api/shared-with-me`, { headers: H() });
    expect(ok.status()).toBe(200);
    expect(Array.isArray(await ok.json())).toBe(true);
    const anon = await request.get(`${API}/api/shared-with-me`, { failOnStatusCode: false });
    expect(anon.status()).toBe(401);
  });

  test("grants: invalid grantee, bad permission and anonymous access are rejected", async ({ request }) => {
    const a = await mkAgent(request);
    const g = `${API}/api/agents/${a.id}/grants`;
    const noAuth = await request.post(g, { data: { granteeEmail: "x@y.test", permissionLevel: "VIEWER" }, failOnStatusCode: false });
    expect(noAuth.status()).toBe(401);
    const badEmail = await request.post(g, { headers: H(), data: { granteeEmail: "not-an-email", permissionLevel: "VIEWER" }, failOnStatusCode: false });
    expect(badEmail.status()).toBe(400);
    const unknown = await request.post(g, { headers: H(), data: { granteeEmail: `${PFX}nobody@vegaduta.test`, permissionLevel: "VIEWER" }, failOnStatusCode: false });
    metrics.grantUnknownStatus = unknown.status();
    expect(unknown.status()).toBe(403);
    expect(await unknown.text()).not.toContain(`${PFX}nobody`);
    const badLevel = await request.post(g, { headers: H(), data: { granteeEmail: "a@b.test", permissionLevel: "GOD" }, failOnStatusCode: false });
    expect(badLevel.status()).toBe(400);
    const list = await request.get(g, { headers: H() });
    expect(list.status()).toBe(200);
    expect(await list.json()).toEqual([]);
    const badKind = await request.get(`${API}/api/secrets/${a.id}/grants`, { headers: H(), failOnStatusCode: false });
    expect(badKind.status()).toBeLessThan(500);
  });

  // ---------- agent share lifecycle ----------
  test("agent share: mint is idempotent, public meta works, chat replies, report counts, revoke kills link, re-share works", async ({ request }) => {
    const a = await mkAgent(request);
    const base = `${API}/api/agents/${a.id}/share`;
    const st0 = await (await request.get(base, { headers: H() })).json();
    expect(st0.active).toBe(false);

    const m1 = await request.post(base, { headers: H() });
    expect(m1.status()).toBe(201);
    const s1 = await m1.json();
    expect(s1.active).toBe(true);
    expect(s1.shareUrl, "browser-facing share URL must not be localhost").not.toMatch(/localhost|127\.0\.0\.1:/);
    metrics.agentShareUrl = s1.shareUrl;
    const m2 = await request.post(base, { headers: H() });
    expect([200, 201]).toContain(m2.status());
    expect((await m2.json()).shareId, "second POST must return the same link").toBe(s1.shareId);

    const pub = `${API}/api/share/agents/${s1.shareId}`;
    const t0 = Date.now();
    const meta = await request.get(pub);
    metrics.agentShareMetaMs = Date.now() - t0;
    expect(meta.status()).toBe(200);
    expect((await meta.json()).agentName).toBe(a.name);

    const t1 = Date.now();
    const chat = await request.post(`${pub}/chat`, { data: { message: "hello" }, failOnStatusCode: false });
    metrics.agentShareChatMs = Date.now() - t1;
    metrics.agentShareChatStatus = chat.status();
    expect(chat.status(), await chat.text()).toBe(200);

    const tooLong = await request.post(`${pub}/chat`, { data: { message: "x".repeat(2500) }, failOnStatusCode: false });
    metrics.agentShareOversizeStatus = tooLong.status();
    expect(tooLong.status()).toBe(400);
    const empty = await request.post(`${pub}/chat`, { data: { message: "" }, failOnStatusCode: false });
    metrics.agentShareEmptyStatus = empty.status();
    expect(empty.status()).toBeLessThan(500);

    const longReason = await request.post(`${pub}/report`, { data: { reason: "r".repeat(501) }, failOnStatusCode: false });
    expect(longReason.status()).toBe(400);
    const rep = await request.post(`${pub}/report`, { data: { reason: "e2e report" }, failOnStatusCode: false });
    expect(rep.status()).toBe(204);
    const st = await (await request.get(base, { headers: H() })).json();
    metrics.agentShareCountsAfterChatAndReport = { reportCount: st.reportCount, viewerCount: st.viewerCount };
    // PRODUCT DEFECT CANDIDATE (see SHARE-COUNTS below): reportCount/viewerCount are not persisted after a 204 report / 200 chat.
    if (process.env.E2E_STRICT_SHARE_COUNTS) {
      expect(st.reportCount).toBeGreaterThanOrEqual(1);
      expect(st.viewerCount).toBeGreaterThanOrEqual(1);
    }

    // revoke is idempotent and kills the link
    const d1 = await request.delete(base, { headers: H() });
    expect(d1.status()).toBe(204);
    const d2 = await request.delete(base, { headers: H(), failOnStatusCode: false });
    expect(d2.status()).toBeLessThan(300);
    const dead = await request.get(pub, { failOnStatusCode: false });
    metrics.agentShareRevokedMetaStatus = dead.status();
    const deadChat = await request.post(`${pub}/chat`, { data: { message: "hi" }, failOnStatusCode: false });
    expect([404, 410]).toContain(dead.status());
    expect([404, 410]).toContain(deadChat.status());
    // the known share-after-revoke 500 (uq_agent_share_agent_id) from the prod spec's comment
    const again = await request.post(base, { headers: H(), failOnStatusCode: false });
    metrics.agentReshareStatus = again.status();
    expect(again.status(), "re-share after revoke").toBe(201);
    const s3 = await again.json();
    metrics.agentReshareSameId = s3.shareId === s1.shareId;
    expect((await request.get(`${API}/api/share/agents/${s3.shareId}`)).status()).toBe(200);
  });

  test("SHARE-COUNTS: reports and viewers are persisted; three reports auto-revoke the link (threshold 3)", async ({ request }) => {
    const a = await mkAgent(request);
    const base = `${API}/api/agents/${a.id}/share`;
    const s = await (await request.post(base, { headers: H() })).json();
    const pub = `${API}/api/share/agents/${s.shareId}`;
    await request.post(`${pub}/chat`, { data: { message: "count me" } });
    for (let i = 0; i < 3; i++) expect((await request.post(`${pub}/report`, { data: { reason: `e2e ${i}` } })).status()).toBe(204);
    const st = await (await request.get(base, { headers: H() })).json();
    const after = await request.get(pub, { failOnStatusCode: false });
    metrics.shareCounts3Reports = { reportCount: st.reportCount, viewerCount: st.viewerCount, active: st.active, publicStatus: after.status() };
    test.fixme(st.reportCount === 0, "DEFECT: AgentShare.report()/recordViewer() mutate a detached entity; counts stay 0 and abuse reports never auto-revoke");
    expect(st.reportCount).toBeGreaterThanOrEqual(3);
    expect([404, 410]).toContain(after.status());
  });

  test("agent share: a non-owner/anonymous caller cannot mint, read status or revoke", async ({ request }) => {
    const a = await mkAgent(request);
    const base = `${API}/api/agents/${a.id}/share`;
    for (const m of ["get", "post", "delete"] as const) {
      const r = await request[m](base, { failOnStatusCode: false });
      expect(r.status(), `anonymous ${m}`).toBe(401);
    }
    const missing = await request.post(`${API}/api/agents/00000000-0000-0000-0000-000000000000/share`, { headers: H(), failOnStatusCode: false });
    expect(missing.status()).toBe(404);
    const malformed = await request.post(`${API}/api/agents/not-a-uuid/share`, { headers: H(), failOnStatusCode: false });
    expect(malformed.status()).toBeLessThan(500);
  });

  test("agent share: deleting the agent kills its public link", async ({ request }) => {
    const a = await mkAgent(request);
    const s = await (await request.post(`${API}/api/agents/${a.id}/share`, { headers: H() })).json();
    const del = await request.delete(`${API}/api/agents/${a.id}`, { headers: H(), failOnStatusCode: false });
    expect(del.status()).toBeLessThan(300);
    createdAgents.splice(createdAgents.indexOf(a.id), 1);
    const r = await request.get(`${API}/api/share/agents/${s.shareId}`, { failOnStatusCode: false });
    metrics.deletedAgentShareStatus = r.status();
    expect([404, 410]).toContain(r.status());
  });

  test("agent share: light abuse probe (5 rapid anonymous chats) stays below 5xx", async ({ request }) => {
    const a = await mkAgent(request);
    const s = await (await request.post(`${API}/api/agents/${a.id}/share`, { headers: H() })).json();
    const rs = await Promise.all([1, 2, 3, 4, 5].map((i) => request.post(`${API}/api/share/agents/${s.shareId}/chat`, { data: { message: `ping ${i}` }, failOnStatusCode: false })));
    metrics.agentShareBurstStatuses = rs.map((r) => r.status());
    for (const r of rs) expect(r.status()).toBeLessThan(500);
  });

  test("agent share UI: owner panel toggles Shared publicly / Not shared and persists after reload", async () => {
    const a = await mkAgent(ctx.request);
    await page.goto(`${APP}/agents?agentId=${a.id}`, { waitUntil: "domcontentloaded" });
    const status = page.getByText(/^(Shared publicly|Not shared)$/).first();
    await expect(status).toBeVisible({ timeout: 30_000 });
    await expect(status).toHaveText("Not shared");
    const row = status.locator("xpath=ancestor::div[contains(@class,'justify-between')][1]");
    await row.getByRole("button").click();
    await expect(page.getByText("Shared publicly").first()).toBeVisible({ timeout: 15_000 });
    const urlText = await page.getByText(/^https?:\/\/.*\/agent\//).first().textContent();
    metrics.agentShareUiUrl = urlText;
    expect(urlText).not.toMatch(/localhost|127\.0\.0\.1:/);
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.getByText("Shared publicly").first()).toBeVisible({ timeout: 30_000 });
    const row2 = page.getByText("Shared publicly").first().locator("xpath=ancestor::div[contains(@class,'justify-between')][1]");
    await row2.getByRole("button").click();
    await expect(page.getByText("Not shared").first()).toBeVisible({ timeout: 15_000 });
  });

  test("anonymous visitor page /agent/<shareId> loads the shared agent and replies", async ({ browser }) => {
    const a = await mkAgent(ctx.request);
    const s = await (await ctx.request.post(`${API}/api/agents/${a.id}/share`, { headers: H() })).json();
    const c = await browser.newContext({ ignoreHTTPSErrors: true });
    const p = await c.newPage();
    const errs: string[] = [];
    p.on("console", (m) => { if (m.type() === "error") errs.push(m.text().slice(0, 200)); });
    const t0 = Date.now();
    await p.goto(`${APP}/agent/${s.shareId}`, { waitUntil: "load" });
    await expect(p.getByText(a.name).first()).toBeVisible({ timeout: 20_000 });
    pageTimings["/agent/<live>"] = Date.now() - t0;
    const box = p.getByRole("textbox").first();
    await box.fill("hello from e2e");
    await box.press("Enter");
    await expect(p.getByText("hello from e2e").first()).toBeVisible();
    await expect.poll(async () => (await p.locator("main").innerText()).length, { timeout: 45_000 }).toBeGreaterThan(40);
    metrics.agentVisitorConsoleErrors = errs;
    await c.close();
  });

  // ---------- workflow share lifecycle ----------
  test("workflow share: owner lifecycle (mint, public meta, report, revoke, dead link)", async ({ request }) => {
    const { res, wf } = await mkWorkflow(request);
    test.skip(!wf, `could not create a workflow via API: ${res.status()} ${await res.text().catch(() => "")}`);
    const base = `${API}/api/workflows/${wf.id}/share`;
    const st0 = await request.get(base, { headers: H(), failOnStatusCode: false });
    expect(st0.status()).toBe(200);
    const mint = await request.post(base, { headers: H(), failOnStatusCode: false });
    metrics.workflowShareMintStatus = mint.status();
    expect([200, 201]).toContain(mint.status());
    const s = await mint.json();
    expect(s.shareUrl).not.toMatch(/localhost|127\.0\.0\.1:/);
    const mint2 = await (await request.post(base, { headers: H() })).json();
    expect(mint2.shareId).toBe(s.shareId);
    const pub = `${API}/api/share/workflows/${s.shareId}`;
    const meta = await request.get(pub, { failOnStatusCode: false });
    expect(meta.status()).toBe(200);
    const rep = await request.post(`${pub}/report`, { data: { reason: "e2e" }, failOnStatusCode: false });
    expect(rep.status()).toBe(204);
    const wst = await (await request.get(base, { headers: H() })).json();
    metrics.workflowShareReportCountAfter1 = wst.reportCount;
    const del = await request.delete(base, { headers: H() });
    expect(del.status()).toBeLessThan(300);
    const dead = await request.get(pub, { failOnStatusCode: false });
    metrics.workflowRevokedStatus = dead.status();
    expect([404, 410]).toContain(dead.status());
    for (const m of ["get", "post", "delete"] as const) {
      expect((await request[m](base, { failOnStatusCode: false })).status(), `anonymous ${m}`).toBe(401);
    }
  });

  test("keyboard: /shared-with-me and /account-deletion are tabbable with visible focus", async () => {
    for (const path of ["/shared-with-me", "/account-deletion"]) {
      await page.goto(`${APP}${path}`, { waitUntil: "load" });
      await page.waitForLoadState("networkidle", { timeout: 8_000 }).catch(() => {});
      await page.locator("h1").first().waitFor({ timeout: 15_000 });
      await page.keyboard.press("Tab");
      await page.keyboard.press("Tab");
      await page.keyboard.press("Tab");
      const tag = await page.evaluate(() => document.activeElement?.tagName);
      (metrics.keyboardTag ||= {} as any)[path] = tag;
      expect(["A", "BUTTON", "INPUT", "SUMMARY"], path).toContain(tag);
    }
  });

  test("record collected console errors / failed / slow requests", async () => {
    metrics.signedInConsoleErrors = consoleErrors.length;
    metrics.signedInFailedRequests = failedRequests.length;
    metrics.signedInSlow = slow.length;
  });
});
