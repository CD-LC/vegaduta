import { test, expect, type Page, type APIRequestContext } from "@playwright/test";
import { APP_URL, API_URL, USER_EMAIL, USER_PASSWORD } from "../helpers/prod-env";
import { signInToConsole, expandReasoningAutonomySection } from "../helpers/prod-session";

/**
 * A2A interoperability console (merged PR #282) - live verification.
 *
 * Covers what no existing spec does:
 *   1. The /admin/a2a console page: inbound-endpoints card, outbound-policy
 *      card, and the cross-direction A2A task table.
 *   2. The API surface the page rides on (/api/a2a/console/tasks|policy,
 *      /.well-known/agent-card.json|jwks.json, /api/directory/agents),
 *      including that both console endpoints 401 without a token.
 *   3. The workbench "Reasoning & autonomy" section's "Callable by other
 *      agents (A2A)" endpoint panel for a saved agent.
 *   4. A real inbound call: JSON-RPC message/send to /api/agents/{id}/a2a
 *      runs an agent turn and the resulting task shows up in the console
 *      list (mutation-gated, the agent is deleted afterwards).
 *
 * The token is captured off the page's own authenticated requests, but wider
 * than helpers/prod-api.ts's API_URL filter: this stack's web image is built
 * with NEXT_PUBLIC_API_URL=http://localhost:8280, so restricting the capture
 * to api.<domain> would never see a token here.
 */
const bearer = { current: "" };
function trackAnyApiBearer(page: Page) {
  page.on("request", (r) => {
    const a = r.headers()["authorization"];
    if (a && /^bearer /i.test(a) && r.url().includes("/api/")) {
      bearer.current = a.replace(/^bearer /i, "");
    }
  });
}
const auth = () => ({ Authorization: `Bearer ${bearer.current}` });

async function signInAndGetToken(page: Page) {
  trackAnyApiBearer(page);
  await signInToConsole(page);
  await expect
    .poll(() => bearer.current, { timeout: 30_000, message: "no bearer token seen on any /api/ request" })
    .not.toBe("");
  return bearer.current;
}

test.describe("A2A interoperability console (live)", () => {
  test.skip(!USER_EMAIL || !USER_PASSWORD, "E2E_USER_EMAIL / E2E_USER_PASSWORD not set");
  // Every full-page navigation on this stack pays for a complete Keycloak redirect
  // round-trip (the silent check-sso iframe is blocked by auth's frame-ancestors),
  // so route loads are much slower than a healthy deployment's.
  test.setTimeout(5 * 60 * 1000);

  test("unauthenticated console endpoints answer 401; well-known endpoints are public", async ({ request }) => {
    for (const path of ["/api/a2a/console/tasks", "/api/a2a/console/policy"]) {
      const res = await request.get(`${API_URL}${path}`);
      expect(res.status(), `${path} without token`).toBe(401);
    }
    for (const path of ["/.well-known/agent-card.json", "/.well-known/jwks.json", "/api/directory/agents"]) {
      const res = await request.get(`${API_URL}${path}`);
      expect(res.status(), `${path} public`).toBe(200);
    }
    const jwks = await (await request.get(`${API_URL}/.well-known/jwks.json`)).json();
    expect(Array.isArray(jwks.keys) && jwks.keys.length > 0, "JWKS has no keys").toBe(true);
  });

  test("console API: policy lists allow-listed hosts; tasks list is readable", async ({ page, request }) => {
    await signInAndGetToken(page);
    const policyRes = await request.get(`${API_URL}/api/a2a/console/policy`, { headers: auth() });
    expect(policyRes.status(), "GET /api/a2a/console/policy").toBe(200);
    const policy = await policyRes.json();
    expect(policy.server.enabled, "a2a server disabled").toBe(true);
    expect(Array.isArray(policy.client.allowedHosts), "policy.client.allowedHosts").toBe(true);
    // Deployment allow-list for this stage is *.example.org (app.a2a.client.*).
    expect(policy.client.allowedHosts.join(" ")).toContain("example.org");

    const tasksRes = await request.get(`${API_URL}/api/a2a/console/tasks`, { headers: auth() });
    expect(tasksRes.status(), "GET /api/a2a/console/tasks").toBe(200);
    expect(Array.isArray((await tasksRes.json()).tasks), "tasks payload").toBe(true);
  });

  test("/admin/a2a renders inbound endpoints, outbound policy and the task table", async ({ page }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (e) => pageErrors.push(e.message));
    await signInAndGetToken(page);
    // ERR_ABORTED is possible: the unauthenticated boot swaps the document for the
    // Keycloak redirect before domcontentloaded - the heading wait is the real check.
    await page.goto("/admin/a2a", { waitUntil: "domcontentloaded" }).catch(() => {});

    await expect(page.getByRole("heading", { name: /agent-to-agent \(a2a\)/i })).toBeVisible({ timeout: 90_000 });
    await expect(page.getByText(/inbound endpoints/i)).toBeVisible();
    await expect(page.getByText(/outbound: remote agents your agents may call/i)).toBeVisible();
    await expect(page.getByText(/a2a tasks/i).first()).toBeVisible();
    // The allow-listed host from the deployment policy surfaces as a badge.
    await expect(page.getByText("*.example.org").first()).toBeVisible({ timeout: 15_000 });
    // Endpoint paths a peer would be handed (rendered as "POST /api/a2a" inside the dd).
    await expect(page.getByText(/\/api\/a2a\b/).first()).toBeVisible();
    await expect(page.getByText(/\.well-known\/jwks\.json/).first()).toBeVisible();

    expect(pageErrors, `client exceptions on /admin/a2a: ${pageErrors.join(" | ")}`).toEqual([]);
  });

  test("workbench Reasoning & autonomy shows the 'Callable by other agents (A2A)' panel", async ({
    page,
    request,
  }) => {
    await signInAndGetToken(page);
    // Deep-link straight at a saved agent - the section only mounts for a non-draft selection,
    // and letting the workbench race the agents list on a loaded stack wastes the whole window.
    const agents = await (await request.get(`${API_URL}/api/agents`, { headers: auth(), timeout: 60_000 })).json();
    const agentId = (Array.isArray(agents) ? agents : [])[0]?.id;
    expect(agentId, "tenant has no saved agent to open in the workbench").toBeTruthy();
    await page.goto(`/agents?agentId=${agentId}`, { waitUntil: "domcontentloaded" }).catch(() => {});
    // The workbench can still be finishing a silent SSO round-trip when it lands;
    // the section's 30s mount window starts only after it is actually on screen.
    const trigger = page.getByRole("button", { name: /reasoning & autonomy/i }).first();
    await expect(trigger, "Reasoning & autonomy section never mounted").toBeVisible({ timeout: 90_000 });
    await expandReasoningAutonomySection(page);
    await expect(
      page.getByText(/callable by other agents \(a2a\)/i),
      "A2A endpoint panel not rendered in the Autonomy section"
    ).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/json-rpc 2\.0 endpoint/i)).toBeVisible();
  });

  test("inbound JSON-RPC message/send runs a turn and lands in the console task list", async ({
    page,
    request,
  }, testInfo) => {
    testInfo.skip(process.env.E2E_ALLOW_MUTATIONS !== "1", "creates an agent + an inbound task - set E2E_ALLOW_MUTATIONS=1");
    test.setTimeout(4 * 60 * 1000);
    await signInAndGetToken(page);

    const created = await request.post(`${API_URL}/api/agents`, {
      headers: { ...auth(), "Content-Type": "application/json" },
      timeout: 60_000, // this stack is heavily loaded by concurrent E2E agents
      // PLATFORM_HOSTED is the platform pool (host Ollama on this stack) - the only provider
      // guaranteed usable without tenant credentials.
      data: { name: `e2e-a2a-${Date.now().toString(36)}`, systemPrompt: "You are a test agent. Reply briefly.", providerName: "PLATFORM_HOSTED", model: "claude-sonnet-4-5", maxTokens: 64 },
    });
    expect(created.status(), "create agent").toBeLessThan(300);
    const agentId = (await created.json()).id;

    try {
      const cardRes = await request.get(`${API_URL}/api/agents/${agentId}/a2a/agent-card.json`, { headers: auth() });
      expect(cardRes.status(), "per-agent card").toBe(200);
      const card = await cardRes.json();
      expect(card.signatures?.length ?? 0, "agent card is not signed").toBeGreaterThan(0);

      const send = await request.post(`${API_URL}/api/agents/${agentId}/a2a`, {
        headers: { ...auth(), "Content-Type": "application/json" },
        data: {
          jsonrpc: "2.0",
          id: "e2e-a2a-1",
          method: "message/send",
          params: { message: { role: "user", messageId: "e2e-a2a-msg-1", parts: [{ kind: "text", text: "Reply with exactly: pong" }] } },
        },
        timeout: 3 * 60 * 1000, // a real local-model turn is slow
      });
      expect(send.status(), "message/send").toBe(200);
      const body = await send.json();
      expect(body.error, `JSON-RPC error: ${JSON.stringify(body.error)}`).toBeUndefined();
      const taskId = body.result?.id;
      expect(taskId, "no task id in message/send result").toBeTruthy();

      await expect
        .poll(
          async () => {
            const res = await request.get(`${API_URL}/api/a2a/console/tasks`, { headers: auth(), timeout: 30_000 });
            const rows = res.ok() ? (await res.json()).tasks ?? [] : [];
            return rows.find((r: any) => r.id === taskId)?.state ?? "absent";
          },
          { timeout: 60_000, message: "inbound task never appeared in /api/a2a/console/tasks" }
        )
        .toMatch(/^TASK_STATE_/);
    } finally {
      await request.delete(`${API_URL}/api/agents/${agentId}`, { headers: auth(), failOnStatusCode: false });
    }
  });
});
