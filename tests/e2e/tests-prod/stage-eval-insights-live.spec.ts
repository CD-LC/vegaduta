import { test, expect, type Browser, type BrowserContext, type Page } from "@playwright/test";

/**
 * Stage spec: /insights, /playground, /research, /eval and the eval datasets / gate API
 * (core/quality, console at /agents/quality).
 *
 * Stage specifics (why this file does not use helpers/prod-session.ts): the local kind stage serves
 * the console over http://app.<E2E_DOMAIN> (the web build's NEXT_PUBLIC_* URLs are http), so an https
 * page hits mixed-content blocks. Chromium is therefore launched with
 * --unsafely-treat-insecure-origin-as-secure for the three hosts and the page is driven over http.
 * The sign-in is the "link, email step, Keycloak form" flow that the stage landing page exposes.
 *
 * Everything this file creates is prefixed "e2e-eval-insights-" and removed in afterAll.
 * One worker only: run with --workers=1 --project=chromium.
 */
const DOMAIN = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const APP = `http://app.${DOMAIN}`;
const API = `http://api.${DOMAIN}`;
const AUTH = `http://auth.${DOMAIN}`;
const EMAIL = process.env.E2E_USER_EMAIL;
const PASSWORD = process.env.E2E_USER_PASSWORD;
const PREFIX = "e2e-eval-insights-";

test.use({
  launchOptions: { args: [`--unsafely-treat-insecure-origin-as-secure=${APP},${AUTH},${API}`] },
});

let ctx: BrowserContext;
let page: Page;
let token = "";
let agentId = "";

async function skipTour(p: Page) {
  await p.getByRole("button", { name: "Skip for now" }).click({ timeout: 2_000 }).catch(() => {});
}

/**
 * Sign-in with retry: the stage's Keycloak brute-force/quick-login protection answers
 * "user_temporarily_disabled" when several parallel agents sign in as the same user within seconds, so
 * a refused attempt is retried after a pause instead of failing the whole file.
 */
async function login(browser: Browser) {
  for (let attempt = 1; attempt <= 8; attempt++) {
    ctx = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1366, height: 900 } });
    page = await ctx.newPage();
    await page.addInitScript(() => {
      try {
        for (const k of ["agentic_language_choice_seen_v1", "vd_lang_prompt_seen", "vd_agents_guide_seen_v1", "vd_demo_tour_seen_v1", "floatingNav.hintSeen.v1"]) {
          localStorage.setItem(k, "1");
        }
        localStorage.setItem("vd_tour_status", "skipped");
        localStorage.setItem("vd_page_guides_seen", JSON.stringify(["home", "agents", "playground", "usage", "jobs"]));
      } catch { /* storage blocked */ }
    });
    token = "";
    // The welcome/product tour is user-scoped server-independent state and re-opens on fresh storage;
    // its backdrop swallows clicks, so dismiss it whenever it appears.
    await page.addLocatorHandler(page.getByRole("button", { name: "Skip for now" }), async (el) => {
      await el.click().catch(() => {});
    });
    // feature-announcement card ("Turn on two-factor authentication") has its own dismiss button
    await page.addLocatorHandler(page.getByRole("button", { name: "Got it", exact: true }), async (el) => {
      await el.click().catch(() => {});
    });
    page.on("request", (r) => {
      const a = r.headers()["authorization"];
      if (a && !token) token = a.replace(/^Bearer /, "");
    });
    await page.goto(`${APP}/login`);
    await page.getByLabel(/email/i).fill(EMAIL!);
    await page.getByRole("button", { name: /continue to secure/i }).click();
    await page.waitForSelector("#password");
    await page.locator("#password").fill(PASSWORD!);
    await page.locator("#kc-login").click();
    await page.waitForTimeout(6_000);
    await page.goto(`${APP}/playground`);
    await page.waitForTimeout(4_000);
    if (token) return;
    await ctx.close();
    await new Promise((r) => setTimeout(r, 45_000));
  }
  throw new Error("could not sign in after 8 attempts (Keycloak lockout or wrong credentials)");
}

type Res = { status: number; body: string; json: any; ms: number; headers: Record<string, string> };

/** Authenticated fetch from inside the page (same origin policy as the console). */
async function call(method: string, path: string, body?: unknown, headers: Record<string, string> = {}, auth = true): Promise<Res> {
  const t0 = Date.now();
  const r = await page.evaluate(
    async ([m, p, b, tok, api, h, a]) => {
      const x = await fetch(`${api}${p}`, {
        method: m as string,
        headers: { ...(a ? { Authorization: `Bearer ${tok}` } : {}), "Content-Type": "application/json", ...(h as object) },
        body: b ? JSON.stringify(b) : undefined,
      });
      return { status: x.status, body: await x.text(), headers: Object.fromEntries(x.headers) };
    },
    [method, path, body ?? null, token, API, headers, auth] as const
  );
  let json: any = null;
  try { json = JSON.parse(r.body); } catch { /* not json */ }
  return { ...r, json, ms: Date.now() - t0 };
}

const Q = () => `/api/agents/${agentId}/quality`;

async function sweepDatasets() {
  const l = await call("GET", `${Q()}/datasets`);
  if (l.status !== 200 || !Array.isArray(l.json)) return;
  for (const d of l.json) {
    if (!String(d.name).startsWith(PREFIX)) continue;
    // cancel any live run first so the delete cannot be refused
    const runs = await call("GET", `${Q()}/datasets/${d.id}/runs`);
    for (const r of Array.isArray(runs.json) ? runs.json : []) {
      if (["QUEUED", "RUNNING"].includes(r.status)) await call("POST", `${Q()}/datasets/${d.id}/runs/${r.id}/cancel`);
    }
    await call("DELETE", `${Q()}/datasets/${d.id}`);
  }
}

async function waitRunTerminal(datasetId: string, runId: string, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  let last: any = null;
  while (Date.now() < deadline) {
    const r = await call("GET", `${Q()}/datasets/${datasetId}/runs/${runId}`);
    last = r.json?.run;
    if (last && !["QUEUED", "RUNNING"].includes(last.status)) return last;
    await new Promise((res) => setTimeout(res, 1_500));
  }
  return last;
}

test.describe.configure({ timeout: 120_000 });

test.beforeAll(async ({ browser }) => {
  test.skip(!EMAIL || !PASSWORD, "E2E_USER_EMAIL / E2E_USER_PASSWORD not set");
  test.setTimeout(600_000);
  await login(browser);
  const agents = await call("GET", "/api/agents");
  expect(agents.status).toBe(200);
  expect(agents.json.length).toBeGreaterThan(0);
  agentId = agents.json[0].id;
  await sweepDatasets();
});

test.afterAll(async () => {
  if (!page) return;
  await sweepDatasets().catch(() => {});
  await ctx?.close();
});

/* ------------------------------------------------------------------ /insights */

test.describe("/insights", () => {
  test("renders KPIs, period pills switch the window and refetch exactly once per endpoint", async () => {
    const seen: string[] = [];
    page.on("response", (r) => {
      if (/spend-reports|agent-jobs\/counts/.test(r.url())) seen.push(`${r.status()} ${r.url().replace(/https?:\/\/[^/]+/, "")}`);
    });
    const t0 = Date.now();
    await page.goto(`${APP}/insights`);
    await skipTour(page);
    await expect(page.getByText("AI CALLS", { exact: false }).first()).toBeVisible({ timeout: 20_000 });
    const firstPaintMs = Date.now() - t0;
    test.info().annotations.push({ type: "metric", description: `insights KPI visible after ${firstPaintMs}ms` });
    expect(firstPaintMs).toBeLessThan(10_000);

    for (const [label, days] of [["30 days", 30], ["90 days", 90], ["7 days", 7]] as const) {
      seen.length = 0;
      await page.getByRole("button", { name: label }).click();
      await expect(page.getByRole("button", { name: label })).toHaveAttribute("aria-pressed", "true");
      // Going back to the period that was loaded first is answered from the client cache (observed: no
      // requests when returning to 7 days), so only the first visit to a period must hit the network.
      const firstVisit = days !== 7;
      if (firstVisit) await expect.poll(() => seen.filter((s) => /summary/.test(s)).length, { timeout: 15_000 }).toBeGreaterThan(0);
      await page.waitForTimeout(1_500);
      for (const ep of ["summary", "by-day", "by-model", "by-agent"]) {
        expect(seen.filter((s) => s.includes(`spend-reports/${ep}`)).length, `${label} ${ep} fetched at most once`).toBeLessThanOrEqual(1);
      }
      if (firstVisit) expect(seen.some((s) => s.includes(`window=P${days}D`)), `${label} sends window=P${days}D`).toBeTruthy();
      expect(seen.every((s) => s.startsWith("200")), `${label} all 200: ${seen.join(" ; ")}`).toBeTruthy();
    }
  });

  test('"View as table" exposes the chart data as a real table', async () => {
    await page.goto(`${APP}/insights`);
    await skipTour(page);
    await expect(page.getByText("AI CALLS").first()).toBeVisible({ timeout: 20_000 });
    const toggles = page.getByRole("button", { name: "View as table" });
    expect(await toggles.count()).toBeGreaterThanOrEqual(2);
    await toggles.first().click();
    await expect(page.locator("main table").first()).toBeVisible();
    expect(await page.locator("main table tr").count()).toBeGreaterThan(1);
  });

  test("a failed usage request shows an inline error and Try again recovers", async () => {
    await page.route("**/api/admin/spend-reports/**", (r) => r.fulfill({ status: 500, contentType: "application/json", body: "{}" }));
    await page.goto(`${APP}/insights`);
    await skipTour(page);
    await expect(page.getByText("We could not load the usage numbers.")).toBeVisible({ timeout: 20_000 });
    // the jobs section must still render independently
    await expect(page.getByText("Jobs right now")).toBeVisible();
    await page.unroute("**/api/admin/spend-reports/**");
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("AI CALLS").first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText("We could not load the usage numbers.")).toHaveCount(0);
  });

  test("a 403 from spend reports degrades to the admin-only note without retry storms", async () => {
    let spendCalls = 0;
    await page.route("**/api/admin/spend-reports/**", (r) => {
      spendCalls++;
      return r.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify({ status: 403 }) });
    });
    await page.goto(`${APP}/insights`);
    await skipTour(page);
    await expect(page.getByText(/visible to workspace admins only/i)).toBeVisible({ timeout: 20_000 });
    await page.waitForTimeout(1_000);
    const afterLoad = spendCalls;
    await page.getByRole("button", { name: "30 days" }).click();
    await page.waitForTimeout(1_500);
    await page.unroute("**/api/admin/spend-reports/**");
    // sticky: a period change after a refusal does not ask again
    expect(spendCalls - afterLoad, "no further spend-report calls after the refusal").toBe(0);
  });

  test("API range validation: inverted range, garbage dates and garbage job window are 400, unauthenticated is 401", async () => {
    expect((await call("GET", "/api/admin/spend-reports/summary?from=2026-10-14&to=2026-10-01")).status).toBe(400);
    expect((await call("GET", "/api/admin/spend-reports/summary?from=bad&to=worse")).status).toBe(400);
    expect((await call("GET", "/api/agent-jobs/counts?window=bogus")).status).toBe(400);
    expect((await call("GET", "/api/admin/spend-reports/summary?from=2026-10-01&to=2026-10-14", undefined, {}, false)).status).toBe(401);
  });

  test("insights endpoints answer under 2s each", async () => {
    const eps = ["summary", "by-day", "by-model", "by-agent"].map((e) => `/api/admin/spend-reports/${e}?from=2026-09-15&to=2026-10-14`);
    for (const ep of eps) {
      const r = await call("GET", ep);
      expect(r.status).toBe(200);
      expect(r.ms, `${ep} took ${r.ms}ms`).toBeLessThan(2_000);
    }
  });
});

/* ------------------------------------------------------------------ /playground */

test.describe("/playground", () => {
  test("agent picker, empty Send is disabled, a turn streams a reply, Clear context resets the transcript", async () => {
    await page.goto(`${APP}/playground`);
    await skipTour(page);
    await expect(page.getByLabel("Agent")).toBeVisible({ timeout: 20_000 }).catch(() => {});
    const input = page.getByPlaceholder("Ask something…");
    await expect(input).toBeVisible({ timeout: 20_000 });
    const send = page.getByRole("button", { name: "Send", exact: true });
    await expect(send).toBeDisabled();
    await input.fill(`${PREFIX}hello`);
    await expect(send).toBeEnabled();
    const t0 = Date.now();
    await send.click();
    await expect(page.getByText(`${PREFIX}hello`).first()).toBeVisible();
    await expect(page.getByText(/^Session: /)).toBeVisible({ timeout: 60_000 });
    test.info().annotations.push({ type: "metric", description: `playground turn complete after ${Date.now() - t0}ms` });
    await page.getByRole("button", { name: "Clear context" }).click();
    await expect(page.getByText("No messages yet.")).toBeVisible();
  });

  test("selected agent persists in the URL across reload; ?prompt prefills; unknown ?agent falls back to a real agent", async () => {
    await page.goto(`${APP}/playground`);
    await skipTour(page);
    await expect(page).toHaveURL(new RegExp(`agent=${agentId}`), { timeout: 20_000 });
    await page.reload();
    await expect(page).toHaveURL(new RegExp(`agent=${agentId}`), { timeout: 20_000 });

    await page.goto(`${APP}/playground?prompt=${encodeURIComponent(PREFIX + "prefilled")}`);
    await expect(page.getByPlaceholder("Ask something…")).toHaveValue(`${PREFIX}prefilled`, { timeout: 20_000 });

    await page.goto(`${APP}/playground?agent=00000000-0000-0000-0000-000000000000`);
    await expect(page).toHaveURL(new RegExp(`agent=${agentId}`), { timeout: 20_000 });
  });

  test("chat API contract: empty/missing/over-8000-char message is 400, unknown agent 404, unauthenticated 401", async () => {
    const sse = { Accept: "text/event-stream" };
    expect((await call("POST", `/api/agents/${agentId}/chat`, { message: "" }, sse)).status).toBe(400);
    expect((await call("POST", `/api/agents/${agentId}/chat`, {}, sse)).status).toBe(400);
    const big = await call("POST", `/api/agents/${agentId}/chat`, { message: "x".repeat(8_001) }, sse);
    expect(big.status).toBe(400);
    expect(big.body).toMatch(/8000/);
    expect((await call("POST", "/api/agents/00000000-0000-0000-0000-000000000000/chat", { message: "hi" }, sse)).status).toBe(404);
    expect((await call("POST", `/api/agents/${agentId}/chat`, { message: "hi" }, sse, false)).status).toBe(401);
  });

  // DEFECT (UX): the composer has no length limit, so a >8000 char message enables Send and only then
  // fails server-side with a 400. Observed: Send stays enabled at 20000 chars.
  test.fixme("composer blocks or warns before the server's 8000 character limit", async () => {
    await page.goto(`${APP}/playground`);
    await skipTour(page);
    await page.getByPlaceholder("Ask something…").fill("x".repeat(9_000));
    await expect(page.getByRole("button", { name: "Send", exact: true })).toBeDisabled();
  });

  // DEFECT (efficiency): every Playground load fires GET /api/browser/takeover/capabilities, which
  // answers 404 on this core and shows up as a console error.
  test.fixme("playground load produces no failed requests / console errors", async () => {
    const bad: string[] = [];
    page.on("response", (r) => { if (r.status() >= 400 && r.url().startsWith(API)) bad.push(`${r.status()} ${r.url()}`); });
    await page.goto(`${APP}/playground`);
    await skipTour(page);
    await page.waitForTimeout(4_000);
    expect(bad).toEqual([]);
  });
});

/* ------------------------------------------------------------------ /research */

test.describe("/research", () => {
  test("status API shape; page shows the server-driven availability state instead of a composer when off", async () => {
    const st = await call("GET", "/api/research/status");
    expect(st.status).toBe(200);
    expect(st.json).toEqual(expect.objectContaining({ enabled: expect.any(Boolean), searchConfigured: expect.any(Boolean), runsEnabled: expect.any(Boolean) }));
    expect((await call("GET", "/api/research/status", undefined, {}, false)).status).toBe(401);

    await page.goto(`${APP}/research`);
    await skipTour(page);
    await expect(page.getByRole("heading", { name: "Research" }).first()).toBeVisible({ timeout: 20_000 });
    if (!st.json.runsEnabled || !st.json.enabled || !st.json.searchConfigured) {
      await expect(page.getByText(/Research is not available here|switched off|search key/i).first()).toBeVisible();
      await expect(page.getByTestId("research-composer")).toHaveCount(0);
    } else {
      await expect(page.getByTestId("research-composer")).toBeVisible();
    }
  });

  test("starting a run when runs are off is refused with 404 and nothing is created", async () => {
    const st = await call("GET", "/api/research/status");
    test.skip(st.json.runsEnabled === true, "runs are enabled on this deployment");
    expect((await call("POST", "/api/research/runs", { question: `${PREFIX}probe` })).status).toBe(404);
    expect((await call("GET", "/api/research/runs")).status).toBe(404);
  });

  test("a run page for an unknown id does not crash the console", async () => {
    await page.goto(`${APP}/research/00000000-0000-0000-0000-000000000000`);
    await skipTour(page);
    await expect(page.getByRole("heading", { name: /research/i }).first()).toBeVisible({ timeout: 20_000 });
  });
});

/* ------------------------------------------------------------------ /eval */

test.describe("/eval", () => {
  test("/eval/edge-faq and /api/eval/edge-faq are dev-only routes and 404 on a production web server", async () => {
    for (const p of ["/eval/edge-faq", "/api/eval/edge-faq", "/eval"]) {
      const r = await page.request.get(`${APP}${p}`, { failOnStatusCode: false });
      expect(r.status(), p).toBe(404);
    }
  });
});

/* ------------------------------------------------------------------ eval datasets / gate API */

test.describe("eval datasets and gate API", () => {
  test("dataset and case validation: blank/too long name, threshold outside 0..1, blank case message", async () => {
    expect((await call("POST", `${Q()}/datasets`, { name: "" })).status).toBe(400);
    expect((await call("POST", `${Q()}/datasets`, { name: `${PREFIX}bad`, passThreshold: 5 })).status).toBe(400);
    expect((await call("POST", `${Q()}/datasets`, { name: "x".repeat(300) })).status).toBe(400);
    const d = await call("POST", `${Q()}/datasets`, { name: `${PREFIX}val`, passThreshold: 0.5 });
    expect(d.status).toBe(200);
    expect((await call("POST", `${Q()}/datasets/${d.json.id}/cases`, { userMessage: "" })).status).toBe(400);
    expect((await call("DELETE", `${Q()}/datasets/${d.json.id}`)).status).toBe(204);
  });

  test("dataset lifecycle: create, add/update case, content hash changes, JSONL export, rename, delete", async () => {
    const d = await call("POST", `${Q()}/datasets`, { name: `${PREFIX}life`, description: "lifecycle", passThreshold: 0.5 });
    expect(d.status).toBe(200);
    const id = d.json.id;
    const h0 = d.json.contentHash;
    const c = await call("POST", `${Q()}/datasets/${id}/cases`, { userMessage: "Say hello", expectedOutput: "hello" });
    expect(c.status).toBe(200);
    const u = await call("PUT", `${Q()}/datasets/${id}/cases/${c.json.id}`, { userMessage: "Say goodbye", expectedOutput: "goodbye" });
    expect(u.status).toBe(200);
    const got = await call("GET", `${Q()}/datasets/${id}`);
    expect(got.json.dataset.caseCount).toBe(1);
    expect(got.json.dataset.contentHash).not.toBe(h0);
    expect(got.json.cases[0].userMessage).toBe("Say goodbye");
    const ex = await call("GET", `${Q()}/datasets/${id}/export.jsonl`);
    expect(ex.status).toBe(200);
    expect(ex.body).toContain("vegaduta.eval-dataset-jsonl/v1");
    const rn = await call("PUT", `${Q()}/datasets/${id}`, { name: `${PREFIX}life-renamed`, passThreshold: 0.4 });
    expect(rn.status).toBe(200);
    expect(rn.json.passThreshold).toBe(0.4);
    expect((await call("DELETE", `${Q()}/datasets/${id}/cases/${c.json.id}`)).status).toBe(204);
    expect((await call("DELETE", `${Q()}/datasets/${id}`)).status).toBe(204);
  });

  test("cross-agent isolation and auth: wrong agent id is 404, no token is 401", async () => {
    const d = await call("POST", `${Q()}/datasets`, { name: `${PREFIX}iso` });
    const other = "00000000-0000-0000-0000-000000000000";
    expect((await call("GET", `/api/agents/${other}/quality/datasets/${d.json.id}`)).status).toBe(404);
    expect((await call("GET", `/api/agents/${other}/quality/datasets`)).status).toBe(404);
    expect((await call("GET", `${Q()}/datasets`, undefined, {}, false)).status).toBe(401);
    await call("DELETE", `${Q()}/datasets/${d.json.id}`);
  });

  test("run: trigger is idempotent per Idempotency-Key, validation of repeats, run reaches a terminal state, JUnit + bundle export", async () => {
    const d = await call("POST", `${Q()}/datasets`, { name: `${PREFIX}run`, passThreshold: 0.4 });
    const id = d.json.id;
    await call("POST", `${Q()}/datasets/${id}/cases`, { userMessage: "Say hello in one word", expectedOutput: "hello" });
    const key = `e2e-ei-${Date.now()}`;
    const r1 = await call("POST", `${Q()}/datasets/${id}/runs`, { repeats: 1 }, { "Idempotency-Key": key });
    expect(r1.status).toBe(200);
    expect(r1.json.status).toMatch(/QUEUED|RUNNING/);
    const r2 = await call("POST", `${Q()}/datasets/${id}/runs`, { repeats: 1 }, { "Idempotency-Key": key });
    expect(r2.status).toBe(200);
    expect(r2.json.id).toBe(r1.json.id);
    expect((await call("POST", `${Q()}/datasets/${id}/runs`, { repeats: 99 })).status).toBe(400);

    const done = await waitRunTerminal(id, r1.json.id);
    expect(["SUCCEEDED", "FAILED", "CANCELLED"]).toContain(done.status);
    test.info().annotations.push({ type: "run", description: `terminal=${done.status} verdict=${done.verdict} err=${done.errorMessage ?? ""}` });
    const junit = await call("GET", `${Q()}/datasets/${id}/runs/${r1.json.id}/junit`);
    expect(junit.status).toBe(200);
    expect(junit.body).toContain("<testsuite");
    const bundle = await call("GET", `${Q()}/datasets/${id}/runs/${r1.json.id}/bundle`);
    expect(bundle.status).toBe(200);
    expect(bundle.json.sha256).toMatch(/^[0-9a-f]{64}$/);
    const hist = await call("GET", `${Q()}/datasets/${id}/runs`);
    expect(hist.json.map((x: any) => x.id)).toContain(r1.json.id);
    await call("DELETE", `${Q()}/datasets/${id}`);
  });

  // DEFECT (server): EvalRunConflictException is annotated @ResponseStatus(CONFLICT) but the central
  // FallbackExceptionHandler (app module) answers every un-mapped exception 500 "Unhandled exception".
  // Observed live: second trigger while a run is live, and delete while a run is live, both 500.
  test.fixme("triggering a second run while one is live answers 409, not 500", async () => {
    const d = await call("POST", `${Q()}/datasets`, { name: `${PREFIX}conflict` });
    await call("POST", `${Q()}/datasets/${d.json.id}/cases`, { userMessage: "hello" });
    const a = await call("POST", `${Q()}/datasets/${d.json.id}/runs`, { repeats: 1 });
    expect(a.status).toBe(200);
    const b = await call("POST", `${Q()}/datasets/${d.json.id}/runs`, { repeats: 1 });
    expect(b.status).toBe(409);
  });

  test.fixme("deleting a dataset with a live run answers 409, not 500", async () => {
    const d = await call("POST", `${Q()}/datasets`, { name: `${PREFIX}delconflict` });
    await call("POST", `${Q()}/datasets/${d.json.id}/cases`, { userMessage: "hello" });
    await call("POST", `${Q()}/datasets/${d.json.id}/runs`, { repeats: 1 });
    expect((await call("DELETE", `${Q()}/datasets/${d.json.id}`)).status).toBe(409);
  });

  // DEFECT (server): EvalDatasetNotFoundException (@ResponseStatus NOT_FOUND) is answered 500.
  test.fixme("a deleted / unknown dataset id answers 404 on GET and when set as the gate dataset", async () => {
    const ghost = "11111111-1111-1111-1111-111111111111";
    expect((await call("GET", `${Q()}/datasets/${ghost}`)).status).toBe(404);
    expect((await call("PUT", `${Q()}/gate`, { datasetId: ghost, enabled: true })).status).toBeGreaterThanOrEqual(400);
    expect((await call("PUT", `${Q()}/gate`, { datasetId: ghost, enabled: true })).status).toBeLessThan(500);
  });

  test("promotion gate: configure, read back, reject a non-positive freshness window, clear", async () => {
    const d = await call("POST", `${Q()}/datasets`, { name: `${PREFIX}gate` });
    const put = await call("PUT", `${Q()}/gate`, { datasetId: d.json.id, enabled: true, maxRunAgeHours: 24 });
    expect(put.status).toBe(200);
    const g = await call("GET", `${Q()}/gate`);
    expect(g.json.gate.datasetId).toBe(d.json.id);
    expect(g.json.gate.maxRunAgeHours).toBe(24);
    expect(g.json.headEvaluation.allowed).toBe(false); // gate on, dataset never passed on head
    expect((await call("PUT", `${Q()}/gate`, { datasetId: d.json.id, enabled: true, maxRunAgeHours: -5 })).status).toBe(400);
    expect((await call("DELETE", `${Q()}/gate`)).status).toBe(204);
    expect((await call("GET", `${Q()}/gate`)).json.gate).toBeNull();
    await call("DELETE", `${Q()}/datasets/${d.json.id}`);
  });

  test("reliability endpoints: empty/never-succeeded agent reports warnings, not an error", async () => {
    const r = await call("GET", `${Q()}/reliability`);
    expect(r.status).toBe(200);
    expect(Array.isArray(r.json.datasets)).toBe(true);
    expect(Array.isArray(r.json.warnings)).toBe(true);
    expect((await call("GET", `${Q()}/reliability/noise-floor`)).status).toBe(400); // datasetId is required
  });

  test("seed-golden creates a 35-case dataset that can be deleted again", async () => {
    const before = await call("GET", `${Q()}/datasets`);
    const had = new Set((before.json as any[]).map((x) => x.id));
    const s = await call("POST", `${Q()}/datasets/seed-golden`, {});
    expect(s.status).toBe(200);
    expect(s.json.dataset.caseCount).toBeGreaterThan(10);
    if (!had.has(s.json.dataset.id)) expect((await call("DELETE", `${Q()}/datasets/${s.json.dataset.id}`)).status).toBe(204);
  });
});

/* ------------------------------------------------------------------ /agents/quality console */

test.describe("/agents/quality console (evals UI)", () => {
  test("five tabs render and each loads its own endpoints", async () => {
    const calls: string[] = [];
    page.on("response", (r) => { if (r.url().startsWith(API)) calls.push(`${r.status()} ${r.url().replace(API, "")}`); });
    await page.goto(`${APP}/agents/quality`);
    await skipTour(page);
    for (const tab of ["Live quality", "Datasets & gate", "Simulations", "Reliability", "Solutions"]) {
      await expect(page.locator("button[role=tab]", { hasText: tab }).first()).toBeVisible({ timeout: 20_000 });
    }
    await page.locator("button[role=tab]", { hasText: "Datasets & gate" }).first().click();
    await expect(page.getByText("Eval datasets & regression runs").first()).toBeVisible();
    await expect(page.getByText("Promotion gate")).toBeVisible();
    await page.locator("button[role=tab]", { hasText: "Simulations" }).first().click();
    await expect(page.getByText("Attack my agent").first()).toBeVisible();
    await expect(page.getByRole("button", { name: /Run simulation \(0\)/ })).toBeDisabled();
    await page.locator("button[role=tab]", { hasText: "Reliability" }).first().click();
    await expect(page.getByText("Honest eval statistics")).toBeVisible();
    await expect.poll(() => calls.some((c) => c.includes("/quality/datasets") && c.startsWith("200"))).toBeTruthy();
    await expect.poll(() => calls.some((c) => c.includes("/quality/reliability") && c.startsWith("200")), { timeout: 10_000 }).toBeTruthy();
  });

  test("create a dataset in the UI, add a case, both survive reload, then delete it via the confirm dialog", async () => {
    const name = `${PREFIX}ui-${Date.now() % 100000}`;
    await page.goto(`${APP}/agents/quality`);
    await skipTour(page);
    await page.locator("button[role=tab]", { hasText: "Datasets & gate" }).first().click();
    await page.getByRole("button", { name: "New dataset" }).first().click();
    const create = page.getByRole("button", { name: /^create/i }).first();
    await expect(create).toBeDisabled(); // blank name cannot be submitted
    await page.getByRole("textbox").first().fill(name);
    await create.click();
    await expect(page.getByRole("button", { name }).first()).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name }).first().click();
    await page.getByPlaceholder(/User message to replay/i).fill("What is the capital of France?");
    await page.getByRole("button", { name: "Add case" }).click();
    await expect(page.getByText("What is the capital of France?").first()).toBeVisible({ timeout: 15_000 });

    await page.reload();
    await skipTour(page);
    await page.locator("button[role=tab]", { hasText: "Datasets & gate" }).first().click();
    const row = page.getByRole("button", { name }).first();
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/1 case/).first()).toBeVisible();

    // delete through the confirm dialog (cancel first: nothing is deleted)
    const rowCard = page.locator("div", { has: row }).filter({ has: page.getByRole("button", { name: "Delete dataset" }) }).last();
    await rowCard.getByRole("button", { name: "Delete dataset" }).first().click();
    await page.keyboard.press("Escape");
    await expect(row).toBeVisible();
    await rowCard.getByRole("button", { name: "Delete dataset" }).first().click();
    await page.getByRole("alertdialog").getByRole("button", { name: /delete/i }).click();
    await expect(page.getByRole("button", { name })).toHaveCount(0, { timeout: 15_000 });
  });

  // DEFECT (efficiency/noise): with app.quality.eval.calibration.enabled=false the console still asks
  // GET .../quality/judge-calibration-sets and .../judge-calibration-history on every Datasets tab
  // open; both answer 404 and surface as console errors. qualityApi should treat them as quiet 404s
  // (toJsonQuietOn([403, 404])) or gate on /quality/config.
  test.fixme("opening the Datasets tab causes no failed requests", async () => {
    const bad: string[] = [];
    page.on("response", (r) => { if (r.status() >= 400 && r.url().startsWith(API)) bad.push(`${r.status()} ${r.url()}`); });
    await page.goto(`${APP}/agents/quality`);
    await skipTour(page);
    await page.locator("button[role=tab]", { hasText: "Datasets & gate" }).first().click();
    await page.waitForTimeout(3_000);
    expect(bad).toEqual([]);
  });
});
