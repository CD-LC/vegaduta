import { test, expect, type APIRequestContext, type APIResponse } from "@playwright/test";
import { API_URL, requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { fixStageApiBase, skipIfMockLlm } from "./local-stage-fix";
import { signInToConsole, skipIfHostedAllowanceExhausted } from "../helpers/prod-session";
import { getBearerToken, authHeaders, parseChatSse } from "../helpers/prod-api";

/**
 * LLM response evaluation - a rubric judge on the platform's real output.
 *
 * "Does the agent answer well?" is the question every consumer actually
 * asks, and the one no structural spec can answer. This file is a compact
 * eval harness: real prompts through the same /api/agents/{id}/chat surface
 * the console and channel bridges use, scored against deterministic rubric
 * checks - the same style of gate the platform's own EvalGateService applies
 * to version promotion, run here from the consumer side.
 *
 * Rubric axes (each its own test so a regression is attributable):
 *   - INSTRUCTION FOLLOWING: obeyed an exact-format instruction
 *   - GROUNDED TOOL USE: real fetch result integrated, not hallucinated
 *   - CONTEXT RETENTION: multi-turn memory within one session
 *   - ARITHMETIC/FACTUAL: a verifiable answer is exactly right
 *   - OUTPUT HYGIENE: no markers, stack traces, or template leakage
 *   - LATENCY: a turn completes inside a consumer-reasonable window
 *
 * Prompts are small and deterministic; the rubric checks are deliberately
 * conservative (they fail on clear violations, tolerate stylistic variance)
 * so a red here is a real quality regression, not taste.
 */
const CHAT_TIMEOUT = 120_000;

function sendChat(
  request: APIRequestContext,
  token: string,
  agentId: string,
  message: string,
  sessionId?: string
): Promise<APIResponse> {
  return request.post(`${API_URL}/api/agents/${agentId}/chat`, {
    headers: { ...authHeaders(token), "Content-Type": "application/json", Accept: "text/event-stream" },
    data: { message, ...(sessionId ? { sessionId } : {}) },
    failOnStatusCode: false,
    timeout: CHAT_TIMEOUT,
  });
}

test.describe("LLM evaluation - real consumer rubric", () => {
  test.use({ bypassCSP: true }); // stage fix: console calls api.<domain>; its CSP only allows localhost:8280 (see local-stage-fix.ts)
  // NOT serial: Playwright skips every dependent when a serial-group member
  // fails, which turns one rubric miss into a silent hole in the report.
  // Each test seeds its own session state instead - context retention's
  // seed->recall pair lives inside its own test.
  let agentId: string;
  let sessionId: string | undefined;

  test.beforeEach(async ({ page, request }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "burns real tokens - one project only");
    testInfo.setTimeout(300_000);
    await fixStageApiBase(page);
    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);

    const token = await getBearerToken(page);
    const agentsRes = await request.get(`${API_URL}/api/agents`, { headers: authHeaders(token) });
    const agents = (await agentsRes.json()) as Array<{ id: string }>;
    test.skip(!Array.isArray(agents) || agents.length === 0, "tenant has no agents to evaluate");
    agentId = agents[0].id;
    sessionId = undefined;
  });

  test("instruction following: obeys an exact-format reply", async ({ page, request }, testInfo) => {
    skipIfMockLlm(testInfo, "exact-format adherence");
    const token = await getBearerToken(page);
    const res = await sendChat(
      request, token, agentId,
      "List exactly three colors. Format: one per line, each line starting with '- '. No other text."
    );
    expect(res.status()).toBe(200);
    sessionId = res.headers()["x-session-id"] ?? undefined;
    const { replyText, streamErrors } = parseChatSse(await res.text());
    expect(streamErrors).toEqual([]);

    const bulletLines = replyText.split(/\r?\n/).filter((l) => l.trim().startsWith("- "));
    expect(
      bulletLines.length,
      `expected ~3 '- ' bullet lines, got ${bulletLines.length}: ${replyText.slice(0, 300)}`
    ).toBeGreaterThanOrEqual(3);
  });

  test("arithmetic correctness: a verifiable answer is exactly right", async ({ page, request }, testInfo) => {
    skipIfMockLlm(testInfo, "arithmetic correctness");
    const token = await getBearerToken(page);
    const res = await sendChat(request, token, agentId, "What is 17 times 24? Reply with only the number.", sessionId);
    const { replyText, streamErrors } = parseChatSse(await res.text());
    expect(streamErrors).toEqual([]);
    expect(replyText.trim(), "agent got 17*24 wrong - arithmetic regression").toMatch(/^408\b|\b408\b/);
  });

  test("context retention: recalls a detail from earlier in the same session", async ({ page, request }, testInfo) => {
    skipIfMockLlm(testInfo, "session recall of a codename");
    const token = await getBearerToken(page);
    // If the earlier tests did not leave a session (e.g. run standalone),
    // seed one now.
    if (!sessionId) {
      const seed = await sendChat(request, token, agentId, "The codeword is BAKER-99. Reply 'noted'.");
      sessionId = seed.headers()["x-session-id"] ?? undefined;
      await seed.text();
    }
    await sendChat(request, token, agentId, "The project codename is TIDEPOOL-8. Acknowledge with one word.", sessionId).then((r) => r.text());
    const recall = await sendChat(request, token, agentId, "What project codename did I just give you? Reply with only the codename.", sessionId);
    const { replyText } = parseChatSse(await recall.text());
    expect(
      replyText.toUpperCase(),
      "agent could not recall a codename from 1 turn ago in the same session - session memory broken"
    ).toContain("TIDEPOOL-8");
  });

  test("grounded tool use: fetch_web_page result is used, not hallucinated", async ({ page, request }, testInfo) => {
    skipIfMockLlm(testInfo, "grounded fetch_web_page tool use");
    const token = await getBearerToken(page);
    const res = await sendChat(
      request, token, agentId,
      "Use your fetch_web_page tool on https://example.com and tell me the exact h1 heading text.",
      sessionId
    );
    const { replyText, streamErrors } = parseChatSse(await res.text());
    expect(streamErrors).toEqual([]);
    expect(
      replyText,
      "agent did not report example.com's real heading - either no tool call or the result was ignored"
    ).toMatch(/example domain/i);
  });

  test("output hygiene + latency: a turn is clean and lands inside budget", async ({ page, request }) => {
    const token = await getBearerToken(page);
    const started = Date.now();
    const res = await sendChat(request, token, agentId, "Say hello in one short sentence.");
    const body = await res.text();
    const elapsed = Date.now() - started;
    const { replyText, streamErrors, rawDataLines } = parseChatSse(body);

    expect(streamErrors).toEqual([]);
    expect(replyText.trim().length).toBeGreaterThan(0);
    // No internal framing or internals in anything a consumer renders.
    for (const line of rawDataLines) {
      expect(line).not.toMatch(/@@AGENT_(STEP|CHAT_STREAM)_.*@@/);
      expect(line).not.toMatch(/at (org|com|java)\.[a-z.]+\(|SQLException|tenant_id/i);
    }
    expect(replyText).not.toMatch(/\{\s*"(tool|function|name)"\s*:/i); // raw tool-call JSON leaked into text
    // 90s is the prod navigationTimeout's own ceiling; a turn that takes
    // longer reads to a consumer as a hang.
    expect(elapsed, `turn took ${elapsed}ms - over the consumer hang threshold`).toBeLessThan(90_000);
  });
});
