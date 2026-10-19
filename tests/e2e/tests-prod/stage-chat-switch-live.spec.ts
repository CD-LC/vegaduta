import { test, expect, type Page, type BrowserContext, type APIRequestContext, type Route } from "@playwright/test";
import { parseChatSse, parseAgUiSse, STREAM_ERROR_MARKER, STEP_EVENT_MARKER } from "../helpers/prod-api";

/**
 * Chat / switch coverage for the local kind stage (area "chat-switch").
 *
 * Pages: /playground (the agent chat surface; /agent/[shareId] is the public
 * share page and is not covered here), /quick-task, /quick-task/history.
 *
 * Stage notes (why this does not reuse helpers/prod-session.ts):
 *   - The stage serves the console over plain http (NEXT_PUBLIC_* point at
 *     http://api.* / http://auth.*), while prod-env.ts hard-codes https, which
 *     the browser then blocks as mixed content. URLs are built here instead.
 *   - The OSS landing page shows "Sign in" as a LINK to /login, not a header
 *     button, so signInToConsole() times out. The login flow is /login ->
 *     email -> "Continue to secure sign-in" -> Keycloak form.
 *   - vd_kc_session is not written to sessionStorage on this build, so the
 *     bearer token is read from the app's own Authorization header.
 *   - The LLM is the in-cluster mock (every reply is "ok ok ok ..."), so only
 *     wiring, state and UX are asserted, never answer content.
 *
 * Every entity created is prefixed "e2e-chat-switch-" and deleted afterwards.
 * Mutation-gated (E2E_ALLOW_MUTATIONS=1) because it creates two agents and
 * runs chat turns.
 */
const DOMAIN = process.env.E2E_DOMAIN || "127.0.0.1.nip.io";
const SCHEME = process.env.E2E_STAGE_SCHEME || "http";
const APP = `${SCHEME}://app.${DOMAIN}`;
const API = `${SCHEME}://api.${DOMAIN}`;
const EMAIL = process.env.E2E_USER_EMAIL;
const PASSWORD = process.env.E2E_USER_PASSWORD;
const PREFIX = "e2e-chat-switch-";
const RUN = Date.now().toString(36);
const NAME_A = `${PREFIX}agent-a-${RUN}`;
const NAME_B = `${PREFIX}agent-b-${RUN}`;

const SEEN_FLAGS = () => {
  try {
    for (const k of ["agentic_language_choice_seen_v1", "vd_lang_prompt_seen", "vd_agents_guide_seen_v1", "vd_demo_tour_seen_v1", "floatingNav.hintSeen.v1"]) {
      localStorage.setItem(k, "1");
    }
    localStorage.setItem("vd_tour_status", "skipped");
  } catch {
    /* storage blocked */
  }
};

let ctx: BrowserContext;
let page: Page;
let token = "";
let agentA = "";
let agentB = "";
const createdAgents: string[] = [];

const bearer = () => ({ Authorization: `Bearer ${token}` });
const json = () => ({ ...bearer(), "Content-Type": "application/json" });

/** The "Welcome" tour modal re-opens on some navigations and swallows clicks (z-1150 blocker). */
async function clearOverlays(p: Page) {
  for (let i = 0; i < 3; i++) {
    const blocker = p.locator('[data-tour-blocker], [role="dialog"][aria-label="Product tour"]').first();
    if (!(await blocker.isVisible({ timeout: 1_200 }).catch(() => false))) return;
    await p.keyboard.press("Escape");
    await p.waitForTimeout(400);
  }
}

async function gotoApp(p: Page, path: string) {
  await p.goto(`${APP}${path}`, { waitUntil: "domcontentloaded" });
  await clearOverlays(p);
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

async function createAgent(request: APIRequestContext, name: string, model: string): Promise<string> {
  const res = await request.post(`${API}/api/agents`, {
    headers: json(),
    data: { name, systemPrompt: `You are ${name}.`, providerName: "OLLAMA", model, maxTokens: 128 },
  });
  expect(res.status(), `create agent ${name}`).toBeLessThan(300);
  const id = (await res.json()).id as string;
  createdAgents.push(id);
  return id;
}

const chatInput = (p: Page) => p.getByLabel("Ask something…");
const sendBtn = (p: Page) => p.getByRole("button", { name: /^send$/i });
const agentPicker = (p: Page) => p.locator("#pg-agent-select");
const sessionLine = (p: Page) => p.getByText(/^Session: /);
const okReplies = (p: Page) => p.getByText(/ok ok ok/);

async function openPlayground(p: Page, agentId: string) {
  await gotoApp(p, `/playground?agent=${agentId}`);
  await expect(chatInput(p)).toBeVisible({ timeout: 30_000 });
}

/** Click that survives the late-mounting welcome-tour blocker (it can appear seconds after load). */
async function safeClick(p: Page, loc: import("@playwright/test").Locator) {
  for (let i = 0; i < 4; i++) {
    await clearOverlays(p);
    try {
      await loc.click({ timeout: 4_000 });
      return;
    } catch (e) {
      if (i === 3) throw e;
    }
  }
}

async function sendMessage(p: Page, text: string) {
  await chatInput(p).fill(text);
  await safeClick(p, sendBtn(p));
}

async function sessionId(p: Page): Promise<string> {
  const t = await sessionLine(p).innerText();
  return t.replace(/^Session:\s*/, "").trim();
}

async function pickAgent(p: Page, name: string) {
  await safeClick(p, agentPicker(p));
  await safeClick(p, p.getByRole("option", { name: new RegExp(name) }));
}

test.describe.configure({ mode: "serial" });

test.describe("chat-switch (stage): playground chat, agent switching, quick task", () => {
  test.beforeAll(async ({ browser }, testInfo) => {
    test.skip(!EMAIL || !PASSWORD, "E2E_USER_EMAIL / E2E_USER_PASSWORD not set");
    test.skip(process.env.E2E_ALLOW_MUTATIONS !== "1", "creates agents and chat turns - set E2E_ALLOW_MUTATIONS=1");
    testInfo.setTimeout(180_000);
    ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
    await ctx.addInitScript(SEEN_FLAGS);
    page = await ctx.newPage();
    page.on("request", (r) => {
      const a = r.headers()["authorization"];
      if (a && r.url().startsWith(API) && !token) token = a.replace(/^Bearer /, "");
    });
    await signIn(page);
    await gotoApp(page, "/playground");
    await expect.poll(() => token, { timeout: 20_000 }).not.toBe("");
    // The product tour keys are per user (userScoped in TourProvider.tsx); mark them handled so the tour
    // does not re-open over the chat controls.
    const me = await (await ctx.request.get(`${API}/api/me`, { headers: bearer() })).json();
    const uid = me.subject ?? me.id ?? me.userId ?? me.sub;
    if (uid) {
      await ctx.addInitScript((id: string) => {
        try {
          localStorage.setItem(`vd_tour_status:${id}`, "skipped");
          localStorage.setItem(`vd_tour_seen_security:${id}`, "1");
        } catch {
          /* storage blocked */
        }
      }, String(uid));
    }
    agentA = await createAgent(ctx.request, NAME_A, "mock:latest");
    agentB = await createAgent(ctx.request, NAME_B, "mock-b:latest");
  });

  test.afterAll(async () => {
    if (!ctx) return;
    for (const id of createdAgents) {
      await ctx.request.delete(`${API}/api/agents/${id}`, { headers: bearer(), failOnStatusCode: false }).catch(() => {});
    }
    await ctx.close().catch(() => {});
  });

  // ------------------------------------------------------------------ playground

  test("playground renders chat surface for the selected agent; Send is gated on non-blank input", async () => {
    await openPlayground(page, agentA);
    await expect(page.getByRole("heading", { level: 1 }).first()).toBeVisible();
    await expect(agentPicker(page)).toContainText(NAME_A);
    await expect(page.getByText(/Runs on Ollama/)).toContainText("mock:latest");
    await expect(page.getByText("No session yet")).toBeVisible();
    await expect(page.getByText("No messages yet.")).toBeVisible();
    await expect(sendBtn(page)).toBeDisabled();
    await chatInput(page).fill("   ");
    await expect(sendBtn(page), "whitespace-only input must not enable Send").toBeDisabled();
    await chatInput(page).fill("hello");
    await expect(sendBtn(page)).toBeEnabled();
    await chatInput(page).fill("");
    await expect(sendBtn(page)).toBeDisabled();
  });

  test("a turn streams a reply, shows a session id, and the follow-up turn reuses that session", async () => {
    await openPlayground(page, agentA);
    const bodies: Array<Record<string, unknown>> = [];
    const onReq = (r: import("@playwright/test").Request) => {
      if (r.method() === "POST" && /\/api\/agents\/[^/]+\/chat$/.test(r.url())) bodies.push(JSON.parse(r.postData() || "{}"));
    };
    page.on("request", onReq);
    const t0 = Date.now();
    await sendMessage(page, `${PREFIX}turn one`);
    await expect(page.getByText(`${PREFIX}turn one`)).toBeVisible();
    await expect(okReplies(page).first()).toBeVisible({ timeout: 30_000 });
    const firstTurnMs = Date.now() - t0;
    await expect(sessionLine(page)).toBeVisible();
    const sid1 = await sessionId(page);
    expect(sid1).toMatch(/^[0-9a-f-]{36}$/);
    await expect(page.getByText("Sending…")).toHaveCount(0, { timeout: 10_000 });

    await sendMessage(page, `${PREFIX}turn two`);
    await expect(okReplies(page)).toHaveCount(2, { timeout: 30_000 });
    expect(await sessionId(page), "second turn must stay in the same session").toBe(sid1);
    page.off("request", onReq);

    expect(bodies.length).toBe(2);
    expect(bodies[0].sessionId, "first turn must not send a session id").toBeUndefined();
    expect(bodies[1].sessionId, "second turn must send the session id from the first").toBe(sid1);
    test.info().annotations.push({ type: "metric", description: `first-turn UI latency vs mock: ${firstTurnMs}ms` });
    expect(firstTurnMs, "mock replies in ~150ms; a turn over 8s means platform overhead").toBeLessThan(8_000);
  });

  test("Enter in the input submits the turn (keyboard use)", async () => {
    await openPlayground(page, agentA);
    await chatInput(page).fill(`${PREFIX}enter key`);
    await chatInput(page).press("Enter");
    await expect(page.getByText(`${PREFIX}enter key`)).toBeVisible();
    await expect(okReplies(page).first()).toBeVisible({ timeout: 30_000 });
  });

  test("Clear context drops the transcript and the next turn starts a NEW session", async () => {
    await openPlayground(page, agentA);
    await sendMessage(page, `${PREFIX}before clear`);
    await expect(okReplies(page).first()).toBeVisible({ timeout: 30_000 });
    const before = await sessionId(page);
    await safeClick(page, page.getByRole("button", { name: "Clear context" }));
    await expect(page.getByText("No messages yet.")).toBeVisible();
    await expect(page.getByText("No session yet")).toBeVisible();
    const sent: Array<Record<string, unknown>> = [];
    page.on("request", (r) => {
      if (r.method() === "POST" && /\/chat$/.test(r.url())) sent.push(JSON.parse(r.postData() || "{}"));
    });
    await sendMessage(page, `${PREFIX}after clear`);
    await expect(okReplies(page).first()).toBeVisible({ timeout: 30_000 });
    expect(sent[0]?.sessionId, "a cleared chat must not re-send the old session id").toBeUndefined();
    expect(await sessionId(page)).not.toBe(before);
  });

  test("switching agent mid-session resets the transcript/session, targets the new agent, and updates the engine chip + URL", async () => {
    await openPlayground(page, agentA);
    await sendMessage(page, `${PREFIX}on agent a`);
    await expect(okReplies(page).first()).toBeVisible({ timeout: 30_000 });
    const sidA = await sessionId(page);

    await pickAgent(page, NAME_B);
    await expect(page).toHaveURL(new RegExp(`agent=${agentB}`));
    await expect(page.getByText(/Runs on Ollama/)).toContainText("mock-b:latest");
    await expect(page.getByText("No messages yet.")).toBeVisible();
    await expect(page.getByText("No session yet")).toBeVisible();
    await expect(page.getByText(`${PREFIX}on agent a`)).toHaveCount(0);
    await expect(page.getByText(new RegExp(`Chat — ${NAME_B}`))).toBeVisible();

    const reqUrls: string[] = [];
    const bodies: Array<Record<string, unknown>> = [];
    page.on("request", (r) => {
      if (r.method() === "POST" && /\/chat$/.test(r.url())) {
        reqUrls.push(r.url());
        bodies.push(JSON.parse(r.postData() || "{}"));
      }
    });
    await sendMessage(page, `${PREFIX}on agent b`);
    await expect(okReplies(page).first()).toBeVisible({ timeout: 30_000 });
    expect(reqUrls[0]).toContain(`/api/agents/${agentB}/chat`);
    expect(bodies[0].sessionId, "agent A's session id must not leak into agent B").toBeUndefined();
    const sidB = await sessionId(page);
    expect(sidB).not.toBe(sidA);

    // and back again: A's transcript is not magically restored, B's session is not reused
    await pickAgent(page, NAME_A);
    await expect(page).toHaveURL(new RegExp(`agent=${agentA}`));
    await expect(page.getByText(/Runs on Ollama/)).toContainText("mock:latest");
    await expect(page.getByText("No session yet")).toBeVisible();
    await expect(page.getByText(`${PREFIX}on agent b`)).toHaveCount(0);
  });

  test("agent selection survives reload and a direct ?agent= link; unknown id falls back to a real agent", async () => {
    await openPlayground(page, agentB);
    await page.reload();
    await clearOverlays(page);
    await expect(agentPicker(page)).toContainText(NAME_B);
    await expect(page).toHaveURL(new RegExp(`agent=${agentB}`));

    // no ?agent= -> remembered selection (localStorage) restores B
    await gotoApp(page, "/playground");
    await expect(agentPicker(page)).toContainText(NAME_B, { timeout: 15_000 });

    // garbage id must not blank the chat or crash
    await gotoApp(page, "/playground?agent=00000000-0000-0000-0000-000000000000");
    await expect(chatInput(page)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/Application error|client-side exception/i)).toHaveCount(0);
  });

  test("the agent picker lists agents as 'name (provider)' and is keyboard operable", async () => {
    await openPlayground(page, agentA);
    await agentPicker(page).focus();
    await page.keyboard.press("Enter");
    const options = page.getByRole("option");
    await expect(options.first()).toBeVisible();
    const names = await options.allInnerTexts();
    expect(names.some((n) => n.includes(NAME_A) && n.includes("(OLLAMA)"))).toBe(true);
    expect(names.some((n) => n.includes(NAME_B))).toBe(true);
    await page.keyboard.press("Escape");
    await expect(options.first()).toBeHidden();
  });

  test("Cancel on a connecting turn stops it, clears the busy state, and a new turn can be sent", async () => {
    await openPlayground(page, agentA);
    let release: () => void = () => {};
    const gate = new Promise<void>((r) => (release = r));
    let held = 0;
    const handler = async (route: Route) => {
      if (route.request().method() !== "POST") return route.fallback();
      held++;
      await gate; // never fulfilled until the test ends -> UI sits in "Connecting…"
      await route.abort().catch(() => {});
    };
    await page.route(/\/api\/agents\/[^/]+\/chat$/, handler);
    await sendMessage(page, `${PREFIX}cancel me`);
    const cancel = page.getByRole("button", { name: "Cancel" });
    await expect(cancel).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("Connecting…")).toBeVisible();
    await safeClick(page, cancel);
    await expect(cancel).toBeHidden({ timeout: 10_000 });
    await expect(page.getByText("Sending…")).toHaveCount(0);
    expect(held).toBe(1);
    release();
    await page.unroute(/\/api\/agents\/[^/]+\/chat$/, handler);

    await sendMessage(page, `${PREFIX}after cancel`);
    await expect(okReplies(page).first()).toBeVisible({ timeout: 30_000 });
  });

  test("a 500 from the chat endpoint is shown as an error, hides internals, and leaves the input usable", async () => {
    await openPlayground(page, agentA);
    const handler = (route: Route) =>
      route.request().method() === "POST"
        ? route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify({ message: "boom", trace: "org.springframework.Boom at X" }) })
        : route.fallback();
    await page.route(/\/api\/agents\/[^/]+\/chat$/, handler);
    await sendMessage(page, `${PREFIX}force 500`);
    await expect(page.getByRole("alert").first().or(page.getByText(/couldn.t|failed|error|try again/i).first())).toBeVisible({ timeout: 20_000 });
    await page.unroute(/\/api\/agents\/[^/]+\/chat$/, handler);
    await expect(page.getByText("org.springframework")).toHaveCount(0);
    await expect(page.getByText("Sending…")).toHaveCount(0, { timeout: 15_000 });
    await chatInput(page).fill("retry");
    await expect(sendBtn(page)).toBeEnabled();
  });

  test("an in-band stream error marker is rendered as a message, never as raw marker text", async () => {
    await openPlayground(page, agentA);
    const handler = (route: Route) =>
      route.request().method() === "POST"
        ? route.fulfill({
            status: 200,
            headers: { "content-type": "text/event-stream", "x-session-id": "11111111-1111-1111-1111-111111111111", "access-control-expose-headers": "X-Session-Id", "access-control-allow-origin": APP },
            body: `data:partial text\n\ndata:${STREAM_ERROR_MARKER}The model is unavailable right now.\n\n`,
          })
        : route.fallback();
    await page.route(/\/api\/agents\/[^/]+\/chat$/, handler);
    await sendMessage(page, `${PREFIX}stream error`);
    await expect(page.getByText(/model is unavailable|try again|something went wrong/i).first()).toBeVisible({ timeout: 20_000 });
    await page.unroute(/\/api\/agents\/[^/]+\/chat$/, handler);
    await expect(page.locator("body")).not.toContainText("@@AGENT_CHAT_STREAM_ERROR");
    await expect(page.locator("body")).not.toContainText("@@AGENT_STEP_EVENT");
  });

  test("resumable streams: a finished turn is replayable from the server buffer via X-Turn-Id; an unknown turn is a clean 4xx", async () => {
    // The UI half (reload while a reply is streaming) cannot be driven on this stage: the mock LLM answers in ~150ms, and the
    // client only stores the pending turn once the response headers arrive, so there is no window to reload in.
    const r = await ctx.request.post(`${API}/api/agents/${agentA}/chat`, {
      headers: { ...json(), Accept: "text/event-stream", "X-Resumable-Stream": "1" },
      data: { message: `${PREFIX}resume api`, stepEvents: false },
    });
    expect(r.status()).toBe(200);
    const turnId = r.headers()["x-turn-id"];
    // Core default is APP_CHAT_RESUMABLE_STREAMS_ENABLED=false (the stage sets only the redis STORE, not ENABLED), in which
    // case no X-Turn-Id is issued and GET /api/chat/turns/** answers 404 - a config gap, not a code defect.
    test.skip(!turnId, "resumable streams are disabled on this stage (APP_CHAT_RESUMABLE_STREAMS_ENABLED unset => false)");
    const live = parseChatSse(await r.text()).replyText;
    expect(live.length).toBeGreaterThan(0);

    const replay = await ctx.request.get(`${API}/api/chat/turns/${turnId}/stream?after=0`, { headers: { ...bearer(), Accept: "text/event-stream" }, failOnStatusCode: false });
    expect(replay.status(), `resume replay answered ${replay.status()}`).toBe(200);
    const body = await replay.text();
    expect(body, "replayed stream must end with the turn-end frame").toContain("turn-end");
    expect(parseChatSse(body.replace(/event: turn-end[\s\S]*$/, "")).replyText.replace(/\s+/g, "")).toContain(live.replace(/\s+/g, "").slice(0, 10));

    const unknown = await ctx.request.get(`${API}/api/chat/turns/00000000-0000-0000-0000-000000000000/stream?after=0`, { headers: { ...bearer(), Accept: "text/event-stream" }, failOnStatusCode: false });
    expect(unknown.status(), `unknown turn resume answered ${unknown.status()}`).toBeGreaterThanOrEqual(400);
    expect(unknown.status()).toBeLessThan(500);
  });

  test.fixme("a COMPLETED transcript is restored after reload (history)", async () => {
    // DEFECT/behaviour gap (observed 2026-10-14): after a finished 2-turn chat the page reload shows "No messages yet"
    // and "No session yet" although the app still fetches /sessions/{oldSid}/llm-requests and /llm-usage for the old
    // session. Only IN-FLIGHT turns are resumed (ChatPanel.tsx loadPendingTurn). Either restore history or stop
    // calling the per-session endpoints for a session the UI no longer shows.
    await openPlayground(page, agentA);
    await sendMessage(page, `${PREFIX}history`);
    await expect(okReplies(page).first()).toBeVisible({ timeout: 30_000 });
    await page.reload();
    await clearOverlays(page);
    await expect(page.getByText(`${PREFIX}history`)).toBeVisible({ timeout: 10_000 });
  });

  test("Refresh buttons for tool calls and memory work; 'Manage all memory' links to /memory", async () => {
    await openPlayground(page, agentA);
    const refreshes = page.getByRole("button", { name: "Refresh" });
    await expect(refreshes).toHaveCount(2);
    const resp = page.waitForResponse((r) => /\/api\/agents\/[^/]+\/tool-calls|tool-call/.test(r.url()) && r.request().method() === "GET", { timeout: 10_000 }).catch(() => null);
    await refreshes.first().click();
    const r = await resp;
    if (r) expect(r.status()).toBeLessThan(400);
    await refreshes.nth(1).click();
    await expect(page.getByText("Application error")).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Manage all memory" })).toHaveAttribute("href", /\/memory/);
  });

  test("'Try decisions' navigates to /capabilities/decision", async () => {
    await openPlayground(page, agentA);
    await page.getByRole("link", { name: /Try decisions/ }).click();
    await expect(page).toHaveURL(/\/capabilities\/decision/);
  });

  test("?prompt= pre-fills the chat input (without sending)", async () => {
    await gotoApp(page, `/playground?agent=${agentA}&prompt=${encodeURIComponent(PREFIX + "prefill")}`);
    await expect(chatInput(page)).toHaveValue(`${PREFIX}prefill`, { timeout: 15_000 });
    await expect(page.getByText("No messages yet.")).toBeVisible();
  });

  test("playground load makes no failed API calls and no duplicate GETs (efficiency)", async () => {
    const calls: Array<{ status: number; url: string; ms: number }> = [];
    const starts = new Map<string, number>();
    const onReq = (r: import("@playwright/test").Request) => starts.set(r.url() + r.method(), Date.now());
    const onRes = (r: import("@playwright/test").Response) => {
      if (!r.url().startsWith(API)) return;
      calls.push({ status: r.status(), url: r.request().method() + " " + r.url().replace(API, "").split("?")[0], ms: Date.now() - (starts.get(r.url() + r.request().method()) ?? Date.now()) });
    };
    page.on("request", onReq);
    page.on("response", onRes);
    await openPlayground(page, agentA);
    await page.waitForLoadState("networkidle", { timeout: 15_000 }).catch(() => {});
    page.off("request", onReq);
    page.off("response", onRes);
    const failed = calls.filter((c) => c.status >= 400);
    test.info().annotations.push({ type: "metric", description: `api calls=${calls.length} failed=${JSON.stringify(failed)} slowest=${JSON.stringify([...calls].sort((a, b) => b.ms - a.ms).slice(0, 3))}` });
    const counts = new Map<string, number>();
    for (const c of calls) counts.set(c.url, (counts.get(c.url) ?? 0) + 1);
    const dupes = [...counts.entries()].filter(([u, n]) => n > 1 && u.startsWith("GET"));
    test.info().annotations.push({ type: "metric", description: `duplicate GETs=${JSON.stringify(dupes)}` });
    // 404 on /api/browser/takeover/capabilities is a known stage finding; everything else must be clean.
    const unexpected = failed.filter((c) => !c.url.includes("/api/browser/takeover/capabilities"));
    expect(unexpected, "unexpected failed API calls on playground load").toEqual([]);
    expect(calls.every((c) => c.ms < 2_000), `API calls over 2s: ${JSON.stringify(calls.filter((c) => c.ms >= 2_000))}`).toBe(true);
  });

  test.fixme("playground load does not fetch /api/browser/takeover/capabilities that 404s", async () => {
    // DEFECT: every authenticated page load issues GET /api/browser/takeover/capabilities -> 404 (console error
    // "Failed to load resource"). The client already caches the miss in sessionStorage 'vd.missing.browser-takeover',
    // but each new tab/context re-requests it. Gate the call on a tenant-capabilities flag or treat 404 as quiet.
    const failed: string[] = [];
    page.on("response", (r) => {
      if (r.status() === 404 && r.url().includes("/browser/takeover")) failed.push(r.url());
    });
    await openPlayground(page, agentA);
    await page.waitForLoadState("networkidle").catch(() => {});
    expect(failed).toEqual([]);
  });

  // ------------------------------------------------------------------ API contract

  test("API: chat SSE carries X-Session-Id, clean frames, and session reuse via sessionId", async () => {
    const r1 = await ctx.request.post(`${API}/api/agents/${agentA}/chat`, {
      headers: { ...json(), Accept: "text/event-stream" },
      data: { message: `${PREFIX}api one`, stepEvents: false },
    });
    expect(r1.status()).toBe(200);
    expect(r1.headers()["content-type"]).toMatch(/text\/event-stream/);
    const sid = r1.headers()["x-session-id"];
    expect(sid).toMatch(/^[0-9a-f-]{36}$/);
    const p1 = parseChatSse(await r1.text());
    expect(p1.streamErrors).toEqual([]);
    expect(p1.replyText.length).toBeGreaterThan(0);
    expect(p1.replyText).not.toContain(STEP_EVENT_MARKER);
    expect(p1.replyText).not.toContain(STREAM_ERROR_MARKER);

    const r2 = await ctx.request.post(`${API}/api/agents/${agentA}/chat`, {
      headers: { ...json(), Accept: "text/event-stream" },
      data: { message: `${PREFIX}api two`, sessionId: sid, stepEvents: false },
    });
    expect(r2.status()).toBe(200);
    expect(r2.headers()["x-session-id"], "a supplied session id must be echoed, not replaced").toBe(sid);
  });

  test("API: rewind drops tail entries and is idempotent", async () => {
    const chat = (message: string, sessionId?: string) =>
      ctx.request.post(`${API}/api/agents/${agentA}/chat`, { headers: { ...json(), Accept: "text/event-stream" }, data: { message, ...(sessionId ? { sessionId } : {}) } });
    const t1 = await chat(`${PREFIX}rewind one`);
    const sid = t1.headers()["x-session-id"];
    await t1.text();
    await (await chat(`${PREFIX}rewind two`, sid)).text();
    const rw = await ctx.request.post(`${API}/api/agents/${agentA}/chat/sessions/${sid}/rewind`, { headers: json(), data: { keepTurns: 2 }, failOnStatusCode: false });
    expect(rw.status()).toBe(200);
    const body = await rw.json();
    expect(body.sessionId).toBe(sid);
    expect(body.droppedTurns).toBeGreaterThanOrEqual(1);
    const rw2 = await ctx.request.post(`${API}/api/agents/${agentA}/chat/sessions/${sid}/rewind`, { headers: json(), data: { keepTurns: 2 } });
    expect((await rw2.json()).droppedTurns).toBe(0);
  });

  test("API: AG-UI run is sequenced, a finished run replays from the buffer, an unknown run rejoin is an empty 200", async () => {
    const runId = `${PREFIX}run-${RUN}`;
    const run = await ctx.request.post(`${API}/api/agents/${agentA}/agui/run`, {
      headers: { ...json(), Accept: "text/event-stream" },
      data: { runId, threadId: `${PREFIX}thread-${RUN}`, messages: [{ id: "m1", role: "user", content: "hi" }] },
    });
    expect(run.status()).toBe(200);
    const events = parseAgUiSse(await run.text());
    const types = events.map((e) => e.type);
    expect(types).toContain("RUN_STARTED");
    expect(types.some((t) => t === "RUN_FINISHED" || t === "RUN_ERROR")).toBe(true);
    const seqs = events.filter((e) => e.seq !== null).map((e) => e.seq as number);
    for (let i = 1; i < seqs.length; i++) expect(seqs[i]).toBeGreaterThan(seqs[i - 1]);

    const replay = await ctx.request.get(`${API}/api/agents/${agentA}/agui/runs/${runId}/events?after=0`, { headers: { ...bearer(), Accept: "text/event-stream" } });
    expect(replay.status()).toBe(200);
    expect(parseAgUiSse(await replay.text()).map((e) => e.type)).toContain("RUN_STARTED");

    const unknown = await ctx.request.get(`${API}/api/agents/${agentA}/agui/runs/${PREFIX}never-${RUN}/events?after=0`, { headers: { ...bearer(), Accept: "text/event-stream" } });
    expect(unknown.status()).toBe(200);
    expect(parseAgUiSse(await unknown.text())).toHaveLength(0);
  });

  test("API: chat rejects missing auth (401), unknown agent (4xx not 5xx) and an empty message (4xx not 5xx)", async () => {
    const noAuth = await ctx.request.post(`${API}/api/agents/${agentA}/chat`, { headers: { "Content-Type": "application/json" }, data: { message: "x" }, failOnStatusCode: false });
    expect(noAuth.status()).toBe(401);
    const unknown = await ctx.request.post(`${API}/api/agents/00000000-0000-0000-0000-000000000000/chat`, { headers: json(), data: { message: "x" }, failOnStatusCode: false });
    expect(unknown.status(), `unknown agent chat answered ${unknown.status()}`).toBeGreaterThanOrEqual(400);
    expect(unknown.status()).toBeLessThan(500);
    const empty = await ctx.request.post(`${API}/api/agents/${agentA}/chat`, { headers: json(), data: { message: "" }, failOnStatusCode: false });
    expect(empty.status(), `empty message chat answered ${empty.status()}`).toBeGreaterThanOrEqual(400);
    expect(empty.status()).toBeLessThan(500);
    const rewindUnknown = await ctx.request.post(`${API}/api/agents/${agentA}/chat/sessions/00000000-0000-0000-0000-000000000000/rewind`, { headers: json(), data: { keepTurns: 0 }, failOnStatusCode: false });
    expect(rewindUnknown.status(), `rewind of unknown session answered ${rewindUnknown.status()}`).toBeLessThan(500);
  });

  // ------------------------------------------------------------------ quick task

  test("quick task: page renders; Get it done is disabled until a message exists; examples fill the box", async () => {
    await gotoApp(page, "/quick-task");
    const box = page.locator("textarea").first();
    await expect(box).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("button", { name: /Get it done/ })).toBeDisabled();
    await page.getByRole("button", { name: "Summarize this PDF for me" }).click();
    await expect(box).toHaveValue(/Summarize this PDF/);
    await expect(page.getByRole("link", { name: /View task history/ })).toHaveAttribute("href", /quick-task\/history/);
  });

  test("quick task: a too-short message shows no plan; a real one shows the plan card with agent, tools and channels", async () => {
    await gotoApp(page, "/quick-task");
    const box = page.locator("textarea").first();
    await box.fill("hi");
    await page.waitForTimeout(1_200);
    await expect(page.getByText("TASK READY")).toHaveCount(0);
    const preview = page.waitForResponse((r) => r.url().includes("/api/quick-task/preview"), { timeout: 15_000 });
    await box.fill(`${PREFIX}summarize my notes please`);
    const pr = await preview;
    expect(pr.status()).toBe(200);
    await expect(page.getByText(/Here's what .* will do/)).toBeVisible({ timeout: 15_000 });
    for (const ch of ["WhatsApp", "SMS", "Phone call", "Log only"]) await expect(page.getByRole("button", { name: ch })).toBeVisible();
  });

  test("quick task: 'Log only' run completes through confirm, shows a result, and lands in task history", async () => {
    await gotoApp(page, "/quick-task");
    const box = page.locator("textarea").first();
    const msg = `${PREFIX}log only run ${RUN}`;
    await box.fill(msg);
    await expect(page.getByRole("button", { name: "Log only" })).toBeVisible({ timeout: 15_000 });
    await page.getByRole("button", { name: "Log only" }).click();
    const go = page.getByRole("button", { name: /Get it done/ });
    await expect(go).toBeEnabled();
    await go.click();
    // explicit confirmation step restates agent/tools/channel before anything runs
    const confirm = page.getByRole("button", { name: /^(confirm|run|yes|start)/i }).first();
    await expect(confirm).toBeVisible({ timeout: 10_000 });
    const streamResp = page.waitForResponse((r) => r.url().includes("/api/quick-task/stream"), { timeout: 30_000 });
    await confirm.click();
    expect((await streamResp).status()).toBe(200);
    await expect(page.getByText(/ok ok|Done|DONE/).first()).toBeVisible({ timeout: 30_000 });

    await gotoApp(page, "/quick-task/history");
    await expect(page.getByText(msg).first()).toBeVisible({ timeout: 20_000 });
  });

  test("quick task history: rows open a detail log and the page has no failed calls", async () => {
    const bad: string[] = [];
    page.on("response", (r) => {
      if (r.url().startsWith(API) && r.status() >= 400 && !r.url().includes("browser/takeover")) bad.push(`${r.status()} ${r.url()}`);
    });
    await gotoApp(page, "/quick-task/history");
    const row = page.getByText(`${PREFIX}log only run ${RUN}`).first();
    await expect(row).toBeVisible({ timeout: 20_000 });
    await row.click();
    await expect(page.getByText(/tool call|response|request/i).first()).toBeVisible({ timeout: 10_000 });
    expect(bad).toEqual([]);
  });

  // ------------------------------------------------------------------ signed out

  test("signed out: /playground and /quick-task do not expose chat (no cookies, fresh context)", async ({ browser }) => {
    const anon = await browser.newContext();
    const p = await anon.newPage();
    for (const path of ["/playground", "/quick-task"]) {
      await p.goto(`${APP}${path}`, { waitUntil: "domcontentloaded" });
      await p.waitForTimeout(2_500);
      await expect(p.getByLabel("Ask something…")).toHaveCount(0);
      await expect(p.getByRole("button", { name: /Get it done/ })).toHaveCount(0);
    }
    await anon.close();
  });
});
