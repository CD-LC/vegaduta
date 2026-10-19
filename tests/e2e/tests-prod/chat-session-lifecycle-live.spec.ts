import { test, expect } from "@playwright/test";
import { API_URL, requireUserCredentials, requireMutationOptIn } from "../helpers/prod-env";
import { signInToConsole, skipIfHostedAllowanceExhausted } from "../helpers/prod-session";
import { getBearerToken, authHeaders, parseChatSse, parseAgUiSse, STEP_EVENT_MARKER, STREAM_ERROR_MARKER } from "../helpers/prod-api";

/**
 * Chat session lifecycle - REAL turns against production.
 *
 * Covers three surfaces that shipped with zero e2e coverage:
 *
 *   1. POST /api/agents/{id}/chat (SSE) - the wire contract a real consumer
 *      (the console, the WhatsApp/Telegram bridges, any API client) depends
 *      on: data:-only frames, X-Session-Id header, marker hygiene (a
 *      STEP_EVENT/STREAM_ERROR marker must never land in rendered text).
 *
 *   2. POST .../chat/sessions/{sid}/rewind (session rewind / time-travel,
 *      1ee88d89) - contract: {sessionId, keepTurns, droppedTurns}; rewinding
 *      to 0 must actually drop history, and re-rewinding must be idempotent
 *      (a second rewind reports droppedTurns=0). The sharp edge: folding a
 *      rewound turn back into the rolling summary would resurrect it in every
 *      future prompt - this spec asks the rewind about the dropped content.
 *
 *   3. AG-UI run + rejoin (e8e8d30c) - POST /agui/run emits sequenced SSE
 *      (id: per frame), then GET /agui/runs/{runId}/events?after=N replays
 *      the durable buffer. Rejoin is the fix for "the SSE connection dropped
 *      and the turn's events are gone"; a buffer that loses events or skips
 *      seq numbers silently breaks every reconnecting client.
 *
 * All of these burn real hosted-pool tokens, so the file is mutation-gated.
 * The prompts are deliberately tiny ("Reply with exactly: PONG") - the point
 * is the transport and lifecycle contract, not the prose.
 */
test.describe("Chat SSE contract - real turn", () => {
  test.beforeEach(async ({ page }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "burns real tokens - one project only");
    testInfo.setTimeout(180_000);
    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);
  });

  test("a real chat turn streams clean tokens, carries X-Session-Id, and leaks no markers", async ({ page, request }) => {
    const token = await getBearerToken(page);
    const agentsRes = await request.get(`${API_URL}/api/agents`, { headers: authHeaders(token) });
    const agents = await agentsRes.json();
    test.skip(!Array.isArray(agents) || agents.length === 0, "tenant has no agents to chat with");
    const agentId = agents[0].id;

    const res = await request.post(`${API_URL}/api/agents/${agentId}/chat`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json", Accept: "text/event-stream" },
      data: { message: "Reply with exactly the single word PONG and nothing else.", stepEvents: false },
      failOnStatusCode: false,
      timeout: 120_000,
    });
    expect(res.status(), `chat POST returned ${res.status()} - expected a 200 SSE stream`).toBe(200);
    expect(res.headers()["content-type"], "chat response is not an SSE stream").toMatch(/text\/event-stream/);

    const sessionId = res.headers()["x-session-id"];
    expect(sessionId, "X-Session-Id response header missing - clients cannot correlate or rewind this session").toBeTruthy();

    const parsed = parseChatSse(await res.text());

    // The turn must produce a real reply - not an in-band error, not silence.
    expect(parsed.streamErrors, `stream carried an error: ${parsed.streamErrors.join(" | ")}`).toEqual([]);
    expect(parsed.replyText.length, "streamed reply was empty").toBeGreaterThan(0);
    expect(parsed.replyText.toUpperCase(), "agent did not follow a one-word instruction").toContain("PONG");

    // Marker hygiene: internal framing markers must never reach rendered text.
    expect(parsed.replyText).not.toContain(STEP_EVENT_MARKER);
    expect(parsed.replyText).not.toContain(STREAM_ERROR_MARKER);
    expect(parsed.replyText).not.toMatch(/Exception|stack trace|org\.springframework/i);
  });
});

test.describe("Session rewind - real session, real history", () => {
  test("rewind drops tail turns and is idempotent", async ({ page, request }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "burns real tokens - one project only");
    testInfo.setTimeout(240_000);
    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);

    const token = await getBearerToken(page);
    const agents = await (await request.get(`${API_URL}/api/agents`, { headers: authHeaders(token) })).json();
    test.skip(!Array.isArray(agents) || agents.length === 0, "tenant has no agents");
    const agentId = agents[0].id;

    // Build a two-turn session so there is something to rewind.
    const chat = (message: string, sessionId?: string) =>
      request.post(`${API_URL}/api/agents/${agentId}/chat`, {
        headers: { ...authHeaders(token), "Content-Type": "application/json", Accept: "text/event-stream" },
        data: { message, ...(sessionId ? { sessionId } : {}) },
        timeout: 120_000,
      });

    const t1 = await chat("Remember this codeword for later: ORANGE-FALCON-7. Just reply 'noted'.");
    expect(t1.status()).toBe(200);
    const sessionId = t1.headers()["x-session-id"];
    expect(sessionId, "first turn returned no X-Session-Id").toBeTruthy();
    await t1.text(); // drain

    const t2 = await chat("Ignore that for now. What is 2+2? Answer with just the number.", sessionId);
    expect(t2.status()).toBe(200);
    await t2.text();

    // Rewind to before turn 2: keepTurns counts HISTORY ENTRIES (a user msg
    // and the assistant reply are separate entries) - keepTurns=2 keeps the
    // first exchange and drops the second.
    const rewind = await request.post(`${API_URL}/api/agents/${agentId}/chat/sessions/${sessionId}/rewind`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: { keepTurns: 2 },
      failOnStatusCode: false,
    });
    expect(rewind.status(), `rewind returned ${rewind.status()}`).toBe(200);
    const rw = await rewind.json();
    expect(rw.sessionId).toBe(sessionId);
    expect(rw.droppedTurns, "rewind reported dropping nothing on a 2-turn session").toBeGreaterThanOrEqual(1);

    // Idempotence: rewinding again to the same point must report 0 dropped -
    // a rewind that re-drops already-dropped turns means the history index is
    // drifting (the resurrected-turn bug in a different costume).
    const rewind2 = await request.post(`${API_URL}/api/agents/${agentId}/chat/sessions/${sessionId}/rewind`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json" },
      data: { keepTurns: 2 },
    });
    const rw2 = await rewind2.json();
    expect(rw2.droppedTurns, "second rewind dropped MORE turns - history did not settle at keepTurns=2").toBe(0);

    // The dropped turn must not leak back: ask for the arithmetic answer -
    // if the rewound "2+2" turn were still in context the reply would treat
    // it as established fact; a rewound session has never seen it.
    const t3 = await chat("In THIS conversation, have I already asked you to calculate 2+2? Answer yes or no only.", sessionId);
    const parsed3 = parseChatSse(await t3.text());
    expect(parsed3.streamErrors).toEqual([]);
    expect(
      parsed3.replyText.trim().toLowerCase(),
      "agent claimed to remember a rewound turn - rewind did not actually remove history (or it leaked via the rolling summary)"
    ).toMatch(/^no\b/);
    // But the kept turn's codeword SHOULD survive - rewind is a scalpel, not
    // a wipe: the first exchange stays in memory.
    const t4 = await chat("What was the codeword? One token answer.", sessionId);
    const parsed4 = parseChatSse(await t4.text());
    expect(
      parsed4.replyText.toUpperCase(),
      "agent lost the KEPT turn's content after rewind - rewind wiped too much"
    ).toContain("ORANGE-FALCON-7");
  });
});

test.describe("AG-UI run stream + rejoin - real run", () => {
  test("a run emits sequenced SSE and a finished run replays from the durable buffer", async ({ page, request }, testInfo) => {
    requireUserCredentials(testInfo);
    requireMutationOptIn(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "burns real tokens - one project only");
    testInfo.setTimeout(240_000);
    await signInToConsole(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await skipIfHostedAllowanceExhausted(page, testInfo);

    const token = await getBearerToken(page);
    const agents = await (await request.get(`${API_URL}/api/agents`, { headers: authHeaders(token) })).json();
    test.skip(!Array.isArray(agents) || agents.length === 0, "tenant has no agents");
    const agentId = agents[0].id;

    // The agent's server-side tools as AG-UI Tool objects - a cheap VIEWER-
    // gated contract check before burning a run.
    const tools = await request.get(`${API_URL}/api/agents/${agentId}/agui/tools`, { headers: authHeaders(token) });
    expect(tools.status(), "/agui/tools must answer 200 for a tenant member").toBe(200);
    expect(Array.isArray(await tools.json())).toBe(true);

    const runId = `e2e-rejoin-${Date.now()}`;
    const run = await request.post(`${API_URL}/api/agents/${agentId}/agui/run`, {
      headers: { ...authHeaders(token), "Content-Type": "application/json", Accept: "text/event-stream" },
      data: {
        runId,
        threadId: `e2e-thread-${Date.now()}`,
        messages: [{ id: "m1", role: "user", content: "Reply with exactly: READY" }],
      },
      failOnStatusCode: false,
      timeout: 120_000,
    });
    expect(run.status(), `agui run returned ${run.status()}`).toBe(200);
    const events = parseAgUiSse(await run.text());
    const types = events.map((e) => e.type).filter(Boolean);

    expect(types, "run emitted no RUN_STARTED").toContain("RUN_STARTED");
    expect(
      types.some((t) => t === "RUN_FINISHED" || t === "RUN_ERROR"),
      `run never terminated - saw only: ${types.join(",")}`
    ).toBe(true);

    // Sequence numbers are the rejoin contract: every mapped event carries a
    // positive, strictly-increasing id: - a gap or reset silently corrupts
    // any Last-Event-ID resume.
    const seqs = events.filter((e) => e.seq !== null).map((e) => e.seq as number);
    expect(seqs.length, "no event carried a sequence id - rejoin cannot work").toBeGreaterThan(0);
    for (let i = 1; i < seqs.length; i++) {
      expect(seqs[i] > seqs[i - 1], `event seq went backwards (${seqs[i - 1]} -> ${seqs[i]})`).toBe(true);
    }

    // Rejoin: a finished run replays its buffered history. after=0 must
    // return the whole stream; after=<last seq> must return nothing but a
    // clean end (the "not on this pod / window passed" honest answer).
    const replay = await request.get(`${API_URL}/api/agents/${agentId}/agui/runs/${runId}/events?after=0`, {
      headers: { ...authHeaders(token), Accept: "text/event-stream" },
      failOnStatusCode: false,
      timeout: 60_000,
    });
    expect(replay.status(), `rejoin answered ${replay.status()}`).toBe(200);
    const replayed = parseAgUiSse(await replay.text());
    const replayTypes = replayed.map((e) => e.type).filter(Boolean);
    expect(
      replayTypes.some((t) => t === "RUN_FINISHED" || t === "RUN_ERROR"),
      `rejoin replay did not include the run's terminal event - buffered history is incomplete (${replayTypes.join(",")})`
    ).toBe(true);

    const lastSeq = seqs[seqs.length - 1];
    const tail = await request.get(`${API_URL}/api/agents/${agentId}/agui/runs/${runId}/events?after=${lastSeq}`, {
      headers: { ...authHeaders(token), Accept: "text/event-stream" },
      failOnStatusCode: false,
      timeout: 60_000,
    });
    const tailEvents = parseAgUiSse(await tail.text());
    expect(
      tailEvents.filter((e) => (e.seq ?? 0) > lastSeq).length,
      "rejoin with after=<lastSeq> replayed events the client already had - resume position is off"
    ).toBe(0);
  });

  test("rejoining an unknown run id returns an empty stream, not an error", async ({ page, request }, testInfo) => {
    requireUserCredentials(testInfo);
    testInfo.skip(testInfo.project.name !== "chromium", "API-level - one project only");
    testInfo.setTimeout(120_000);
    await signInToConsole(page);

    const token = await getBearerToken(page);
    const agents = await (await request.get(`${API_URL}/api/agents`, { headers: authHeaders(token) })).json();
    test.skip(!Array.isArray(agents) || agents.length === 0, "tenant has no agents");

    const res = await request.get(
      `${API_URL}/api/agents/${agents[0].id}/agui/runs/never-existed-${Date.now()}/events?after=0`,
      { headers: { ...authHeaders(token), Accept: "text/event-stream" }, failOnStatusCode: false, timeout: 60_000 }
    );
    // A run id the buffer has never seen: the honest answer is an empty 200
    // stream (client polls again later) - a 500 or a stack trace here is a
    // real defect in the not-found path.
    expect(res.status(), `unknown-run rejoin answered ${res.status()}`).toBe(200);
    expect(parseAgUiSse(await res.text()).length).toBe(0);
  });
});
