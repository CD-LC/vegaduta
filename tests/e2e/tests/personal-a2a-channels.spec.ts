import { expect, test as base, type APIRequestContext, type Page } from "@playwright/test";

/**
 * Personal-edition A2A and channels/devices surfaces, signed in as the owner.
 *
 * What the personal edition actually exposes (verified against the source, not assumed):
 *   - A2A is gated by EditionFeature.A2A_DIRECTORY, which is OFF on a personal install
 *     (/api/public/status edition.features["a2a-directory"] === false). The core A2A controllers
 *     (agent card, JSON-RPC, REST, console) are only registered when that feature is on, and the web
 *     proxy answers 404 for /.well-known/agent-card.json and /.well-known/agent.json. The workbench's
 *     "Callable by other agents (A2A)" panel self-hides on personal.
 *   - Channels: social channels (Slack, Discord, Teams, X) are off; Telegram and WhatsApp stay.
 *   - Devices: /devices forwards to /admin/channels?channel=mobile-sdk. The device pairing API is not
 *     edition-gated, so the pairing lifecycle works on personal.
 *
 * So the A2A block asserts the off state, and runs the positive card and message/send checks only when a
 * build turns the a2a-directory feature on. The round trip writes a task row, so it is mutation-gated like
 * the production A2A spec.
 *
 * Run against a personal stack (see playwright.personal.config.ts). Needs E2E_PERSONAL_EMAIL and
 * E2E_PERSONAL_PASSWORD (credentials from the environment only); without them every check skips.
 * The device lifecycle and the A2A round trip need E2E_ALLOW_MUTATIONS=1.
 */
const PERSONAL = process.env.E2E_EDITION === "personal";
const OWNER_EMAIL = process.env.E2E_PERSONAL_EMAIL || "";
const OWNER_PASSWORD = process.env.E2E_PERSONAL_PASSWORD || "";
const HAS_OWNER = !!(OWNER_EMAIL && OWNER_PASSWORD);
const MUTATIONS = process.env.E2E_ALLOW_MUTATIONS === "1";
const API = process.env.E2E_API_BASE_URL || "http://localhost:8080";

const SKIP_REASON =
  "personal A2A/channels suite: run it with playwright.personal.config.ts against a personal stack";
const OWNER_SKIP =
  "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD (the owner after first-run setup)";
const MUTATION_SKIP = "writes rows (device pairing / A2A task) - set E2E_ALLOW_MUTATIONS=1";

type Owner = Page & { api: APIRequestContext };

/**
 * The owner fixture signs in through the web origin (cookie session) and also captures the bearer
 * access token from the sign-in body. Core API calls go through `owner.api`, a context that sends
 * `Authorization: Bearer <accessToken>`: the core API rejects cookie-only requests with 401.
 */
const test = base.extend<{ owner: Owner }>({
  owner: async ({ page, baseURL, playwright }, use) => {
    await page
      .context()
      .addCookies([
        {
          name: "NEXT_LOCALE",
          value: "en",
          url: baseURL || "http://localhost:3000",
        },
      ]);
    const res = await page.request.post("/api/local-auth/login", {
      data: { identifier: OWNER_EMAIL, password: OWNER_PASSWORD },
    });
    expect(res.status(), "owner sign-in (is first-run setup finished?)").toBe(
      200,
    );
    const body = (await res.json()) as { accessToken?: string };
    expect(body.accessToken, "sign-in response carried no accessToken").toBeTruthy();
    const api = await playwright.request.newContext({
      baseURL: API,
      extraHTTPHeaders: { Authorization: `Bearer ${body.accessToken}` },
    });
    const owner = Object.assign(page, { api }) as Owner;
    try {
      await use(owner);
    } finally {
      await api.dispose();
    }
  },
});

/** Whether this build serves the A2A surface (the a2a-directory edition feature). */
async function a2aFeatureOn(request: APIRequestContext): Promise<boolean> {
  // The status document lives on the core API, not on the web origin (which answers 404 for it).
  const api = process.env.E2E_API_BASE_URL || "http://localhost:8080";
  const res = await request.get(`${api}/api/public/status`);
  expect(res.status(), "GET /api/public/status").toBe(200);
  const body = (await res.json()) as {
    edition?: { features?: Record<string, boolean> };
  };
  return body.edition?.features?.["a2a-directory"] === true;
}

/** The owner's agents, as the console lists them. */
async function ownedAgents(request: APIRequestContext): Promise<Array<{ id: string; name: string }>> {
  const res = await request.get("/api/agents");
  expect(res.status(), "GET /api/agents").toBe(200);
  const body = await res.json();
  return Array.isArray(body) ? body : [];
}

test.describe("personal A2A: off on a personal install", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(!HAS_OWNER, OWNER_SKIP);

  test("the public agent card and its legacy alias are withheld (404), not served", async ({ owner }) => {
    test.skip(await a2aFeatureOn(owner.api), "a2a-directory is on in this build - see the positive A2A block");
    for (const path of ["/.well-known/agent-card.json", "/.well-known/agent.json"]) {
      const res = await owner.request.get(path);
      expect(res.status(), `${path} on a personal install`).toBe(404);
      expect(res.headers()["content-type"] ?? "", `${path} must not serve a card`).not.toMatch(/json/i);
    }
  });

  test("the A2A console and JSON-RPC endpoints are not mounted for the owner", async ({ owner }) => {
    test.skip(await a2aFeatureOn(owner.api), "a2a-directory is on in this build - see the positive A2A block");
    const consoleRes = await owner.api.get("/api/a2a/console/policy");
    expect(consoleRes.status(), "GET /api/a2a/console/policy").toBe(404);

    const agents = await ownedAgents(owner.api);
    const agentId = agents[0]?.id;
    test.skip(!agentId, "the owner has no saved agent to address its A2A endpoint");
    const card = await owner.api.get(`/api/agents/${agentId}/a2a/agent-card.json`);
    expect(card.status(), "per-agent agent card on a personal install").toBe(404);

    const rpc = await owner.api.post(`/api/agents/${agentId}/a2a`, {
      headers: { "Content-Type": "application/json" },
      data: {
        jsonrpc: "2.0",
        id: "personal-a2a-off",
        method: "message/send",
        params: { message: { role: "user", messageId: "personal-a2a-off-1", parts: [{ kind: "text", text: "ping" }] } },
      },
    });
    expect(rpc.status(), "JSON-RPC message/send on a personal install").toBe(404);
  });

  test("/admin/a2a is a 404 for the owner", async ({ owner }) => {
    const res = await owner.goto("/admin/a2a");
    expect(res?.status()).toBe(404);
  });

  test("the workbench Autonomy section offers no 'Callable by other agents (A2A)' panel", async ({ owner }) => {
    test.skip(await a2aFeatureOn(owner.api), "a2a-directory is on in this build - see the positive A2A block");
    const agentId = (await ownedAgents(owner.api))[0]?.id;
    test.skip(!agentId, "the owner has no saved agent to open in the workbench");
    await owner.goto(`/agents?agentId=${agentId}`);
    const trigger = owner.getByRole("button", { name: /reasoning & autonomy/i }).first();
    await expect(trigger, "Reasoning & autonomy section never mounted").toBeVisible({ timeout: 30_000 });
    await trigger.click();
    await expect(owner.getByText(/json-rpc 2\.0 endpoint/i)).toHaveCount(0);
    await expect(owner.getByText(/callable by other agents \(a2a\)/i)).toHaveCount(0);
  });
});

test.describe("personal A2A: positive path (only when a2a-directory is on)", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(!HAS_OWNER, OWNER_SKIP);

  test("the card at /.well-known/agent-card.json advertises the owner's agents as skills", async ({ owner }) => {
    test.skip(!(await a2aFeatureOn(owner.api)), "a2a-directory is off on this personal install");
    const res = await owner.request.get("/.well-known/agent-card.json");
    expect(res.status(), "platform agent card").toBe(200);
    expect(res.headers()["content-type"] ?? "").toMatch(/json/i);
    const card = await res.json();
    expect(typeof card.name, "card.name").toBe("string");
    expect(String(card.url ?? ""), "card.url must be an absolute endpoint").toMatch(/^https?:\/\//);
  });

  test("a per-agent card is signed and its skills name the owned agent", async ({ owner }) => {
    test.skip(!(await a2aFeatureOn(owner.api)), "a2a-directory is off on this personal install");
    const agent = (await ownedAgents(owner.api))[0];
    test.skip(!agent, "the owner has no saved agent to publish a card for");
    const res = await owner.api.get(`/api/agents/${agent.id}/a2a/agent-card.json`);
    expect(res.status(), "per-agent card").toBe(200);
    const card = await res.json();
    expect(card.signatures?.length ?? 0, "per-agent card is not signed").toBeGreaterThan(0);
    expect(Array.isArray(card.skills), "card.skills").toBe(true);
    expect(card.skills.length, "card advertises no skills").toBeGreaterThan(0);
    const skill = card.skills.find((s: { id?: string }) => s.id === agent.id);
    expect(skill, `no skill with the owned agent's id ${agent.id}`).toBeTruthy();
    expect(skill.name).toBe(agent.name);
  });

  test("a JSON-RPC message/send to the owned agent returns a task in a TASK_STATE_ state", async ({ owner, request }, testInfo) => {
    testInfo.skip(!MUTATIONS, MUTATION_SKIP);
    test.skip(!(await a2aFeatureOn(owner.api)), "a2a-directory is off on this personal install");
    const agent = (await ownedAgents(owner.api))[0];
    test.skip(!agent, "the owner has no saved agent to send a message to");
    test.setTimeout(4 * 60 * 1000);

    const send = await owner.api.post(`/api/agents/${agent.id}/a2a`, {
      headers: { "Content-Type": "application/json" },
      timeout: 3 * 60 * 1000,
      data: {
        jsonrpc: "2.0",
        id: "personal-a2a-1",
        method: "message/send",
        params: { message: { role: "user", messageId: `personal-a2a-${Date.now()}`, parts: [{ kind: "text", text: "Reply with exactly: pong" }] } },
      },
    });
    expect(send.status(), "message/send").toBe(200);
    const body = await send.json();
    expect(body.error, `JSON-RPC error: ${JSON.stringify(body.error)}`).toBeUndefined();
    expect(body.result?.id, "no task id in message/send result").toBeTruthy();
    expect(String(body.result?.status?.state ?? ""), "task state").toMatch(/^TASK_STATE_/);
  });
});

test.describe("personal channels: signed in as the owner", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(!HAS_OWNER, OWNER_SKIP);

  test("the channels grid keeps Telegram and WhatsApp and omits the social channels", async ({ owner }) => {
    await owner.goto("/admin/channels");
    const cards = owner.getByTestId("channel-card");
    await expect(cards.first()).toBeVisible();
    const keys = await cards.evaluateAll((els) => els.map((el) => el.getAttribute("data-channel") ?? ""));
    expect(keys, "channel keys on the personal grid").toEqual(expect.arrayContaining(["telegram", "whatsapp"]));
    for (const hidden of ["slack", "discord", "teams", "x"]) {
      expect(keys, `channel ${hidden} must not be offered`).not.toContain(hidden);
    }
  });

  test("the Telegram connect form renders its Bot Token field and a Connect or Replace button", async ({ owner }) => {
    const pageErrors: string[] = [];
    owner.on("pageerror", (e) => pageErrors.push(e.message));
    await owner.goto("/admin/channels?channel=telegram");
    await expect(owner.getByLabel("Bot Token"), "telegram Bot Token field").toBeVisible({ timeout: 15_000 });
    await expect(owner.getByRole("button", { name: /^(Connect|Replace)$/ }).first()).toBeVisible();
    expect(pageErrors, `client exceptions on the Telegram form: ${pageErrors.join(" | ")}`).toEqual([]);
  });

  test("/devices forwards to the Mobile SDK tab on the channels page", async ({ owner }) => {
    await owner.goto("/devices");
    await expect(owner, "/devices should forward to the Mobile SDK tab").toHaveURL(/\/admin\/channels\?.*channel=mobile-sdk/, {
      timeout: 30_000,
    });
    await expect(owner.getByRole("heading", { name: "Mobile SDK" })).toBeVisible({ timeout: 15_000 });
    await expect(owner.getByRole("button", { name: "Generate pairing code" })).toBeVisible();
  });
});

test.describe("personal devices: pairing lifecycle (writes a device row)", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(!HAS_OWNER, OWNER_SKIP);

  test("generate code, claim, see the device, poll, revoke, then the revoked token is rejected", async ({ owner, request }, testInfo) => {
    testInfo.skip(!MUTATIONS, MUTATION_SKIP);
    testInfo.setTimeout(120_000);
    const pageErrors: string[] = [];
    owner.on("pageerror", (e) => pageErrors.push(e.message));

    await owner.goto("/admin/channels?channel=mobile-sdk");
    await owner.getByRole("button", { name: "Generate pairing code" }).click();
    const codeLocator = owner.locator("div.font-mono").filter({ hasText: /^\d{6}$/ }).first();
    await expect(codeLocator, "pairing code not rendered").toBeVisible({ timeout: 20_000 });
    const code = (await codeLocator.textContent())?.trim() ?? "";
    expect(code, "pairing code").toMatch(/^\d{6}$/);

    // The phone claims the code itself: POST /api/devices/claim is permitAll, so no session is involved.
    const deviceName = `Personal E2E Device ${Date.now()}`;
    const claim = await request.post(`${API}/api/devices/claim`, {
      data: { code, deviceName, platform: "ANDROID" },
    });
    expect(claim.status(), `claim failed: ${await claim.text()}`).toBe(200);
    const deviceToken = (await claim.json()).deviceToken as string;
    expect(deviceToken, "claim response carried no deviceToken").toBeTruthy();

    await owner.goto("/admin/channels?channel=mobile-sdk");
    const nameEl = owner.getByText(deviceName, { exact: true }).first();
    await expect(nameEl, "claimed device never appeared in the paired list").toBeVisible({ timeout: 20_000 });
    const deviceCard = nameEl.locator("xpath=ancestor::div[.//button[contains(., 'evoke')]][1]");
    await expect(deviceCard.getByText(/never polled/i)).toBeVisible();

    const pollBefore = await request.post(`${API}/api/devices/poll`, { headers: { "X-Device-Token": deviceToken } });
    expect([200, 204], `poll before revoke returned ${pollBefore.status()}`).toContain(pollBefore.status());

    await deviceCard.getByRole("button", { name: "Revoke" }).click();
    await deviceCard.getByRole("button", { name: "Confirm revoke" }).click();
    await expect(owner.getByText(/Device revoked/i).first()).toBeVisible({ timeout: 20_000 });
    const revokedCard = owner
      .getByText(deviceName, { exact: true })
      .first()
      .locator("xpath=ancestor::div[.//button[contains(., 'Command history')]][1]");
    await expect(revokedCard.getByText(/^revoked$/i)).toBeVisible({ timeout: 20_000 });

    const pollAfter = await request.post(`${API}/api/devices/poll`, { headers: { "X-Device-Token": deviceToken } });
    expect(pollAfter.status(), "a revoked device token still authenticated a poll").toBe(400);
    expect(String((await pollAfter.json()).error)).toMatch(/unknown or revoked device token/i);

    testInfo.annotations.push({
      type: "data-left-behind",
      description: `Paired and revoked one device ("${deviceName}") on the personal stack; left in place as an audit row.`,
    });
    expect(pageErrors, `client exceptions on the Mobile SDK tab: ${pageErrors.join(" | ")}`).toEqual([]);
  });
});
