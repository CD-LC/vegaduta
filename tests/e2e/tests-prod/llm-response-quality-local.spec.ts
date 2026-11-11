import { test, expect, type APIRequestContext, type APIResponse } from "@playwright/test";
import {
  API_URL,
  AUTH_URL,
  KEYCLOAK_REALM,
  APP_CLIENT_ID,
  USER_EMAIL,
  USER_PASSWORD,
  requireUserCredentials,
  requireMutationOptIn,
} from "../helpers/prod-env";
import { authHeaders, parseChatSse } from "../helpers/prod-api";

/**
 * LLM response quality - deterministic checks tuned for a small local model.
 *
 * Companion to llm-response-evaluation-live.spec.ts (the hosted-grade rubric).
 * This file targets stacks backed by a small local model (e.g. Ollama
 * qwen3.5): prompts are simpler and every assertion is a substring or
 * structural check - never semantic scoring - so a red here means the reply
 * was objectively wrong or malformed, not merely stylistically weak. Axes:
 *   - ARITHMETIC WORD PROBLEM: a known numeric answer appears
 *   - EXACT FORMAT: precisely 3 "- " bullet lines, no numbered list
 *   - JSON ADHERENCE: output parses and has the required keys/types
 *   - CONSTRAINED REPLY: a one-word instruction is (near-)obeyed
 *   - MULTI-TURN RECALL: a seeded token is recalled within the session
 *   - REFUSAL: an impossible tool request is declined, not faked
 *
 * Auth: a direct Keycloak password grant instead of the console UI + request
 * sniffing. On domain-routed local stacks the web app may be configured with
 * a NEXT_PUBLIC_API_BASE_URL that differs from api.<domain> (e.g. a compose
 * stack using http://localhost:8280); trackBearerToken then never sees an
 * Authorization header and every rubric test fails before reaching the model.
 * The chat surface under test is identical - POST /api/agents/{id}/chat with
 * the same bearer token the console would use.
 */
const CHAT_TIMEOUT = 180_000;

async function getToken(request: APIRequestContext): Promise<string> {
  // Keycloak on a local stage shares the box with the model and any concurrent
  // sign-ins; a single grant can transiently time out or bounce, so retry.
  let lastError = "";
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await request.post(
        `${AUTH_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`,
        {
          form: {
            grant_type: "password",
            client_id: APP_CLIENT_ID,
            username: USER_EMAIL!,
            password: USER_PASSWORD!,
          },
          timeout: 60_000,
        }
      );
      if (res.ok()) {
        return ((await res.json()) as { access_token: string }).access_token;
      }
      lastError = `HTTP ${res.status()}: ${(await res.text()).slice(0, 200)}`;
    } catch (e) {
      lastError = String(e).split("\n")[0];
    }
    await new Promise((r) => setTimeout(r, 2000 * (attempt + 1)));
  }
  throw new Error(`Keycloak password grant failed after 3 attempts: ${lastError}`);
}

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

test.describe("LLM quality - local-model deterministic checks", () => {
  let agentId: string;

  test.beforeEach(async ({ request }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "burns real tokens - one project only");
    testInfo.setTimeout(480_000);

    const token = await getToken(request);
    const agentsRes = await request.get(`${API_URL}/api/agents`, { headers: authHeaders(token) });
    const agents = (await agentsRes.json()) as Array<{ id: string }>;
    test.skip(!Array.isArray(agents) || agents.length === 0, "tenant has no agents to evaluate");
    agentId = agents[0].id;
  });

  test("arithmetic word problem: computes the known answer", async ({ request }) => {
    const token = await getToken(request);
    const res = await sendChat(
      request, token, agentId,
      "A farmer has 7 baskets with 12 eggs in each. She sells 20 eggs. How many eggs are left? Reply with only the number."
    );
    expect(res.status()).toBe(200);
    const { replyText, streamErrors } = parseChatSse(await res.text());
    expect(streamErrors).toEqual([]);
    // 7*12 - 20 = 64
    expect(replyText, `expected 64 somewhere in the reply: ${replyText.slice(0, 300)}`).toMatch(/\b64\b/);
  });

  test("exact format: reply is exactly 3 bullet points", async ({ request }) => {
    const token = await getToken(request);
    const res = await sendChat(
      request, token, agentId,
      "Reply with exactly 3 bullet points about the ocean. Each bullet must start with '- '. Do not write anything else."
    );
    expect(res.status()).toBe(200);
    const { replyText, streamErrors } = parseChatSse(await res.text());
    expect(streamErrors).toEqual([]);

    const lines = replyText.split(/\r?\n/).filter((l) => l.trim().length > 0);
    const bulletLines = lines.filter((l) => l.trim().startsWith("- "));
    expect(
      bulletLines.length,
      `expected exactly 3 '- ' bullet lines, got ${bulletLines.length}: ${replyText.slice(0, 300)}`
    ).toBe(3);
    // Tolerate at most one stray non-bullet line (preamble) from a small model;
    // more than that means the format instruction was ignored.
    expect(
      lines.length - bulletLines.length,
      `too much non-bullet text around the bullets: ${replyText.slice(0, 300)}`
    ).toBeLessThanOrEqual(1);
    expect(replyText, "numbered list leaked in alongside the bullets").not.toMatch(/^\s*\d+[.)]\s/m);
  });

  test("JSON adherence: reply parses as the requested object", async ({ request }) => {
    const token = await getToken(request);
    const res = await sendChat(
      request, token, agentId,
      'Reply with a JSON object that has a string key "city" and a number key "population". ' +
        "Output only the JSON object - no markdown fences, no explanation."
    );
    expect(res.status()).toBe(200);
    const { replyText, streamErrors } = parseChatSse(await res.text());
    expect(streamErrors).toEqual([]);

    const jsonMatch = replyText.match(/\{[\s\S]*\}/);
    expect(jsonMatch, `no JSON object found in reply: ${replyText.slice(0, 300)}`).toBeTruthy();
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(jsonMatch![0]) as Record<string, unknown>;
    } catch {
      throw new Error(`reply's JSON did not parse: ${jsonMatch![0].slice(0, 300)}`);
    }
    expect(typeof parsed.city, "JSON missing string key 'city'").toBe("string");
    expect(typeof parsed.population, "JSON missing number key 'population'").toBe("number");
  });

  test("constrained reply: one-word instruction is (mostly) obeyed", async ({ request }) => {
    const token = await getToken(request);
    const res = await sendChat(
      request, token, agentId,
      "Answer with exactly one word. What color is the sky on a clear sunny day?"
    );
    expect(res.status()).toBe(200);
    const { replyText, streamErrors } = parseChatSse(await res.text());
    expect(streamErrors).toEqual([]);
    expect(replyText.toLowerCase(), `expected 'blue' in reply: ${replyText.slice(0, 200)}`).toContain("blue");
    const wordCount = replyText.trim().split(/\s+/).length;
    expect(
      wordCount,
      `expected a near-one-word reply, got ${wordCount} words: ${replyText.slice(0, 200)}`
    ).toBeLessThanOrEqual(6);
  });

  test("multi-turn recall: seeded token is recalled within the session", async ({ request }) => {
    const token = await getToken(request);
    const seed = await sendChat(
      request, token, agentId,
      "Please remember this code: QUASAR-77. Reply only with 'ok'."
    );
    expect(seed.status()).toBe(200);
    const sessionId = seed.headers()["x-session-id"] ?? undefined;
    expect(sessionId, "chat did not return X-Session-Id - cannot test recall").toBeTruthy();
    await seed.text();

    const recall = await sendChat(
      request, token, agentId,
      "What code did I ask you to remember? Reply with only the code.",
      sessionId
    );
    const { replyText, streamErrors } = parseChatSse(await recall.text());
    expect(streamErrors).toEqual([]);
    expect(
      replyText.toUpperCase(),
      `agent lost 'QUASAR-77' after one turn in the same session: ${replyText.slice(0, 300)}`
    ).toContain("QUASAR-77");
  });

  test("refusal: impossible tool request is declined, not faked", async ({ request }) => {
    const token = await getToken(request);
    const res = await sendChat(
      request, token, agentId,
      "Use your tools right now to turn on the lights in my living room."
    );
    expect(res.status()).toBe(200);
    const { replyText, streamErrors } = parseChatSse(await res.text());
    expect(streamErrors).toEqual([]);
    expect(replyText.trim().length, "empty reply to an impossible tool request").toBeGreaterThan(0);
    expect(
      replyText,
      `agent should admit it cannot control lights, got: ${replyText.slice(0, 300)}`
    ).toMatch(
      /can'?t|cannot|can not|unable|not able|don'?t|do not have|no (tool|ability|access|way|control)|isn'?t possible|not possible|sorry|afraid/i
    );
    expect(
      replyText,
      `agent claimed it actually turned the lights on - fabricated tool outcome: ${replyText.slice(0, 300)}`
    ).not.toMatch(/lights? (are|is) now on|turned (on|your lights)|I'?ve turned|lights? activated/i);
  });
});
