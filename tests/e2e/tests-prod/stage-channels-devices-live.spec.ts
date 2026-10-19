import { test, expect, type Page, type BrowserContext, type Locator } from "@playwright/test";

/**
 * Channels / devices / jobs / edge coverage for the local kind stage (area "channels-devices").
 *
 * Pages: /devices (forwards to the Mobile SDK tab), /admin/channels (omnichannel tiles + inspectors),
 * /edge, /jobs (Runs + Schedules), /scheduled, /coding, /clips, /code, /hire and /earn.
 *
 * Why this does not reuse helpers/prod-session.ts (same reasons as stage-chat-switch-live.spec.ts): the stage
 * is served over plain http, the OSS landing page has a "Sign in" LINK (not a header button), so
 * signInToConsole() times out - all 11 tests in channel-connectors-and-devices.spec.ts fail at that line, a
 * test-env issue, not a product defect.
 *
 * The LLM is the in-cluster mock. Every entity created is prefixed "e2e-channels-devices-" and removed after.
 * No real third-party credentials are ever submitted (one deliberately junk Telegram token is used to prove
 * the rejection path). Mutation-gated (E2E_ALLOW_MUTATIONS=1).
 */
const DOMAIN = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const SCHEME = process.env.E2E_STAGE_SCHEME || "http";
const APP = `${SCHEME}://app.${DOMAIN}`;
const API = `${SCHEME}://api.${DOMAIN}`;
const EMAIL = process.env.E2E_USER_EMAIL;
const PASSWORD = process.env.E2E_USER_PASSWORD;
const PREFIX = "e2e-channels-devices-";
const RUN = Date.now().toString(36);

let ctx: BrowserContext;
let page: Page;
let token = "";
let agentId = "";
let userId = "";
const createdDeviceNames: string[] = [];
const timings: Record<string, number> = {};

const bearer = () => ({ Authorization: `Bearer ${token}` });
const json = () => ({ ...bearer(), "Content-Type": "application/json" });

const SEEN_FLAGS = (uid: string) => {
  try {
    for (const k of ["agentic_language_choice_seen_v1", "vd_lang_prompt_seen", "vd_agents_guide_seen_v1", "vd_demo_tour_seen_v1", "floatingNav.hintSeen.v1"]) {
      localStorage.setItem(k, "1");
    }
    localStorage.setItem("vd_tour_status", "skipped");
    if (uid) {
      localStorage.setItem(`vd_tour_status:${uid}`, "skipped");
      localStorage.setItem(`vd_tour_seen_security:${uid}`, "1");
    }
  } catch {
    /* storage blocked */
  }
};

async function clearOverlays(p: Page) {
  for (let i = 0; i < 3; i++) {
    const blocker = p.locator('[data-tour-blocker], [role="dialog"][aria-label="Product tour"]').first();
    if (!(await blocker.isVisible({ timeout: 800 }).catch(() => false))) return;
    await p.keyboard.press("Escape");
    await p.waitForTimeout(300);
  }
}

async function gotoApp(p: Page, path: string) {
  const t0 = Date.now();
  await p.goto(`${APP}${path}`, { waitUntil: "domcontentloaded" });
  await clearOverlays(p);
  timings[path] = Date.now() - t0;
}

async function safeClick(p: Page, loc: Locator) {
  for (let i = 0; i < 4; i++) {
    await clearOverlays(p);
    try {
      await loc.click({ timeout: 5_000 });
      return;
    } catch (e) {
      if (i === 3) throw e;
    }
  }
}

async function signIn(p: Page) {
  await p.goto(`${APP}/login`);
  await p.getByLabel(/email/i).fill(EMAIL!);
  await p.getByRole("button", { name: /continue to secure/i }).click();
  await p.waitForSelector("#password", { timeout: 30_000 });
  await p.locator("#password").fill(PASSWORD!);
  await p.locator("#kc-login").click();
  await p.waitForURL((u) => u.hostname.startsWith("app.") && !u.hash.includes("state"), { waitUntil: "commit", timeout: 45_000 });
  await expect(p.locator("header, nav").first()).toBeVisible({ timeout: 30_000 });
}

const tile = (p: Page, key: string) => p.locator(`[data-testid="channel-card"][data-channel="${key}"]`);
const inspector = (p: Page) => p.getByRole("heading", { level: 2 }).or(p.getByRole("heading", { level: 3 }));

test.describe.configure({ mode: "serial" });

test.describe("channels-devices (stage)", () => {
  test.beforeAll(async ({ browser }, testInfo) => {
    test.skip(!EMAIL || !PASSWORD, "E2E_USER_EMAIL / E2E_USER_PASSWORD not set");
    test.skip(process.env.E2E_ALLOW_MUTATIONS !== "1", "creates an agent, jobs and a device - set E2E_ALLOW_MUTATIONS=1");
    testInfo.setTimeout(180_000);
    ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
    await ctx.addInitScript(SEEN_FLAGS, "");
    page = await ctx.newPage();
    page.on("request", (r) => {
      const a = r.headers()["authorization"];
      if (a && r.url().startsWith(API) && !token) token = a.replace(/^Bearer /, "");
    });
    await signIn(page);
    await gotoApp(page, "/jobs");
    await expect.poll(() => token, { timeout: 20_000 }).not.toBe("");
    const me = await (await ctx.request.get(`${API}/api/me`, { headers: bearer() })).json();
    userId = String(me.subject ?? me.id ?? "");
    await ctx.addInitScript(SEEN_FLAGS, userId);
    const res = await ctx.request.post(`${API}/api/agents`, {
      headers: json(),
      data: { name: `${PREFIX}agent-${RUN}`, systemPrompt: "You are a test agent.", providerName: "OLLAMA", model: "mock:latest", maxTokens: 64 },
    });
    expect(res.status(), "create agent").toBeLessThan(300);
    agentId = (await res.json()).id;
  });

  test.afterAll(async () => {
    if (!ctx) return;
    if (agentId) {
      // cancel anything still live, then drop the agent (jobs/schedule go with it)
      const jobs = await ctx.request.get(`${API}/api/agents/${agentId}/jobs?limit=50`, { headers: bearer() }).then((r) => r.json()).catch(() => []);
      for (const j of Array.isArray(jobs) ? jobs : []) {
        await ctx.request.post(`${API}/api/agents/${agentId}/jobs/${j.id}/cancel`, { headers: bearer(), failOnStatusCode: false }).catch(() => {});
      }
      await ctx.request.delete(`${API}/api/agents/${agentId}/schedule`, { headers: bearer(), failOnStatusCode: false }).catch(() => {});
      await ctx.request.delete(`${API}/api/agents/${agentId}`, { headers: bearer(), failOnStatusCode: false }).catch(() => {});
    }
    // restore sentinel policy defaults if a test left it changed
    await ctx.request.put(`${API}/api/mobile/sentinels/policy`, { headers: json(), data: { retentionDays: 30 }, failOnStatusCode: false }).catch(() => {});
    console.log("PAGE_TIMINGS_MS " + JSON.stringify(timings));
    await ctx.close().catch(() => {});
  });

  // ------------------------------------------------------------------ routing

  test("/devices forwards to the Mobile SDK tab; /hire and /earn are OSS-mode redirects for anonymous visitors", async ({ request }) => {
    await gotoApp(page, "/devices");
    // ChannelsPanel strips ?channel= right after applying it, so only the path is stable
    await expect(page).toHaveURL(/\/admin\/channels/, { timeout: 20_000 });
    await expect(page.getByRole("heading", { name: "Mobile SDK" })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("button", { name: "Generate pairing code" })).toBeVisible();
    // anonymous request (no cookies): OSS site-mode proxy redirects marketing routes to "/" (proxy.js), documented behaviour
    for (const path of ["/hire", "/earn"]) {
      const res = await request.get(`${APP}${path}`, { maxRedirects: 0 });
      expect(res.status(), `${path} anonymous`).toBe(307);
      expect(res.headers()["location"]).toMatch(/^(https?:\/\/[^/]+)?\/$/);
    }
  });

  // ------------------------------------------------------------------ channels

  test("channel grid lists every channel; counters match; tiles are toggle buttons that open their inspector", async () => {
    await gotoApp(page, "/admin/channels");
    await expect(page.getByRole("heading", { name: "Channels", level: 1 })).toBeVisible({ timeout: 20_000 });
    const keys = ["whatsapp", "telegram", "instagram", "messenger", "slack", "discord", "x", "teams", "email", "voice", "mobile-sdk"];
    const status = await (await ctx.request.get(`${API}/api/channels/status`, { headers: bearer() })).json();
    const apiKeys = (status.channels as Array<{ key: string }>).map((c) => c.key);
    // "sms" is counted in totalCount (12) but has no tile of its own (it is configured inside the WhatsApp/Twilio panel):
    // the header says "12 available" while 11 tiles are drawn - recorded as a low-severity UX inconsistency.
    await expect(page.locator('[data-testid="channel-card"]')).toHaveCount(apiKeys.filter((k) => k !== "sms").length, { timeout: 15_000 });
    await expect(page.getByText(`${status.liveCount} live / ${status.totalCount} available`)).toBeVisible();
    expect(keys.length).toBeGreaterThan(0);
    const headings: Record<string, RegExp> = {
      telegram: /Connect Telegram/,
      slack: /Connect Slack/,
      discord: /Connect Discord/,
      teams: /Connect Microsoft Teams/,
      email: /Connect Email/,
      x: /Connect X/,
      voice: /Voice calls/,
    };
    for (const [key, h] of Object.entries(headings)) {
      const t = tile(page, key);
      await expect(t, `${key} tile`).toBeVisible();
      await safeClick(page, t);
      await expect(t).toHaveAttribute("aria-pressed", "true");
      await expect(page.getByRole("heading", { name: h }).first(), `${key} inspector heading`).toBeVisible();
    }
  });

  test("channel inspector forms: required-field validation, secret masking, keyboard activation, deep link", async () => {
    await gotoApp(page, "/admin/channels");
    // Telegram: empty submit is rejected client-side with a named field error and makes no credential POST
    const posts: string[] = [];
    const onReq = (r: import("@playwright/test").Request) => {
      if (r.method() === "POST" && r.url().includes("/api/channels/")) posts.push(r.url());
    };
    page.on("request", onReq);
    await safeClick(page, tile(page, "telegram"));
    await expect(page.getByLabel("Bot Token")).toHaveAttribute("type", "password");
    await safeClick(page, page.getByRole("button", { name: /^Connect$/ }));
    await expect(page.getByText("Bot Token is required.").first()).toBeVisible();
    expect(posts, "no credential POST on empty form").toEqual([]);
    // Slack: both secrets required, both masked
    await safeClick(page, tile(page, "slack"));
    await expect(page.getByLabel("Bot Token")).toHaveAttribute("type", "password");
    await expect(page.getByLabel("Signing Secret")).toHaveAttribute("type", "password");
    // Email: numeric port field, password masked
    await safeClick(page, tile(page, "email"));
    await expect(page.getByLabel("Port")).toBeVisible();
    await expect(page.getByLabel(/^Password/)).toHaveAttribute("type", "password");
    // Keyboard: focus a tile and activate it with Enter / Space
    await tile(page, "discord").focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: /Connect Discord/ }).first()).toBeVisible();
    await tile(page, "teams").focus();
    await page.keyboard.press("Space");
    await expect(page.getByRole("heading", { name: /Connect Microsoft Teams/ }).first()).toBeVisible();
    page.off("request", onReq);
    // Deep link selects the tile on first load
    await gotoApp(page, "/admin/channels?channel=slack");
    await expect(page.getByRole("heading", { name: /Connect Slack/ }).first()).toBeVisible({ timeout: 15_000 });
    await expect(tile(page, "slack")).toHaveAttribute("aria-pressed", "true");
  });

  test("inbound routing: Save stays disabled until an agent is chosen; unconnected channels show 'not configured'", async () => {
    await gotoApp(page, "/admin/channels?channel=telegram");
    await expect(page.getByText("Not configured").first()).toBeVisible({ timeout: 15_000 });
    const save = page.getByRole("button", { name: "Save", exact: true }).first();
    await expect(save).toBeDisabled();
    await expect(page.getByText(/No default agent/).first()).toBeVisible();
    await expect(page.getByTestId("route-box").getByRole("link", { name: "Review waiting senders" })).toHaveAttribute("href", /.+/);
  });

  test("a junk Telegram token is rejected by the server and shown to the user; nothing is stored", async () => {
    await gotoApp(page, "/admin/channels?channel=telegram");
    await page.getByLabel("Bot Token").fill("junk-token-e2e");
    const t0 = Date.now();
    const resp = page.waitForResponse((r) => r.url().endsWith("/api/channels/telegram/credential") && r.request().method() === "POST", { timeout: 40_000 });
    await safeClick(page, page.getByRole("button", { name: /^Connect$/ }));
    const r = await resp;
    timings["POST telegram credential (junk token)"] = Date.now() - t0;
    expect(r.status()).toBe(400);
    await expect(page.getByRole("alert").filter({ hasText: /Telegram rejected|invalid|token/i }).first()).toBeVisible({ timeout: 10_000 });
    const cred = await ctx.request.get(`${API}/api/channels/telegram/credential`, { headers: bearer() });
    expect(cred.status(), "junk token must not be persisted").toBe(204);
  });

  test("API contract: connecting with a blank token is a 400, not a 500, for every token channel", async () => {
    for (const [path, body] of [
      ["telegram", { botToken: "" }],
      ["discord", { botToken: "" }],
      ["slack", { botToken: "", signingSecret: "" }],
    ] as const) {
      const res = await ctx.request.post(`${API}/api/channels/${path}/credential`, { headers: json(), data: body, failOnStatusCode: false });
      expect(res.status(), `${path} blank`).toBe(400);
    }
    const noAuth = await ctx.request.get(`${API}/api/channels/status`, { failOnStatusCode: false, headers: {} });
    expect([401, 403]).toContain(noAuth.status());
  });

  // ------------------------------------------------------------------ devices / mobile sdk

  test("device pairing lifecycle through the UI: code -> claim -> listed -> poll -> revoke (two-click) -> poll rejected", async () => {
    await gotoApp(page, "/devices");
    await expect(page.getByRole("button", { name: "Generate pairing code" })).toBeVisible({ timeout: 20_000 });
    await safeClick(page, page.getByRole("button", { name: "Generate pairing code" }));
    const codeEl = page.locator("div.font-mono").filter({ hasText: /^\d{6}$/ }).first();
    await expect(codeEl).toBeVisible({ timeout: 20_000 });
    const code = (await codeEl.textContent())!.trim();
    // NOTE: /api/devices/claim allows 10 attempts per IP per window (DeviceClaimRateLimiter) and every stage client shares one IP,
    // so this test spends exactly two claim calls (real + reuse) and does not probe wrong codes.
    const deviceName = `${PREFIX}device-${RUN}`;
    createdDeviceNames.push(deviceName);
    const claim = await ctx.request.post(`${API}/api/devices/claim`, { data: { code, deviceName, platform: "ANDROID" } });
    expect(claim.status(), await claim.text()).toBe(200);
    const deviceToken = (await claim.json()).deviceToken as string;
    expect(deviceToken).toBeTruthy();
    // a pairing code is single-use
    const reuse = await ctx.request.post(`${API}/api/devices/claim`, { data: { code, deviceName: deviceName + "-2", platform: "ANDROID" }, failOnStatusCode: false });
    expect(reuse.status(), "pairing code reused").toBeGreaterThanOrEqual(400);
    expect(reuse.status()).toBeLessThan(500);
    // device appears without a manual reload (panel live-polls) or after reload
    // ChannelsPanel strips ?channel= with router.replace after applying it, so a reload lands on the WhatsApp tab
    // (low-severity UX finding: selected tab is not in the URL) - reopen the Mobile SDK tile.
    await page.reload({ waitUntil: "domcontentloaded" });
    await safeClick(page, tile(page, "mobile-sdk"));
    const nameEl = page.getByText(deviceName, { exact: true }).first();
    await expect(nameEl).toBeVisible({ timeout: 30_000 });
    const card = nameEl.locator("xpath=ancestor::div[.//button[contains(., 'Revoke')]][1]");
    await expect(card.getByText(/never polled/i)).toBeVisible();
    const poll = await ctx.request.post(`${API}/api/devices/poll`, { headers: { "X-Device-Token": deviceToken } });
    expect([200, 204]).toContain(poll.status());
    // first click arms, Cancel disarms, second click revokes
    await card.getByRole("button", { name: "Revoke" }).click();
    await expect(card.getByRole("button", { name: "Confirm revoke" })).toBeVisible();
    await card.getByRole("button", { name: /^Cancel$/ }).click();
    await expect(card.getByRole("button", { name: "Revoke" })).toBeVisible();
    await card.getByRole("button", { name: "Revoke" }).click();
    await card.getByRole("button", { name: "Confirm revoke" }).click();
    await expect(page.getByText(/Device revoked/i).first()).toBeVisible({ timeout: 20_000 });
    const afterPoll = await ctx.request.post(`${API}/api/devices/poll`, { headers: { "X-Device-Token": deviceToken }, failOnStatusCode: false });
    expect(afterPoll.status(), "revoked device token must not authenticate").toBe(400);
    // state persists after reload: still listed, badge says revoked, no Revoke button
    await page.reload({ waitUntil: "domcontentloaded" });
    await safeClick(page, tile(page, "mobile-sdk"));
    const revoked = page.getByText(deviceName, { exact: true }).first().locator("xpath=ancestor::div[.//button[contains(., 'Command history')]][1]");
    await expect(revoked.getByText(/^revoked$/i)).toBeVisible({ timeout: 30_000 });
    await expect(revoked.getByRole("button", { name: "Revoke" })).toHaveCount(0);
  });

  test("pairing codes: each generate call returns a fresh 6-digit code; device endpoints reject missing/garbage auth", async () => {
    const c1 = await (await ctx.request.post(`${API}/api/devices/pairing-code`, { headers: bearer() })).json();
    const c2 = await (await ctx.request.post(`${API}/api/devices/pairing-code`, { headers: bearer() })).json();
    expect(String(c1.code)).toMatch(/^\d{6}$/);
    expect(String(c2.code)).toMatch(/^\d{6}$/);
    expect(c2.code, "second code must differ from the first").not.toBe(c1.code);
    const anon = await ctx.request.post(`${API}/api/devices/pairing-code`, { failOnStatusCode: false });
    expect([401, 403]).toContain(anon.status());
    const garbage = await ctx.request.post(`${API}/api/devices/poll`, { headers: { "X-Device-Token": "garbage" }, failOnStatusCode: false });
    expect(garbage.status()).toBeGreaterThanOrEqual(400);
    expect(garbage.status()).toBeLessThan(500);
  });

  // DEFECT: DeviceGatewayController#poll/#ack and DeviceDataIngestionController declare @RequestHeader("X-Device-Token") as
  // required, and RequestBindingExceptionHandler has no handler for MissingRequestHeaderException, so a request WITHOUT the
  // header falls through to FallbackExceptionHandler: HTTP 500 "Something went wrong" + an ERROR log line per request, on a
  // permitAll() endpoint anyone can hit. Expected: 400/401.
  test.fixme("DEFECT: POST /api/devices/poll without X-Device-Token returns 4xx, not 500", async () => {
    const noTok = await ctx.request.post(`${API}/api/devices/poll`, { failOnStatusCode: false });
    expect(noTok.status()).toBeGreaterThanOrEqual(400);
    expect(noTok.status()).toBeLessThan(500);
  });

  test("Sentinels policy: Save is gated on a change, persists across reload, and reverts cleanly", async () => {
    await gotoApp(page, "/admin/channels?channel=mobile-sdk");
    const retention = page.getByLabel("Retention (days)");
    await expect(retention).toBeVisible({ timeout: 20_000 });
    const save = page.getByRole("button", { name: "Save policy" });
    await expect(save).toBeDisabled();
    const original = await retention.inputValue();
    const next = original === "31" ? "32" : "31";
    await retention.fill(next);
    await expect(save).toBeEnabled();
    const put = page.waitForResponse((r) => r.url().includes("/api/mobile/sentinels/policy") && r.request().method() === "PUT");
    await safeClick(page, save);
    expect((await put).status()).toBeLessThan(300);
    await gotoApp(page, "/admin/channels?channel=mobile-sdk");
    await expect(page.getByLabel("Retention (days)")).toHaveValue(next, { timeout: 20_000 });
    // restore
    await page.getByLabel("Retention (days)").fill(original);
    await safeClick(page, page.getByRole("button", { name: "Save policy" }));
    await expect.poll(async () => (await (await ctx.request.get(`${API}/api/mobile/sentinels/policy`, { headers: bearer() })).json()).retentionDays).toBe(Number(original));
  });

  test("Sentinels policy API rejects an out-of-range retention instead of storing it", async () => {
    const res = await ctx.request.put(`${API}/api/mobile/sentinels/policy`, { headers: json(), data: { retentionDays: -5 }, failOnStatusCode: false });
    expect(res.status(), "negative retention").toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
  });

  // ------------------------------------------------------------------ edge

  test("/edge: device verdict, mode switch, model catalog matches the manifest, relay section renders", async () => {
    await gotoApp(page, "/edge");
    await expect(page.getByRole("heading", { name: /VegaDūta Edge/, level: 1 })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText("THIS DEVICE", { exact: false }).first()).toBeVisible();
    const manifest = await (await ctx.request.get(`${API}/api/edge/manifest`, { headers: bearer() })).json();
    const downloadable = (manifest.models as Array<{ url: string | null }>).filter((m) => m.url).length;
    expect(downloadable).toBeGreaterThan(0);
    // headless chromium has no WebGPU: the verdict must say so and "Edge first" must not be selectable
    await expect(page.getByText(/Not supported|WebGPU: No/).first()).toBeVisible();
    const hosted = page.getByRole("button", { name: /Hosted only/ });
    await expect(hosted).toBeVisible();
    await safeClick(page, hosted);
    await expect(hosted).toHaveAttribute("aria-pressed", "true");
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.getByRole("button", { name: /Hosted only/ })).toHaveAttribute("aria-pressed", "true", { timeout: 20_000 });
    // relay section
    const relay = await (await ctx.request.get(`${API}/api/channels/edge-relay/status`, { headers: bearer() })).json();
    expect(typeof relay.enabled).toBe("boolean");
  });

  // ------------------------------------------------------------------ jobs

  test("/jobs launcher: Start is gated on a brief, counter tracks length, deep link preselects the agent", async () => {
    await gotoApp(page, `/jobs?agentId=${agentId}`);
    const start = page.getByRole("button", { name: "Start in background" });
    await expect(start).toBeDisabled({ timeout: 20_000 });
    await expect(page.locator("select").first()).toHaveValue(agentId);
    const goal = page.getByPlaceholder(/Describe the whole job/);
    await goal.fill("   ");
    await expect(start, "whitespace-only brief").toBeDisabled();
    await goal.fill("abcde");
    await expect(page.getByText("5/8000")).toBeVisible();
    await expect(start).toBeEnabled();
    await goal.fill("");
    await expect(page.getByText("0/8000")).toBeVisible();
  });

  test("/jobs: start a job -> detail page -> listed under All -> cancel is idempotent and sticks", async () => {
    await gotoApp(page, `/jobs?agentId=${agentId}`);
    await page.getByPlaceholder(/Describe the whole job/).fill(`${PREFIX}job ${RUN}`);
    const created = page.waitForResponse((r) => /\/api\/agents\/[^/]+\/jobs$/.test(r.url()) && r.request().method() === "POST");
    await safeClick(page, page.getByRole("button", { name: "Start in background" }));
    const resp = await created;
    expect(resp.status()).toBe(202);
    const job = await resp.json();
    await expect(page).toHaveURL(new RegExp(`/jobs/${agentId}/${job.id}`), { timeout: 20_000 });
    await expect(page.getByText(`${PREFIX}job ${RUN}`).first()).toBeVisible();
    // cancel through the API twice (idempotent per agentLifecycle.ts) and confirm the UI shows Cancelled
    for (let i = 0; i < 2; i++) {
      const c = await ctx.request.post(`${API}/api/agents/${agentId}/jobs/${job.id}/cancel`, { headers: bearer(), failOnStatusCode: false });
      expect(c.status(), `cancel #${i + 1}`).toBeLessThan(300);
    }
    await expect.poll(async () => (await (await ctx.request.get(`${API}/api/agents/${agentId}/jobs/${job.id}`, { headers: bearer() })).json()).status, { timeout: 60_000 }).toMatch(/CANCELLED|FAILED|SUCCEEDED/);
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.getByText(/Cancelled|Failed|Succeeded/).first()).toBeVisible({ timeout: 20_000 });
    // list filters hit the server with a valid status vocabulary (no 400)
    await gotoApp(page, `/jobs?agentId=${agentId}`);
    const bad: string[] = [];
    page.on("response", (r) => {
      if (r.url().includes("/api/agent-jobs") && r.status() >= 400) bad.push(`${r.status()} ${r.url()}`);
    });
    for (const f of ["All", "Succeeded", "Failed", "Cancelled", "In flight"]) {
      await safeClick(page, page.getByRole("button", { name: f, exact: true }));
      await page.waitForTimeout(500);
    }
    expect(bad, "job list filters must not 4xx").toEqual([]);
    await safeClick(page, page.getByRole("button", { name: "All", exact: true }));
    await expect(page.getByText(`${PREFIX}job ${RUN}`).first()).toBeVisible({ timeout: 15_000 });
  });

  // The stage's core-jobs deployment is not given APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS (tests/load/kind/values-loadtest.yaml
  // sets it under `core.env` only), so every background job against the mock Ollama provider fails with
  // EXECUTION_FAILED "model server URL is not allowed". That is a stage-overlay defect, not a product one.
  test.fixme("/jobs: a job against the mock model SUCCEEDS (blocked: core-jobs lacks APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS on the stage)", async () => {
    const res = await ctx.request.post(`${API}/api/agents/${agentId}/jobs`, { headers: json(), data: { goal: `${PREFIX}ok-run ${RUN}` } });
    const job = await res.json();
    await expect.poll(async () => (await (await ctx.request.get(`${API}/api/agents/${agentId}/jobs/${job.id}`, { headers: bearer() })).json()).status, { timeout: 120_000 }).toBe("SUCCEEDED");
  });

  test("/jobs Schedules tab: create an interval schedule, pause, resume, run now, delete (API-verified)", async () => {
    await gotoApp(page, "/jobs");
    await safeClick(page, page.getByRole("tab", { name: "Schedules" }));
    await page.locator("#sched-agent").selectOption({ value: agentId });
    await expect(page.locator("#sched-goal")).toBeVisible({ timeout: 15_000 });
    // goal is required
    const create = page.getByRole("button", { name: "Create schedule" });
    await page.locator("#sched-goal").fill("");
    await page.locator("#sched-interval").fill("60");
    await expect(create, "blank goal must keep Create schedule disabled").toBeDisabled();
    await page.locator("#sched-goal").fill(`${PREFIX}scheduled brief`);
    const put2 = page.waitForResponse((x) => x.url().endsWith(`/api/agents/${agentId}/schedule`) && x.request().method() === "PUT");
    await create.click();
    expect((await put2).status()).toBeLessThan(300);
    await expect(page.getByText("Enabled", { exact: true }).first()).toBeVisible({ timeout: 15_000 });
    const sched = await (await ctx.request.get(`${API}/api/agents/${agentId}/schedule`, { headers: bearer() })).json();
    expect(sched.enabled).toBe(true);
    expect(sched.intervalSeconds).toBe(3600);
    // pause -> persisted
    await safeClick(page, page.getByRole("button", { name: "Pause" }));
    await expect.poll(async () => (await (await ctx.request.get(`${API}/api/agents/${agentId}/schedule`, { headers: bearer() })).json()).enabled).toBe(false);
    await expect(page.getByText("Paused", { exact: true }).first()).toBeVisible();
    await safeClick(page, page.getByRole("button", { name: "Resume" }));
    await expect.poll(async () => (await (await ctx.request.get(`${API}/api/agents/${agentId}/schedule`, { headers: bearer() })).json()).enabled).toBe(true);
    // schedule survives reload
    await page.reload({ waitUntil: "domcontentloaded" });
    await safeClick(page, page.getByRole("tab", { name: "Schedules" }));
    await page.locator("#sched-agent").selectOption({ value: agentId });
    await expect(page.getByText("Enabled", { exact: true }).first()).toBeVisible({ timeout: 15_000 });
    // run now answers with a run row (SKIPPED is acceptable when the agent is busy)
    const runNow = page.waitForResponse((x) => x.url().endsWith("/schedule/run-now"));
    await safeClick(page, page.getByRole("button", { name: "Run now" }));
    expect((await runNow).status()).toBeLessThan(500);
    // DELETE only DISABLES (AgentScheduleController#disable keeps the configuration), so there is no way to remove a schedule
    // other than deleting the agent - cleanup happens in afterAll.
    const del = await ctx.request.delete(`${API}/api/agents/${agentId}/schedule`, { headers: bearer() });
    expect(del.status()).toBeLessThan(300);
    const after = await (await ctx.request.get(`${API}/api/agents/${agentId}/schedule`, { headers: bearer() })).json();
    expect(after.enabled, "DELETE must disable the schedule").toBe(false);
  });

  test("/jobs Schedules: cron mode rejects an invalid expression with a server-side 4xx (not 5xx)", async () => {
    const res = await ctx.request.put(`${API}/api/agents/${agentId}/schedule`, {
      headers: json(),
      data: { targetType: "AGENT", goalTemplate: `${PREFIX}cron`, cron: "not a cron", timezone: "UTC", enabled: true },
      failOnStatusCode: false,
    });
    expect(res.status()).toBeGreaterThanOrEqual(400);
    expect(res.status()).toBeLessThan(500);
    const tz = await ctx.request.put(`${API}/api/agents/${agentId}/schedule`, {
      headers: json(),
      data: { targetType: "AGENT", goalTemplate: `${PREFIX}tz`, cron: "0 9 * * *", timezone: "Mars/Olympus", enabled: true },
      failOnStatusCode: false,
    });
    expect(tz.status(), "invalid timezone").toBeGreaterThanOrEqual(400);
    expect(tz.status()).toBeLessThan(500);
    await ctx.request.delete(`${API}/api/agents/${agentId}/schedule`, { headers: bearer(), failOnStatusCode: false });
  });

  // ------------------------------------------------------------------ scheduled / coding (feature-flagged off on the stage)

  test("/scheduled and /coding render an honest 'not turned on' state when the deployment flag is off", async () => {
    const sCap = await (await ctx.request.get(`${API}/api/scheduled-tasks/capabilities`, { headers: bearer() })).json();
    const cCap = await (await ctx.request.get(`${API}/api/coding-tasks/capabilities`, { headers: bearer() })).json();
    await gotoApp(page, "/scheduled");
    await expect(page.getByRole("heading", { name: "Scheduled tasks", level: 1 })).toBeVisible({ timeout: 20_000 });
    if (!sCap.enabled) {
      await expect(page.getByText(/Scheduled tasks aren't turned on/)).toBeVisible();
      const r = await ctx.request.get(`${API}/api/scheduled-tasks`, { headers: bearer(), failOnStatusCode: false });
      expect(r.status()).toBe(404);
    } else {
      test.info().annotations.push({ type: "note", description: "scheduled tasks enabled: functional flow not covered" });
    }
    await gotoApp(page, "/coding");
    await expect(page.getByRole("heading", { name: "Coding tasks", level: 1 })).toBeVisible({ timeout: 20_000 });
    if (!cCap.enabled) {
      await expect(page.getByText(/Coding tasks are not switched on/)).toBeVisible();
    }
  });

  test.fixme("/scheduled and /coding functional flows (blocked: scheduled-tasks.enabled=false and coding-tasks.enabled=false on this stage)", async () => {
    /* needs core config scheduled-tasks.enabled and coding-tasks.enabled (+ e2b runner) */
  });

  // ------------------------------------------------------------------ clips / code

  test("/clips: motion controls stay disabled until an image is chosen; uploading an image enables Create clip", async () => {
    await gotoApp(page, "/clips");
    await expect(page.getByRole("heading", { name: "Clip Studio", level: 1 })).toBeVisible({ timeout: 20_000 });
    const create = page.getByRole("button", { name: "Create clip" });
    await expect(create).toBeDisabled();
    await expect(page.getByRole("button", { name: /Zoom in/ })).toBeDisabled();
    // 1x1 PNG, enough for the picker
    const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");
    await page.locator('input[type="file"]').setInputFiles({ name: "e2e-channels-devices.png", mimeType: "image/png", buffer: png });
    await expect(page.getByRole("button", { name: /Zoom in/ })).toBeEnabled({ timeout: 15_000 });
    await expect(create).toBeEnabled();
    // Motion+ tab is reachable
    await safeClick(page, page.getByRole("button", { name: "Motion+" }));
    await safeClick(page, page.getByRole("button", { name: "Still clip" }));
  });

  test("/code (local folder tool): public chrome, explains missing File System Access API, no uploads", async () => {
    const uploads: string[] = [];
    const onReq = (r: import("@playwright/test").Request) => {
      if (r.method() !== "GET" && r.url().startsWith(API) && !r.url().includes("csp-report")) uploads.push(r.method() + " " + r.url());
    };
    page.on("request", onReq);
    await gotoApp(page, "/code");
    await expect(page.getByRole("heading", { name: "Local code", level: 1 })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/never uploaded/i).first()).toBeVisible();
    page.off("request", onReq);
    expect(uploads, "/code must not POST anything to the API").toEqual([]);
  });

  // ------------------------------------------------------------------ efficiency

  test("page efficiency: every page of this area reaches its heading within 8 s and fires <= 40 API calls", async () => {
    const pages: Array<[string, RegExp]> = [
      ["/admin/channels", /Channels/],
      ["/edge", /VegaDūta Edge/],
      ["/jobs", /Jobs/],
      ["/scheduled", /Scheduled tasks/],
      ["/coding", /Coding tasks/],
      ["/clips", /Clip Studio/],
    ];
    const report: string[] = [];
    for (const [path, h1] of pages) {
      let calls = 0;
      const dup = new Map<string, number>();
      const onResp = (r: import("@playwright/test").Response) => {
        if (r.url().startsWith(API) && r.request().method() === "GET") {
          calls++;
          const key = r.url().replace(/\?.*$/, "");
          dup.set(key, (dup.get(key) || 0) + 1);
        }
      };
      page.on("response", onResp);
      const t0 = Date.now();
      await page.goto(`${APP}${path}`, { waitUntil: "domcontentloaded" });
      await expect(page.getByRole("heading", { name: h1, level: 1 })).toBeVisible({ timeout: 30_000 });
      const ms = Date.now() - t0;
      await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
      page.off("response", onResp);
      const dups = [...dup.entries()].filter(([, n]) => n > 1).map(([u, n]) => `${u.replace(API, "")}x${n}`);
      report.push(`${path}: heading ${ms}ms, ${calls} GETs, dup=[${dups.join(",")}]`);
      expect(ms, `${path} time to heading`).toBeLessThan(8_000);
      expect(calls, `${path} GET count`).toBeLessThanOrEqual(40);
    }
    console.log("EFFICIENCY\n" + report.join("\n"));
  });
});
