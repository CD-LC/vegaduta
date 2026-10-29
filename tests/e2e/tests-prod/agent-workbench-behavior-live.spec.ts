import { test, expect, type APIRequestContext } from "@playwright/test";
import { requireUserCredentials, requireMutationOptIn, skipIfMockLlm, LLM_IS_MOCK, API_URL } from "../helpers/prod-env";
import { signInToConsole, skipIfHostedAllowanceExhausted } from "../helpers/prod-session";
import { getBearerToken, authHeaders, parseChatSse } from "../helpers/prod-api";

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

  let token = "";
  let template: { providerName: string; model?: string } | null = null;
  const created: string[] = [];

  test.beforeEach(async ({ page, request }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data + burns tokens - one project only");
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
    let last = {
      replyText: "",
      stepEvents: [] as any[],
      streamErrors: [] as string[],
      rawDataLines: [] as string[],
      sessionId: "",
    };
    for (let attempt = 0; attempt < 3; attempt++) {
      const res = await request.post(`${API_URL}/api/agents/${id}/chat`, {
        headers: { ...authHeaders(token), "Content-Type": "application/json", Accept: "text/event-stream" },
        data: { message, stepEvents },
        failOnStatusCode: false,
        timeout: 120_000,
      });
      expect(res.status(), "chat POST did not return an SSE 200").toBe(200);
      last = { ...parseChatSse(await res.text()), sessionId: res.headers()["x-session-id"] ?? "" };
      if (last.replyText.trim().length > 0 && last.streamErrors.length === 0) break;
    }
    // Hygiene common to every reply a user would read.
    expect(last.streamErrors, `in-band stream error: ${last.streamErrors.join(" | ")}`).toEqual([]);
    expect(last.replyText.trim().length, "agent reply was empty").toBeGreaterThan(0);
    expect(last.replyText).not.toMatch(/@@AGENT_|\bat [\w.$]+\([\w]+\.java:\d+\)|Traceback|NullPointerException/);
    return last;
  }

  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

  interface SentOptions {
    maxTokens?: number;
    stopSequences?: string[];
  }

  /**
   * The sampling options of the LAST model call in a chat session, from the
   * request inspector (`GET .../llm-requests` - the same endpoint the
   * Playground's "what was sent" panel reads; the snapshot is taken by
   * LlmMetricsAdvisor, the last point before the provider adapter). This is
   * the proof a generation param actually reached the provider (#292/#293).
   * Null when the store has nothing for the session (small write-queue delay
   * is polled out; a missing endpoint or empty store just means the check is
   * not available, not that the cap failed).
   */
  async function lastSentOptions(
    request: APIRequestContext,
    agentId: string,
    sessionId: string
  ): Promise<SentOptions | null> {
    if (!sessionId) return null;
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
      const res = await request.get(`${API_URL}/api/agents/${agentId}/sessions/${sessionId}/llm-requests`, {
        headers: authHeaders(token),
        failOnStatusCode: false,
      });
      if (res.ok()) {
        const list = (await res.json()) as Array<{ options?: SentOptions }>;
        if (list.length) return list[list.length - 1].options ?? null;
      } else {
        return null;
      }
      await sleep(800);
    }
    return null;
  }

  /**
   * Highest provider-reported completion-token count among the session's calls
   * (`GET .../llm-usage` - what the model actually counted, reasoning tokens
   * included). Null when not readable here: FREE tenants get 403 (per-call
   * usage is a PREMIUM+ surface) and an empty session returns [].
   */
  async function sessionCompletionTokens(
    request: APIRequestContext,
    agentId: string,
    sessionId: string
  ): Promise<number | null> {
    if (!sessionId) return null;
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
      const res = await request.get(`${API_URL}/api/agents/${agentId}/sessions/${sessionId}/llm-usage`, {
        headers: authHeaders(token),
        failOnStatusCode: false,
      });
      if (res.status() === 403) return null;
      if (res.ok()) {
        const calls = (await res.json()) as Array<{ completionTokens?: number | null }>;
        const counted = calls.map((c) => c.completionTokens).filter((n): n is number => typeof n === "number");
        if (counted.length) return Math.max(...counted);
      }
      await sleep(800);
    }
    return null;
  }

  /**
   * True when the cloned model is a reasoning ("thinking") family - qwen3.x,
   * QwQ, deepseek-r1/v3.1, the OpenAI o-series, gpt-5. Those can spend
   * maxTokens in a hidden thinking block and carry marker text there, so the
   * VISIBLE reply is not a reliable measure of what the provider counted or
   * where it stopped - assert provider truth (sent options + usage) instead.
   */
  function isReasoningModel(): boolean {
    const model = String(template?.model ?? "");
    return /(qwen3|qwq|deepseek-(r1|v3\.1)|\bo[0-9]+(-|\b)|gpt-5|magistral|reasoning|thinking)/i.test(model);
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
    const a = await createAgent(
      request,
      "Every reply MUST begin with the exact token ALPHA-MARK-1111. One sentence only."
    );
    const before = await chat(request, a.id, "Name a primary colour.");
    expect(before.replyText).toContain("ALPHA-MARK-1111");

    const updated = await patch(request, a.id, "prompt", {
      systemPrompt:
        "Every reply MUST begin with the exact token OMEGA-MARK-2222. Never use any other prefix. One sentence only.",
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
    const lines = r.replyText
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean);
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

  test("generation params: tiny maxTokens truncates output; raising it allows a long answer", async ({
    request,
  }, testInfo) => {
    const a = await createAgent(request, "You are a verbose storyteller. Always write long, detailed answers.");
    await patch(request, a.id, "generation-params", { maxTokens: 24, temperature: 0.2, topP: 1 });
    // The mock always returns 20 tokens whatever maxTokens is; persistence is still checked.
    if (LLM_IS_MOCK) {
      const cfg = await patch(request, a.id, "generation-params", { maxTokens: 1500, temperature: 0.2, topP: 1 });
      expect(cfg.maxTokens).toBe(1500);
      testInfo.skip(
        true,
        "E2E_LLM_MOCK=1: the stage LLM is the fixed 'ok ok ...' mock (always 20 tokens), so output-length effects of maxTokens need a real model"
      );
    }
    const short = await chat(request, a.id, "Tell me a long story about a dragon and a knight, at least 300 words.");

    // Provider truth, not visible words (#292): the request inspector shows the
    // cap reaching the model call and llm-usage shows what the provider counted.
    // A reasoning model can burn the whole cap in a hidden thinking block, so a
    // long visible reply is not proof the cap was ignored.
    const sent = await lastSentOptions(request, a.id, short.sessionId);
    if (sent) expect(sent.maxTokens, "maxTokens=24 did not reach the model call").toBe(24);
    const shortCompletion = await sessionCompletionTokens(request, a.id, short.sessionId);
    const shortWords = short.replyText.trim().split(/\s+/).length;
    if (shortCompletion != null) {
      expect(
        shortCompletion,
        `provider reported ${shortCompletion} completion tokens under maxTokens=24`
      ).toBeLessThanOrEqual(24);
    } else {
      testInfo.skip(
        isReasoningModel(),
        `${template?.model} is a reasoning model: it can spend maxTokens in a hidden thinking block and ` +
          "per-call llm-usage is not visible on this plan, so visible reply length cannot measure the cap"
      );
      expect(shortWords, `maxTokens=24 but reply had ${shortWords} words`).toBeLessThan(45);
    }

    const cfg = await patch(request, a.id, "generation-params", { maxTokens: 1500, temperature: 0.2, topP: 1 });
    expect(cfg.maxTokens).toBe(1500);
    const long = await chat(request, a.id, "Tell me a long story about a dragon and a knight, at least 300 words.");
    const longCompletion = await sessionCompletionTokens(request, a.id, long.sessionId);
    if (longCompletion != null) {
      expect(
        longCompletion,
        `maxTokens=1500 but provider reported only ${longCompletion} completion tokens`
      ).toBeGreaterThan(shortCompletion ?? 24);
    } else if (!isReasoningModel()) {
      const longWords = long.replyText.trim().split(/\s+/).length;
      expect(longWords, `maxTokens=1500 but reply only ${longWords} words`).toBeGreaterThan(shortWords * 2);
    }
    // Reasoning model + no usage visibility: a longer VISIBLE reply cannot be
    // trusted as evidence (#292) - the sent-options check above is the wiring proof.
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
    // Wiring first (#293): the stop list has to reach the model call itself
    // before any visible-output assertion means anything.
    const sent = await lastSentOptions(request, a.id, r.sessionId);
    if (sent) {
      expect(sent.stopSequences ?? [], "stop sequence 'STOP' did not reach the model call").toContain("STOP");
    }
    expect(r.replyText, "reply lost the text before the stop sequence").toMatch(/ONE\s+TWO/);
    const pastMarker = /THREE|FOUR/.test(r.replyText);
    testInfo.skip(
      pastMarker && isReasoningModel(),
      `${template?.model} emitted text past 'STOP' but is a reasoning model: the marker can sit inside a ` +
        "hidden thinking block and some OpenAI-compat surfaces ignore `stop` there, so visible " +
        "truncation cannot be asserted - the sent-options check above verified the platform's side"
    );
    expect(r.replyText, "text after the stop sequence was still emitted").not.toMatch(/THREE|FOUR/);
  });

  test("tool allow-list: restricted agent makes no fetch_web_page call; unrestricted agent does", async ({
    request,
  }, testInfo) => {
    const a = await createAgent(
      request,
      "You are a research assistant. When asked about a URL, use your tools to fetch it and quote exact heading text."
    );
    const toolsRes = await request.get(`${API_URL}/api/agents/${a.id}/available-tools`, {
      headers: authHeaders(token),
    });
    expect(toolsRes.ok(), "available-tools endpoint failed").toBeTruthy();
    const tools = (await toolsRes.json()) as Array<{ name?: string }>;
    const names = tools.map((t) => t.name).filter((n): n is string => !!n);
    test.skip(!names.includes("fetch_web_page"), "fetch_web_page not offered to this tenant");
    const other = names.find((n) => n !== "fetch_web_page");
    test.skip(!other, "no second tool available to build a restrictive allow-list");

    await patch(request, a.id, "tool-access", { allowedToolNames: [other] });
    const off = await chat(
      request,
      a.id,
      "Fetch https://example.com and report the exact heading text on that page.",
      true
    );
    expect(JSON.stringify(off.stepEvents), "restricted agent still called fetch_web_page").not.toContain(
      "fetch_web_page"
    );
    // A tools-off agent must not fabricate having fetched the page.
    expect(off.replyText, "restricted agent claims a successful fetch it could not make").not.toMatch(
      /(i (have )?(fetched|retrieved|visited)|the (page|heading) (says|reads|is)).*example domain/i
    );

    await patch(request, a.id, "tool-access", { allowedToolNames: [] }); // clears restriction
    // The mock never emits tool calls; the restricted-agent half above already ran.
    skipIfMockLlm(testInfo, "an unrestricted agent actually calling fetch_web_page");
    const on = await chat(
      request,
      a.id,
      "Fetch https://example.com and report the exact heading text on that page.",
      true
    );
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

  test("workbench UI: a freshly created agent's persona shows up in the Playground reply", async ({
    page,
    request,
  }) => {
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
