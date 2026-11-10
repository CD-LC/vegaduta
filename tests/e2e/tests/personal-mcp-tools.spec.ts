import { expect, test as base, type APIRequestContext, type Page } from "@playwright/test";

/**
 * Personal-edition MCP and tool coverage (signed in as the owner):
 *
 *   - MCP server registration: register, read back, toggle, delete (CONTROL-COVERAGE-MATRIX "MCP server
 *     register / toggle / remove", previously covered only by the Keycloak-stack specs),
 *   - tool inventory: the tools an agent would be offered (GET /api/agents/{id}/available-tools),
 *   - tool selection: the per-agent allow-list (PATCH /tool-access) and the Auto-select / Manual mode
 *     (PATCH /experience-mode), the matrix row that had no test at all,
 *   - a real tool call: a chat turn on an agent restricted to one tool must land in the tool-call log
 *     (GET /api/agents/{id}/tool-calls).
 *
 * Driving the registration form, the Auto-select toggle and the workbench tool-call trace through the UI
 * is brittle against the personal stack, so the mutations go through the same core REST endpoints the
 * controls call and every assertion checks the returned values, not only the status code. The /tools page
 * is checked through the UI for the registered server name.
 *
 * Run with `npx playwright test -c playwright.personal.config.ts` against a personal stack. Needs the
 * owner credentials (E2E_PERSONAL_EMAIL / E2E_PERSONAL_PASSWORD); without them every check skips.
 * The password is read from the environment only and never written anywhere.
 */
const PERSONAL = process.env.E2E_EDITION === "personal";
const OWNER_EMAIL = process.env.E2E_PERSONAL_EMAIL || "";
const OWNER_PASSWORD = process.env.E2E_PERSONAL_PASSWORD || "";
const HAS_OWNER = !!(OWNER_EMAIL && OWNER_PASSWORD);

const SKIP_REASON =
  "personal MCP/tools suite: run it with playwright.personal.config.ts against a personal stack";

const RUN = Date.now().toString(36);
const NAME_PREFIX = "e2e-mcp-tools-";

const API = process.env.E2E_API_BASE_URL || "http://localhost:8080";

type Owner = Page & { api: APIRequestContext };

/**
 * A fresh browser profile has never answered the first-run welcome tour, so its modal sits over every
 * console page and covers the controls under test. Mark the owner's tour as done, the same localStorage
 * keys the console writes when the owner finishes it (user-scoped by the token's `sub`, as in
 * web/app/components/tour/storage.ts). Only missing keys are written, so a real choice is never overwritten.
 */
async function suppressFirstRunTour(page: Page, accessToken: string) {
  let sub: string | null = null;
  try {
    const payload = JSON.parse(Buffer.from(accessToken.split(".")[1], "base64url").toString("utf8")) as {
      sub?: unknown;
    };
    sub = typeof payload.sub === "string" ? payload.sub : null;
  } catch {
    sub = null;
  }
  await page.context().addInitScript((scopedSub: string | null) => {
    try {
      const keys = ["vd_tour_status", "vd_tour_seen_security", "vd_tour_ended"];
      const scoped = scopedSub ? keys.map((k) => `${k}:${scopedSub}`) : [];
      for (const k of [...keys, ...scoped]) {
        if (localStorage.getItem(k) === null) localStorage.setItem(k, k.startsWith("vd_tour_status") ? "done" : "1");
      }
    } catch {
      // Storage blocked: the tour simply shows, as it would for any visitor.
    }
  }, sub);
}

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
    await suppressFirstRunTour(page, body.accessToken as string);
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

type Registration = {
  id: string;
  name: string;
  type: string;
  enabled: boolean;
  connectionStatus?: string;
};
type ToolDescriptor = { name: string; description: string | null };
type AgentLike = {
  id: string;
  name: string;
  providerName: string;
  model: string | null;
  allowedToolNames: string[] | null;
  experienceMode: string | null;
};
type ToolCallEntry = {
  id: string;
  sessionId: string;
  toolName: string;
  toolInput: string | null;
  toolOutput: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
};

async function getJson<T>(request: APIRequestContext, path: string): Promise<T> {
  const res = await request.get(path);
  expect(res.status(), `GET ${path}`).toBe(200);
  return (await res.json()) as T;
}

/** A throwaway agent that copies the owner's existing provider/model, so it runs on the same stack. */
async function createTestAgent(request: APIRequestContext): Promise<AgentLike> {
  const existing = await getJson<AgentLike[]>(request, `${API}/api/agents`);
  test.skip(existing.length === 0, "no agent exists yet - finish first-run setup");
  const base = existing[0];
  const res = await request.post(`${API}/api/agents`, {
    data: {
      name: `${NAME_PREFIX}agent-${RUN}`,
      systemPrompt:
        "You are a test agent. When asked to fetch a web page, call your fetch_web_page tool.",
      providerName: base.providerName,
      model: base.model || undefined,
    },
  });
  expect([200, 201], `create test agent: ${(await res.text()).slice(0, 200)}`).toContain(res.status());
  return (await res.json()) as AgentLike;
}

async function deleteAgent(request: APIRequestContext, id: string) {
  await request.delete(`${API}/api/agents/${id}`, { failOnStatusCode: false });
}

async function deleteRegistration(request: APIRequestContext, id: string) {
  await request.delete(`${API}/api/mcp/registry/${id}`, { failOnStatusCode: false });
}

test.describe("personal MCP: registration", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(
    !HAS_OWNER,
    "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD (the owner after first-run setup)",
  );

  test("registering a disabled remote server returns its name, type and enabled=false, and it is listed", async ({
    owner,
  }) => {
    const name = `${NAME_PREFIX}remote-${RUN}`;
    const res = await owner.api.post(`${API}/api/mcp/registry`, {
      data: {
        name,
        type: "STREAMABLE_HTTP",
        description: "personal MCP registration check",
        enabled: false,
        url: "https://mcp.e2e-mcp-tools.invalid/mcp",
      },
      timeout: 90_000,
    });
    // The remote-URL guard may refuse the host outright; a 4xx is a valid outcome, a 5xx is not.
    expect(res.status(), `register answered ${res.status()}`).toBeLessThan(500);
    test.skip(
      res.status() >= 400,
      `registration refused with ${res.status()} (remote URL guard) - nothing to verify`,
    );

    const reg = (await res.json()) as Registration;
    try {
      expect(reg.name).toBe(name);
      expect(reg.type).toBe("STREAMABLE_HTTP");
      expect(reg.enabled, "registered disabled").toBe(false);
      expect(reg.id, "registration carries an id").toBeTruthy();

      const listed = await getJson<Registration[]>(owner.api, `${API}/api/mcp/registry`);
      const row = listed.find((r) => r.id === reg.id);
      expect(row, "new registration missing from GET /api/mcp/registry").toBeTruthy();
      expect(row?.name).toBe(name);
      expect(row?.enabled).toBe(false);
    } finally {
      await deleteRegistration(owner.api, reg.id);
    }
  });

  test("a registered server shows up on the /tools page", async ({ owner }) => {
    const name = `${NAME_PREFIX}ui-${RUN}`;
    const res = await owner.api.post(`${API}/api/mcp/registry`, {
      data: {
        name,
        type: "STREAMABLE_HTTP",
        description: "personal MCP UI check",
        enabled: false,
        url: "https://mcp.e2e-mcp-tools.invalid/mcp",
      },
      timeout: 90_000,
    });
    test.skip(
      res.status() >= 400,
      `registration refused with ${res.status()} - nothing to show on /tools`,
    );
    expect(res.status()).toBeLessThan(500);
    const reg = (await res.json()) as Registration;
    try {
      await owner.goto("/tools");
      await expect(owner).not.toHaveURL(/404/);
      // A custom server is the last card in the "All" grid, after every catalog entry, so with the default
      // page size it sits on a later page. The page's own "Search tools & servers" box filters the grid by
      // name (web/app/lib/mcpCardSearch.ts), which is how a person finds a server they just registered.
      await owner.getByLabel(/^Search tools & servers/).fill(name);
      await expect(
        owner.getByText(name).first(),
        "registered MCP server name never rendered on /tools",
      ).toBeVisible({ timeout: 20_000 });
    } finally {
      await deleteRegistration(owner.api, reg.id);
    }
  });

  test("toggling enabled and then deleting a registration is reflected in the list", async ({
    owner,
  }) => {
    const name = `${NAME_PREFIX}toggle-${RUN}`;
    const res = await owner.api.post(`${API}/api/mcp/registry`, {
      data: {
        name,
        type: "STREAMABLE_HTTP",
        description: "personal MCP toggle check",
        enabled: false,
        url: "https://mcp.e2e-mcp-tools.invalid/mcp",
      },
      timeout: 90_000,
    });
    test.skip(res.status() >= 400, `registration refused with ${res.status()}`);
    const reg = (await res.json()) as Registration;
    const rowOf = async () =>
      (await getJson<Registration[]>(owner.api, `${API}/api/mcp/registry`)).find(
        (r) => r.id === reg.id,
      );
    try {
      const on = await owner.api.patch(`${API}/api/mcp/registry/${reg.id}`, {
        data: { enabled: true },
      });
      expect(on.status(), "toggle on must not 5xx").toBeLessThan(500);
      if (on.ok()) {
        const body = (await on.json()) as Registration;
        expect(body.enabled, "toggle response carries enabled=true").toBe(true);
        expect((await rowOf())?.enabled, "list reflects enabled=true").toBe(true);
      }

      const off = await owner.api.patch(`${API}/api/mcp/registry/${reg.id}`, {
        data: { enabled: false },
      });
      expect(off.status()).toBeLessThan(500);
      if (off.ok()) {
        expect((await rowOf())?.enabled, "list reflects enabled=false").toBe(false);
      }

      const del = await owner.api.delete(`${API}/api/mcp/registry/${reg.id}`);
      expect(del.status(), "delete answers 204").toBe(204);
      expect(await rowOf(), "deleted registration still listed").toBeUndefined();
    } finally {
      await deleteRegistration(owner.api, reg.id);
    }
  });
});

test.describe("personal tools: inventory, selection and the tool log", () => {
  test.skip(!PERSONAL, SKIP_REASON);
  test.skip(
    !HAS_OWNER,
    "set E2E_PERSONAL_EMAIL and E2E_PERSONAL_PASSWORD (the owner after first-run setup)",
  );

  test("the tool inventory for an agent lists named tools, including the built-in fetch_web_page", async ({
    owner,
  }) => {
    const agent = await createTestAgent(owner.api);
    try {
      const tools = await getJson<ToolDescriptor[]>(
        owner.api,
        `${API}/api/agents/${agent.id}/available-tools`,
      );
      expect(Array.isArray(tools), "available-tools is an array").toBe(true);
      expect(tools.length, "an agent is offered at least one tool").toBeGreaterThan(0);
      for (const t of tools) {
        expect(t.name.trim(), "every tool has a non-blank name").not.toBe("");
      }
      const names = tools.map((t) => t.name);
      expect(new Set(names).size, "tool names are unique").toBe(names.length);
      expect(names, "built-in fetch_web_page is offered without a key").toContain(
        "fetch_web_page",
      );
    } finally {
      await deleteAgent(owner.api, agent.id);
    }
  });

  test("restricting an agent to one tool persists the allow-list and clearing it empties it", async ({
    owner,
  }) => {
    const agent = await createTestAgent(owner.api);
    try {
      const set = await owner.api.patch(`${API}/api/agents/${agent.id}/tool-access`, {
        data: { allowedToolNames: ["fetch_web_page"] },
      });
      expect(set.status(), "set allow-list").toBe(200);
      expect(((await set.json()) as AgentLike).allowedToolNames).toEqual([
        "fetch_web_page",
      ]);

      const readBack = await getJson<AgentLike>(owner.api, `${API}/api/agents/${agent.id}`);
      expect(readBack.allowedToolNames, "allow-list read back from the agent").toEqual([
        "fetch_web_page",
      ]);

      const clear = await owner.api.patch(`${API}/api/agents/${agent.id}/tool-access`, {
        data: { allowedToolNames: [] },
      });
      expect(clear.status()).toBe(200);
      const cleared = await getJson<AgentLike>(owner.api, `${API}/api/agents/${agent.id}`);
      expect(
        cleared.allowedToolNames ?? [],
        "an empty list clears the restriction",
      ).toEqual([]);
    } finally {
      await deleteAgent(owner.api, agent.id);
    }
  });

  test("Auto-select and Manual tool mode round-trip through the experience mode", async ({
    owner,
  }) => {
    const agent = await createTestAgent(owner.api);
    try {
      const manual = await owner.api.patch(
        `${API}/api/agents/${agent.id}/experience-mode`,
        { data: { experienceMode: "ADVANCED" } },
      );
      expect(manual.status()).toBe(200);
      expect(((await manual.json()) as AgentLike).experienceMode).toBe("ADVANCED");

      const auto = await owner.api.patch(
        `${API}/api/agents/${agent.id}/experience-mode`,
        { data: { experienceMode: "SIMPLE" } },
      );
      expect(auto.status()).toBe(200);
      expect(((await auto.json()) as AgentLike).experienceMode).toBe("SIMPLE");

      const readBack = await getJson<AgentLike>(owner.api, `${API}/api/agents/${agent.id}`);
      expect(readBack.experienceMode, "auto-select persisted on the agent").toBe("SIMPLE");
    } finally {
      await deleteAgent(owner.api, agent.id);
    }
  });

  test("a turn that calls the allowed tool lands in the agent's tool-call log with its input", async ({
    owner,
  }) => {
    // A cold local model can take well over 60s on its first turn.
    test.setTimeout(300_000);
    const agent = await createTestAgent(owner.api);
    try {
      const allow = await owner.api.patch(`${API}/api/agents/${agent.id}/tool-access`, {
        data: { allowedToolNames: ["fetch_web_page"] },
      });
      expect(allow.status()).toBe(200);

      const before = await getJson<ToolCallEntry[]>(
        owner.api,
        `${API}/api/agents/${agent.id}/tool-calls`,
      );
      const seenBefore = new Set(before.map((c) => c.id));

      const chat = await owner.api.post(`${API}/api/agents/${agent.id}/chat`, {
        data: {
          message:
            "Use your fetch_web_page tool on https://example.com and tell me the page heading.",
        },
        timeout: 240_000,
      });
      expect(chat.status(), "chat turn accepted").toBe(200);

      const found: { entry?: ToolCallEntry } = {};
      await expect
        .poll(
          async () => {
            const now = await getJson<ToolCallEntry[]>(
              owner.api,
              `${API}/api/agents/${agent.id}/tool-calls`,
            );
            found.entry = now.find(
              (c) => !seenBefore.has(c.id) && c.toolName === "fetch_web_page",
            );
            return found.entry?.toolName ?? "";
          },
          {
            timeout: 150_000,
            message: "no fetch_web_page call appeared in the tool-call log",
          },
        )
        .toBe("fetch_web_page");

      // Values, not presence: the logged call names the tool, carries the URL the model passed, has a
      // session and a start time.
      const landed = found.entry;
      expect(landed?.toolName).toBe("fetch_web_page");
      expect(landed?.toolInput ?? "", "logged input names the requested URL").toMatch(
        /example\.com/,
      );
      expect(landed?.sessionId, "logged call belongs to a chat session").toBeTruthy();
      expect(landed?.startedAt, "logged call has a start time").toBeTruthy();
    } finally {
      await deleteAgent(owner.api, agent.id);
    }
  });
});
