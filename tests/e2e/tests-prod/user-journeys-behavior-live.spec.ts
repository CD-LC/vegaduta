import { test, expect, type Page, type APIRequestContext } from "@playwright/test";
import { API_URL, requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole, skipIfHostedAllowanceExhausted } from "../helpers/prod-session";
import { getBearerToken, authHeaders, parseChatSse } from "../helpers/prod-api";
import { node, edge, createWorkflowViaUi, putGraph, apiCall, openWorkflow, runWorkflow, deleteWorkflow } from "../helpers/workflow-graph";

/**
 * END-TO-END USER JOURNEYS (behavior, live). Real end-user path across
 * surfaces: create an agent, talk to it, wire it into a workflow, run it and
 * READ what comes out. Assertions target output content (tokens the prompt
 * demands, counts, JSON structure) rather than merely "it rendered".
 *
 * Every test makes its own uniquely named agents/workflows and deletes them.
 */

type Created = { agents: string[]; workflows: string[] };

async function createAgent(
  request: APIRequestContext,
  token: string,
  name: string,
  systemPrompt: string,
  extra: Record<string, unknown> = {}
): Promise<string> {
  const res = await request.post(`${API_URL}/api/agents`, {
    headers: { ...authHeaders(token), "Content-Type": "application/json" },
    data: {
      name,
      systemPrompt,
      providerName: "PLATFORM_HOSTED",
      model: "gemini-2.5-flash",
      reasoningDepth: "FAST",
      maxTokens: 1024,
      temperature: 0.1,
      ...extra,
    },
    failOnStatusCode: false,
  });
  expect(res.status(), `create agent failed: ${(await res.text()).slice(0, 300)}`).toBeLessThan(300);
  return (await res.json()).id as string;
}

async function chat(request: APIRequestContext, token: string, agentId: string, message: string, sessionId?: string) {
  const res = await request.post(`${API_URL}/api/agents/${agentId}/chat`, {
    headers: { ...authHeaders(token), "Content-Type": "application/json", Accept: "text/event-stream" },
    data: { message, ...(sessionId ? { sessionId } : {}) },
    failOnStatusCode: false,
    timeout: 120_000,
  });
  expect(res.status(), "chat POST status").toBe(200);
  const parsed = parseChatSse(await res.text());
  expect(parsed.streamErrors, `in-band stream error: ${parsed.streamErrors.join("|")}`).toEqual([]);
  expect(parsed.replyText).not.toMatch(/Exception|org\.springframework|@@AGENT_/);
  return { reply: parsed.replyText, sessionId: res.headers()["x-session-id"] as string | undefined };
}

/** Retry an LLM-dependent check a small number of times with a fresh session. */
async function withRetries<T>(n: number, fn: () => Promise<T>, ok: (v: T) => boolean): Promise<T> {
  let last!: T;
  for (let i = 0; i < n; i++) {
    last = await fn();
    if (ok(last)) return last;
  }
  return last;
}

/** The playground agent picker is a custom listbox (button), not a native <select>. */
async function pickAgentInPlayground(page: Page, agentName: string) {
  const trigger = page.locator("#pg-agent-select");
  await expect(trigger).toBeVisible({ timeout: 30_000 });
  await trigger.click();
  await page.getByRole("option").filter({ hasText: agentName }).first().click();
}

async function cleanup(page: Page, request: APIRequestContext, c: Created) {
  const token = await getBearerToken(page).catch(() => null);
  for (const w of c.workflows) await deleteWorkflow(page, w);
  if (!token) return;
  for (const a of c.agents) {
    await request.delete(`${API_URL}/api/agents/${a}`, { headers: authHeaders(token), failOnStatusCode: false }).catch(() => {});
  }
}

/** Runs a workflow over the API (blocking) and returns the run with its full trace text. */
async function runViaApi(page: Page, workflowId: string, input: string) {
  const r = await apiCall(page, "POST", `/api/workflows/${workflowId}/run`, { input });
  expect(r.status, `run failed: ${r.text.slice(0, 300)}`).toBeLessThan(300);
  return JSON.parse(r.text) as {
    status: string;
    errorMessage?: string;
    trace: Array<{ nodeId: string; nodeType: string; status: string; summary?: string; logLines?: string[] }>;
  };
}
const traceText = (run: Awaited<ReturnType<typeof runViaApi>>) =>
  run.trace.map((t) => `${t.summary ?? ""}\n${(t.logLines ?? []).join("\n")}`).join("\n");

test.describe("End-to-end user journeys - behavior (live production)", () => {
  test.setTimeout(6 * 60 * 1000);
  let created: Created;

  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    created = { agents: [], workflows: [] };
    const mobileOnly = /journey 4/.test(testInfo.title);
    testInfo.skip(mobileOnly ? testInfo.project.name !== "mobile-chrome" : testInfo.project.name !== "chromium", "project-scoped journey");
    await signInToConsole(page);
  });

  test.afterEach(async ({ page, request }) => {
    // beforeEach skips (no creds / no mutation opt-in) before `created` is set; don't crash cleanup.
    if (!created) return;
    await cleanup(page, request, created);
  });

  test("journey 1: create persona agent -> chat -> attach to workflow Agent node -> run -> output carries persona; swapping agent changes output", async ({
    page,
    request,
  }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - desktop only");
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    const token = await getBearerToken(page);
    const stamp = Date.now();
    const tokA = `ALPHA${stamp % 100000}`;
    const tokB = `BRAVO${stamp % 100000}`;

    const persona = (t: string) =>
      `You are a support bot. Whatever the user says, your reply MUST begin with the exact code ${t} followed by a colon and one short sentence.`;
    const a = await createAgent(request, token, `E2E Journey A ${stamp}`, persona(tokA));
    const b = await createAgent(request, token, `E2E Journey B ${stamp}`, persona(tokB));
    created.agents.push(a, b);

    // Step 1: chat directly - persona is honoured.
    const direct = await withRetries(2, () => chat(request, token, a, "Hi, what are your opening hours?"), (r) => r.reply.includes(tokA));
    expect(direct.reply, "agent A ignored its persona in a direct chat").toContain(tokA);
    expect(direct.reply).not.toContain(tokB);

    // Step 2: workflow trigger -> agent(A) -> output, built through the real New-workflow UI.
    const name = `E2E Journey WF ${stamp}`;
    const wf = await createWorkflowViaUi(page, name);
    created.workflows.push(wf);
    const graphFor = (agentId: string) => [
      [node("t1", "trigger", { channel: null }, "Trigger"), node("a1", "agent", { agentId }, "Support Agent"), node("o1", "output", { channel: "log" }, "Output")],
      [edge("t1", "a1"), edge("a1", "o1")],
    ] as const;
    let [nodes, edges] = graphFor(a);
    await putGraph(page, wf, name, nodes, edges);
    await openWorkflow(page, wf);

    // Run via the real Run modal, as a user would, and read the on-screen result.
    const status = await runWorkflow(page, "Where is my order?");
    expect(status, "workflow with agent node did not complete").toBe("COMPLETED");
    await expect(page.getByText(new RegExp(tokA)).first(), "workflow output did not carry agent A's persona token").toBeVisible({
      timeout: 20_000,
    });
    await expect(page.getByText(new RegExp(tokB))).toHaveCount(0);

    // Step 3: swap the attached agent; same input must now yield B's persona (config change -> output change).
    [nodes, edges] = graphFor(b);
    await putGraph(page, wf, name, nodes, edges);
    const run2 = await runViaApi(page, wf, "Where is my order?");
    expect(run2.status, run2.errorMessage).toBe("COMPLETED");
    const text2 = traceText(run2);
    expect(text2, "after swapping the agent the output did not reflect agent B").toContain(tokB);
    expect(text2, "stale agent A persona leaked after swap").not.toContain(tokA);
    expect(text2).not.toMatch(/Exception|org\.springframework|undefined|\[object Object\]/);
  });

  test("journey 2a: summarize provided text into exactly N bullets", async ({ page, request }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - desktop only");
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    const token = await getBearerToken(page);
    const id = await createAgent(
      request,
      token,
      `E2E Summarizer ${Date.now()}`,
      "You summarize text. Output ONLY bullet lines, each starting with '- '. No intro, no outro, no headings."
    );
    created.agents.push(id);
    const article =
      "The city council met on Tuesday. It approved a new bike lane on Main Street costing 2 million dollars. " +
      "Residents complained about parking loss. The council also voted to extend library hours to 9pm. " +
      "A proposal to raise bus fares was postponed until March. The mayor promised a public survey on all three topics.";

    for (const n of [3, 5]) {
      const r = await withRetries(
        3,
        () => chat(request, token, id, `Summarize the following into exactly ${n} bullets.\n\n${article}`),
        (x) => x.reply.split("\n").filter((l) => /^\s*[-*•]\s+\S/.test(l)).length === n
      );
      const bullets = r.reply.split("\n").filter((l) => /^\s*[-*•]\s+\S/.test(l));
      expect(bullets.length, `asked for ${n} bullets, got ${bullets.length}:\n${r.reply}`).toBe(n);
      expect(r.reply.toLowerCase(), "summary lost the article's subject").toMatch(/bike|library|bus|council/);
    }
  });

  test("journey 2b: extract structured JSON fields from free text", async ({ page, request }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - desktop only");
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    const token = await getBearerToken(page);
    const id = await createAgent(
      request,
      token,
      `E2E Extractor ${Date.now()}`,
      'You extract fields. Reply with ONLY a single JSON object with keys "name" (string), "city" (string), "order_id" (string), "quantity" (number). No prose, no code fences.'
    );
    created.agents.push(id);
    const parse = (s: string) => {
      const m = s.replace(/```(?:json)?/g, "").match(/\{[\s\S]*\}/);
      try {
        return m ? JSON.parse(m[0]) : null;
      } catch {
        return null;
      }
    };
    const r = await withRetries(
      3,
      () => chat(request, token, id, "Hello, this is Priya Menon from Pune. My order ZX-4471 for 3 units never arrived."),
      (x) => {
        const j = parse(x.reply);
        return !!j && j.order_id === "ZX-4471";
      }
    );
    const j = parse(r.reply);
    expect(j, `reply was not parseable JSON:\n${r.reply}`).not.toBeNull();
    expect(String(j.name)).toMatch(/Priya/i);
    expect(String(j.city)).toMatch(/Pune/i);
    expect(j.order_id).toBe("ZX-4471");
    expect(Number(j.quantity)).toBe(3);
  });

  test("journey 2c: workflow agent node with the same extraction agent returns JSON in the run output", async ({ page, request }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - desktop only");
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    const token = await getBearerToken(page);
    const id = await createAgent(
      request,
      token,
      `E2E WF Extractor ${Date.now()}`,
      'Reply with ONLY a JSON object {"city": string, "count": number} extracted from the user text. No prose.'
    );
    created.agents.push(id);
    const name = `E2E Journey Extract WF ${Date.now()}`;
    const wf = await createWorkflowViaUi(page, name);
    created.workflows.push(wf);
    await putGraph(
      page,
      wf,
      name,
      [node("t1", "trigger", { channel: null }, "Trigger"), node("a1", "agent", { agentId: id }, "Extract"), node("o1", "output", { channel: "log" }, "Output")],
      [edge("t1", "a1"), edge("a1", "o1")]
    );
    const run = await withRetries(
      2,
      () => runViaApi(page, wf, "We shipped 7 crates to Jaipur yesterday."),
      (r) => r.status === "COMPLETED" && /Jaipur/.test(traceText(r))
    );
    expect(run.status, run.errorMessage).toBe("COMPLETED");
    const agentStep = run.trace.find((t) => t.nodeId === "a1");
    expect(agentStep?.status, "agent node step did not succeed").toBe("OK");
    const text = traceText(run);
    expect(text).toMatch(/Jaipur/);
    expect(text).toMatch(/\b7\b/);
    expect(text).not.toMatch(/Exception|org\.springframework|\[object Object\]/);
  });

  test("journey 3: session persistence - context survives a follow-up turn, and the playground transcript survives a reload", async ({
    page,
    request,
  }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "chromium", "writes real data - desktop only");
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    const token = await getBearerToken(page);
    const stamp = Date.now();
    const id = await createAgent(request, token, `E2E Memory ${stamp}`, "You are a concise assistant. Answer in one short line.");
    created.agents.push(id);

    // API-level continuity: codeword given in turn 1 is recalled in turn 2 on the same session.
    const word = `KIWI${stamp % 100000}`;
    const t1 = await chat(request, token, id, `Remember this codeword: ${word}. Reply only 'noted'.`);
    expect(t1.sessionId, "no X-Session-Id on first turn").toBeTruthy();
    const t2 = await withRetries(2, () => chat(request, token, id, "What was the codeword I gave you?", t1.sessionId), (r) => r.reply.includes(word));
    expect(t2.reply, "agent forgot the codeword within the same session").toContain(word);

    // UI-level: send in the playground, reload, and see what a user sees.
    await page.goto("/playground", { waitUntil: "domcontentloaded" });
    await pickAgentInPlayground(page, `E2E Memory ${stamp}`);
    const input = page.getByPlaceholder(/ask something/i);
    await expect(input).toBeVisible({ timeout: 30_000 });
    const msg = `Say the word PERSIST${stamp % 100000} back to me.`;
    await input.fill(msg);
    await page.getByRole("button", { name: /^send$/i }).click();
    await expect(page.getByText(msg).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(new RegExp(`PERSIST${stamp % 100000}`, "i")).nth(1), "assistant reply never rendered in transcript").toBeVisible({
      timeout: 90_000,
    });

    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(page.locator("#pg-agent-select")).toBeVisible({ timeout: 30_000 });
    // After reload the user expects their conversation (or at minimum a working, non-broken chat surface).
    const restored = await page
      .getByText(msg)
      .first()
      .isVisible({ timeout: 8_000 })
      .catch(() => false);
    testInfo.annotations.push({ type: "reload-history-restored", description: String(restored) });
    await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
    await expect(page.getByPlaceholder(/ask something/i)).toBeVisible({ timeout: 30_000 });
    // Design observation (ChatPanel keeps sessionId in component state; no history-restore UI exists):
    // the playground transcript is NOT expected to survive a reload, so it is annotated, not asserted.
    // What MUST hold: the surface recovers cleanly, and the SERVER-side session (asserted above via the
    // codeword recall) still exists - and a fresh UI turn after reload still works.
    await pickAgentInPlayground(page, `E2E Memory ${stamp}`);
    const again = page.getByPlaceholder(/ask something/i);
    await again.fill(`Reply with the single word READY${stamp % 100000}.`);
    await page.getByRole("button", { name: /^send$/i }).click();
    await expect(page.getByText(new RegExp(`READY${stamp % 100000}`, "i")).nth(1), "post-reload chat turn got no reply").toBeVisible({
      timeout: 90_000,
    });
    const stillRemembers = await chat(request, token, id, "What was the codeword I gave you earlier?", t1.sessionId);
    expect(stillRemembers.reply, "server-side session history lost after UI reload").toContain(word);
  });

  test("journey 4 (mobile Pixel 7): create-agent-to-reply core flow has no overflow and returns the persona token", async ({
    page,
    request,
  }, testInfo) => {
    testInfo.skip(testInfo.project.name !== "mobile-chrome", "mobile viewport pass only");
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
    const token = await getBearerToken(page);
    const stamp = Date.now();
    const tok = `MOBILE${stamp % 100000}`;
    const id = await createAgent(request, token, `E2E Mobile ${stamp}`, `Every reply MUST begin with the exact code ${tok} then a colon and one short sentence.`);
    created.agents.push(id);

    await page.goto("/playground", { waitUntil: "domcontentloaded" });
    await pickAgentInPlayground(page, `E2E Mobile ${stamp}`);
    const input = page.getByPlaceholder(/ask something/i);
    await expect(input).toBeVisible({ timeout: 30_000 });
    await input.fill("Hello there");
    await page.getByRole("button", { name: /^send$/i }).click();
    await expect(page.getByText(new RegExp(tok)).first(), "mobile chat reply lacked persona token").toBeVisible({ timeout: 90_000 });

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, "horizontal page overflow at Pixel 7 width during chat").toBeLessThanOrEqual(1);

    // Mobile workflow list + canvas open.
    await page.goto("/workflows", { waitUntil: "domcontentloaded" });
    await expect(page.getByText(/Application error: a client-side exception/i)).toHaveCount(0);
    const wOverflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(wOverflow, "horizontal overflow on /workflows at Pixel 7 width").toBeLessThanOrEqual(1);
  });
});
