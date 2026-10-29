import { test, expect, type APIRequestContext } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn, API_URL } from "../helpers/prod-env";
import { signInToConsole, skipIfHostedAllowanceExhausted } from "../helpers/prod-session";
import { getBearerToken, authHeaders, parseChatSse } from "../helpers/prod-api";
import { fixStageApiBase, skipIfMockLlm, LLM_IS_MOCK } from "./local-stage-fix";

/**
 * BEHAVIOR tests for the agent workbench, as an end user would exercise it:
 * create an agent, change its configuration, chat, then read what the agent
 * actually says and assert the OUTPUT reflects the configuration.
 *
 * Deterministic assertions first (markers the prompt demands, line counts,
 * absence of raw JSON / stack traces / framing markers); LLM output is
 * matched tolerantly and each chat retries up to 2 extra times.
 * Every test creates its own uniquely named agent and deletes it afterwards.
 * The agent's provider/model is cloned from an existing tenant agent, so the
 * platform-hosted pool is used and no credentials are involved.
 */
test.describe("Agent workbench - configuration changes are reflected in real output (live)", () => {
  test.setTimeout(4 * 60 * 1000);
  test.use({ bypassCSP: true }); // stage fix: console calls api.<domain>; its CSP only allows localhost:8280 (see local-stage-fix.ts)


  let token = "";
  let template: { providerName: string; model?: string } | null = null;
  const created: string[] = [];

  test.beforeEach(async ({ page, request }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data + burns tokens - one project only");
    await fixStageApiBase(page);
    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    token = await getBearerToken(page);
    const list = await (await request.get(`${API_URL}/api/agents`, { headers: authHeaders(token) })).json();
    template = Array.isArray(list) && list.length ? list[0] : null;
    testInfo.skip(!template, "tenant has no agent to clone a provider/model from");
  });

  test.afterEach(async ({ request }) => {
    while (created.length) {
      const id = created.pop()!;
      await request
        .delete(`${API_URL}/api/agents/${id}`, { headers: authHeaders(token), failOnStatusCode: false })
        .catch(() => {});
    }
  });

  async function createAgent(request: APIRequestContext, systemPrompt: string, extra: Record<string, unknown> = {}) {
    const res = await request.post(`${API_URL}/api/agents`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: {
        name: `E2E WB ${Date.now()}-${Math.floor(Math.random() * 1e4)}`,
        systemPrompt,
        providerName: template!.providerName,
        model: template!.model,
        ...extra,
      },
      failOnStatusCode: false,
    });
    expect(res.status(), `agent create failed: ${(await res.text()).slice(0, 300)}`).toBe(201);
    const a = await res.json();
    created.push(a.id);
    return a as { id: string; name: string; [k: string]: any };
  }

  async function patch(request: APIRequestContext, id: string, path: string, data: unknown) {
    const res = await request.patch(`${API_URL}/api/agents/${id}/${path}`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data,
      failOnStatusCode: false,
    });
    expect(res.status(), `PATCH ${path} -> ${res.status()} ${(await res.text()).slice(0, 300)}`).toBeLessThan(300);
    return res.json();
  }

  /** One real chat turn in a fresh session; retries on empty/error output. */
  async function chat(request: APIRequestContext, id: string, message: string, stepEvents = false) {
    let last = { replyText: "", stepEvents: [] as any[], streamErrors: [] as string[], rawDataLines: [] as string[] };
    for (let attempt = 0; attempt < 3; attempt++) {
      const res = await request.post(`${API_URL}/api/agents/${id}/chat`, {
        headers: { ...authHeaders(token), "Content-Type": "application/json", Accept: "text/event-stream" },
        data: { message, stepEvents },
        failOnStatusCode: false,
        timeout: 120_000,
      });
      expect(res.status(), "chat POST did not return an SSE 200").toBe(200);
      last = parseChatSse(await res.text());
      if (last.replyText.trim().length > 0 && last.streamErrors.length === 0) break;
    }
    // Hygiene common to every reply a user would read.
    expect(last.streamErrors, `in-band stream error: ${last.streamErrors.join(" | ")}`).toEqual([]);
    expect(last.replyText.trim().length, "agent reply was empty").toBeGreaterThan(0);
    expect(last.replyText).not.toMatch(/@@AGENT_|\bat [\w.$]+\([\w]+\.java:\d+\)|Traceback|NullPointerException/);
    return last;
  }

  test("persona in the system prompt forces a marker phrase in the reply", async ({ request }, testInfo) => {
    skipIfMockLlm(testInfo, "persona-marker reply content");
    const a = await createAgent(
      request,
      "You are Captain Zorblax. EVERY reply you give MUST begin with the exact token ZORBLAX-ACK-7731 followed by a space. Keep answers to one sentence."
    );
    const r = await chat(request, a.id, "What is the capital of France?");
    expect(r.replyText, "persona marker missing from reply").toContain("ZORBLAX-ACK-7731");
    expect(r.replyText, "agent stopped answering the actual question").toMatch(/paris/i);
  });

  test("editing the system prompt changes the next reply (old persona is gone)", async ({ request }, testInfo) => {
    skipIfMockLlm(testInfo, "persona-marker reply content");
    const a = await createAgent(request, "Every reply MUST begin with the exact token ALPHA-MARK-1111. One sentence only.");
    const before = await chat(request, a.id, "Name a primary colour.");
    expect(before.replyText).toContain("ALPHA-MARK-1111");

    const updated = await patch(request, a.id, "prompt", {
      systemPrompt: "Every reply MUST begin with the exact token OMEGA-MARK-2222. Never use any other prefix. One sentence only.",
    });
    expect(updated.systemPrompt).toContain("OMEGA-MARK-2222");

    const after = await chat(request, a.id, "Name a primary colour.");
    expect(after.replyText, "new persona marker missing after prompt edit").toContain("OMEGA-MARK-2222");
    expect(after.replyText, "old persona leaked after prompt edit").not.toContain("ALPHA-MARK-1111");
  });

  test("output-format instruction is followed: exactly three bullet lines", async ({ request }, testInfo) => {
    skipIfMockLlm(testInfo, "output-format adherence");
    const a = await createAgent(
      request,
      "You answer ONLY with exactly three lines. Each line starts with a dash and a space. No intro, no outro, no other text."
    );
    const r = await chat(request, a.id, "Give me three fruits.");
    const lines = r.replyText.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
    expect(lines.length, `expected 3 lines, got: ${JSON.stringify(lines)}`).toBe(3);
    for (const l of lines) expect(l).toMatch(/^[-*•]\s+\S/);
  });

  test("output-language instruction is followed (Hindi Devanagari reply)", async ({ request }, testInfo) => {
    skipIfMockLlm(testInfo, "output-language adherence");
    const a = await createAgent(
      request,
      "Always reply ONLY in Hindi written in Devanagari script, regardless of the language the user writes in. Never use English words except proper nouns."
    );
    const r = await chat(request, a.id, "Tell me one fact about the sun.");
    const deva = (r.replyText.match(/[ऀ-ॿ]/g) || []).length;
    const latin = (r.replyText.match(/[A-Za-z]/g) || []).length;
    expect(deva, `reply not in Devanagari: ${r.replyText.slice(0, 200)}`).toBeGreaterThan(10);
    expect(deva, "reply is mostly Latin script").toBeGreaterThan(latin);
  });

  test("generation params: tiny maxTokens truncates output; raising it allows a long answer", async ({ request }, testInfo) => {
    const a = await createAgent(request, "You are a verbose storyteller. Always write long, detailed answers.");
    await patch(request, a.id, "generation-params", { maxTokens: 24, temperature: 0.2, topP: 1 });
    // The mock always returns 20 tokens whatever maxTokens is; persistence is still checked.
    if (LLM_IS_MOCK) {
      const cfg = await patch(request, a.id, "generation-params", { maxTokens: 1500, temperature: 0.2, topP: 1 });
      expect(cfg.maxTokens).toBe(1500);
      testInfo.skip(true, "E2E_LLM_MOCK=1: the stage LLM is the fixed 'ok ok ...' mock (always 20 tokens), so output-length effects of maxTokens need a real model");
    }
    const short = await chat(request, a.id, "Tell me a long story about a dragon and a knight, at least 300 words.");
    const shortWords = short.replyText.trim().split(/\s+/).length;
    expect(shortWords, `maxTokens=24 but reply had ${shortWords} words`).toBeLessThan(45);

    const cfg = await patch(request, a.id, "generation-params", { maxTokens: 1500, temperature: 0.2, topP: 1 });
    expect(cfg.maxTokens).toBe(1500);
    const long = await chat(request, a.id, "Tell me a long story about a dragon and a knight, at least 300 words.");
    const longWords = long.replyText.trim().split(/\s+/).length;
    expect(longWords, `maxTokens=1500 but reply only ${longWords} words`).toBeGreaterThan(shortWords * 2);
  });

  test("temperature 0 + stop sequence: reply is cut at the stop sequence", async ({ request }, testInfo) => {
    skipIfMockLlm(testInfo, "stop-sequence handling");
    const a = await createAgent(request, "Reply with exactly: ONE TWO STOP THREE FOUR. Nothing else.");
    await patch(request, a.id, "generation-params", {
      maxTokens: 200,
      temperature: 0,
      topP: 1,
      stopSequences: ["STOP"],
    });
    const r = await chat(request, a.id, "Go.");
    expect(r.replyText, "reply lost the text before the stop sequence").toMatch(/ONE\s+TWO/);
    expect(r.replyText, "text after the stop sequence was still emitted").not.toMatch(/THREE|FOUR/);
  });

  test("tool allow-list: restricted agent makes no fetch_web_page call; unrestricted agent does", async ({ request }, testInfo) => {
    const a = await createAgent(
      request,
      "You are a research assistant. When asked about a URL, use your tools to fetch it and quote exact heading text."
    );
    const toolsRes = await request.get(`${API_URL}/api/agents/${a.id}/available-tools`, { headers: authHeaders(token) });
    expect(toolsRes.ok(), "available-tools endpoint failed").toBeTruthy();
    const tools = (await toolsRes.json()) as Array<{ name?: string }>;
    const names = tools.map((t) => t.name).filter((n): n is string => !!n);
    test.skip(!names.includes("fetch_web_page"), "fetch_web_page not offered to this tenant");
    const other = names.find((n) => n !== "fetch_web_page");
    test.skip(!other, "no second tool available to build a restrictive allow-list");

    await patch(request, a.id, "tool-access", { allowedToolNames: [other] });
    const off = await chat(request, a.id, "Fetch https://example.com and report the exact heading text on that page.", true);
    expect(JSON.stringify(off.stepEvents), "restricted agent still called fetch_web_page").not.toContain("fetch_web_page");
    // A tools-off agent must not fabricate having fetched the page.
    expect(off.replyText, "restricted agent claims a successful fetch it could not make").not.toMatch(
      /(i (have )?(fetched|retrieved|visited)|the (page|heading) (says|reads|is)).*example domain/i
    );

    await patch(request, a.id, "tool-access", { allowedToolNames: [] }); // clears restriction
    // The mock never emits tool calls; the restricted-agent half above already ran.
    skipIfMockLlm(testInfo, "an unrestricted agent actually calling fetch_web_page");
    const on = await chat(request, a.id, "Fetch https://example.com and report the exact heading text on that page.", true);
    expect(JSON.stringify(on.stepEvents), "unrestricted agent never used fetch_web_page").toContain("fetch_web_page");
    expect(on.replyText, "agent did not report the real fetched heading").toMatch(/example domain/i);
  });

  test("reasoning depth: every depth persists and Careful/Rigorous still answer correctly", async ({ request }) => {
    const a = await createAgent(request, "You are a precise arithmetic assistant. State the final number clearly.");
    for (const depth of ["CAREFUL", "RIGOROUS"]) {
      const cfg = await patch(request, a.id, "reasoning-depth", { reasoningDepth: depth });
      expect(String(cfg.reasoningDepth).toUpperCase(), `depth ${depth} did not persist`).toBe(depth);
      const r = await chat(request, a.id, "What is 17 multiplied by 23? Give the final number.");
      if (LLM_IS_MOCK) expect(r.replyText, `depth ${depth} did not complete a reply`).toMatch(/^(ok ?)+$/);
      else expect(r.replyText, `depth ${depth} gave wrong arithmetic`).toMatch(/391/);
    }
    const back = await patch(request, a.id, "reasoning-depth", { reasoningDepth: "FAST" });
    expect(String(back.reasoningDepth).toUpperCase()).toBe("FAST");
  });

  test("workbench UI: a freshly created agent's persona shows up in the Playground reply", async ({ page, request }) => {
    const a = await createAgent(
      request,
      "Every reply MUST contain the exact token UIPERSONA-9042. Keep it to one short sentence."
    );
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await page.locator('button[aria-haspopup="listbox"][data-tour="create-agent"]').click();
    const option = page.getByText(a.name, { exact: false }).first();
    await expect(option, "new agent missing from the workbench agent switcher").toBeVisible({ timeout: 30_000 });
    await option.click();
    await expect(page.getByText(a.name).first()).toBeVisible({ timeout: 30_000 });

    const message = page.getByPlaceholder(/send a test message/i);
    await expect(message).toBeVisible({ timeout: 30_000 });
    await message.fill("Say hello.");
    await page.locator('button[aria-label="Send"][type="submit"]').click();
    await expect(
      page.getByText(/UIPERSONA-9042/).nth(0),
      "persona token never appeared in the Playground reply"
    ).toBeVisible({ timeout: 90_000 });
    const body = await page.locator("body").innerText();
    expect(body, "raw framing marker leaked into the UI").not.toMatch(/@@AGENT_/);
  });
});
