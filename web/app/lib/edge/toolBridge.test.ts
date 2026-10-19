// toolBridge's spend guard — the check that decides whether an on-device turn
// is even allowed to ask the server to execute a tool.
//
// Style follows the sibling edge tests (webmcp.test.ts, redactor.test.ts):
// resetModules + vi.doMock + a fresh dynamic import per case, so the module's
// session-sticky state (toolSurfaceGone, the tool-list cache) never leaks
// between tests.
//
// The assertions that matter are not "the boolean is right" — they are:
//   * a refused call performs NO fetch at all (the whole point: no wasted
//     request, no 60s timeout budget, no log line on an endpoint that should
//     never have been asked),
//   * a refused call never asks auth for a token, so a background dispatch
//     decision can never reach the refresh/reauth path,
//   * a refusal does NOT set the sticky-404 flag, so signing in mid-session
//     restores tools,
//   * and a signed-in call is byte-for-byte what it always was.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const API_BASE = "https://api.test";

interface AuthHarness {
  /** Every options object auth.getToken was called with. Must stay empty for
   * any call the guard refuses. */
  tokenCalls: unknown[];
  setAuthenticated: (value: boolean) => void;
}

/** Mock the auth module the way toolBridge consumes it: a synchronous
 * isAuthenticated() plus the silent getToken() used to build bearer headers. */
function mockAuth(initiallyAuthenticated: boolean, token: string | null = "tok-123"): AuthHarness {
  const harness: AuthHarness = {
    tokenCalls: [],
    setAuthenticated: (value: boolean) => {
      authenticated = value;
    },
  };
  let authenticated = initiallyAuthenticated;
  vi.doMock("../auth", () => ({
    auth: {
      isAuthenticated: () => authenticated,
      getToken: vi.fn(async (opts?: { silent?: boolean }) => {
        harness.tokenCalls.push(opts);
        return authenticated ? token : null;
      }),
    },
  }));
  return harness;
}

/** auth.isAuthenticated() throwing stands in for keycloak-js failing to
 * construct — the guard must fail closed, not propagate. */
function mockThrowingAuth(): void {
  vi.doMock("../auth", () => ({
    auth: {
      isAuthenticated: () => {
        throw new Error("keycloak-js unavailable");
      },
      getToken: vi.fn(async () => null),
    },
  }));
}

function mockDeps(): void {
  vi.doMock("../api", () => ({ API_BASE }));
  vi.doMock("./capabilities", () => ({ getEdgeLearningOptIn: () => false }));
}

interface FetchHarness {
  calls: { url: string; init: RequestInit }[];
}

/** Install a fake global fetch answering with `status` and `body`. */
function mockFetch(status: number, body: unknown): FetchHarness {
  const harness: FetchHarness = { calls: [] };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      harness.calls.push({ url: String(url), init });
      const text = typeof body === "string" ? body : JSON.stringify(body);
      return {
        ok: status >= 200 && status < 300,
        status,
        text: async () => text,
        json: async () => JSON.parse(text),
      } as unknown as Response;
    })
  );
  return harness;
}

async function importBridge() {
  return import("./toolBridge");
}

beforeEach(() => {
  vi.resetModules();
  mockDeps();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.doUnmock("../auth");
  vi.doUnmock("../api");
  vi.doUnmock("./capabilities");
});

describe("canUseServerTools", () => {
  it("is true only with both an agent identity and a live session", async () => {
    mockAuth(true);
    const { canUseServerTools } = await importBridge();
    expect(canUseServerTools("agent-1")).toBe(true);
  });

  it("is false without an agent identity, however signed in", async () => {
    mockAuth(true);
    const { canUseServerTools } = await importBridge();
    expect(canUseServerTools(null)).toBe(false);
    expect(canUseServerTools(undefined)).toBe(false);
    expect(canUseServerTools("")).toBe(false);
    expect(canUseServerTools("   ")).toBe(false);
  });

  it("is false for an anonymous visitor even with an agent id in hand", async () => {
    mockAuth(false);
    const { canUseServerTools } = await importBridge();
    expect(canUseServerTools("agent-1")).toBe(false);
  });

  it("fails closed when the auth client itself blows up", async () => {
    mockThrowingAuth();
    const { canUseServerTools } = await importBridge();
    expect(canUseServerTools("agent-1")).toBe(false);
  });

  it("never asks auth for a token — a dispatch decision cannot reach the refresh path", async () => {
    const authHarness = mockAuth(true);
    const { canUseServerTools } = await importBridge();
    canUseServerTools("agent-1");
    canUseServerTools(null);
    expect(authHarness.tokenCalls).toEqual([]);
  });
});

describe("executeEdgeTool — refusal when the guard is false", () => {
  it("returns the typed unavailable failure and performs no fetch at all", async () => {
    mockAuth(false);
    const fetchHarness = mockFetch(200, { ok: true, resultJson: "never reached" });
    const { executeEdgeTool } = await importBridge();

    const result = await executeEdgeTool("search_knowledge", { q: "x" }, "agent-1");

    expect(result.ok).toBe(false);
    // strict:false — the literal comparison is what narrows the union.
    if (result.ok === false) {
      expect(result.code).toBe("unavailable");
      expect(result.detail).toContain("not signed in");
    }
    expect(fetchHarness.calls).toEqual([]);
  });

  it("refuses an anonymous call without asking auth for a token", async () => {
    const authHarness = mockAuth(false);
    mockFetch(200, { ok: true, resultJson: "never reached" });
    const { executeEdgeTool } = await importBridge();

    await executeEdgeTool("search_knowledge", {}, "agent-1");

    expect(authHarness.tokenCalls).toEqual([]);
  });

  it("refuses a signed-in call that has no agent, with a readable detail", async () => {
    mockAuth(true);
    const fetchHarness = mockFetch(200, { ok: true, resultJson: "never reached" });
    const { executeEdgeTool } = await importBridge();

    const result = await executeEdgeTool("search_knowledge", {}, null);

    expect(result.ok).toBe(false);
    if (result.ok === false) {
      expect(result.code).toBe("unavailable");
      expect(result.detail).toContain("no agent selected");
    }
    expect(fetchHarness.calls).toEqual([]);
  });

  it("does not mark the tool surface gone — signing in mid-session restores tools", async () => {
    const authHarness = mockAuth(false);
    const fetchHarness = mockFetch(200, { ok: true, resultJson: "done" });
    const { executeEdgeTool, edgeToolsUnavailable } = await importBridge();

    await executeEdgeTool("search_knowledge", {}, "agent-1");
    expect(edgeToolsUnavailable()).toBe(false);
    expect(fetchHarness.calls).toEqual([]);

    authHarness.setAuthenticated(true);
    const after = await executeEdgeTool("search_knowledge", {}, "agent-1");

    expect(after).toEqual({ ok: true, result: "done" });
    expect(fetchHarness.calls).toHaveLength(1);
  });
});

describe("executeEdgeTool — the signed-in path is unchanged", () => {
  it("posts the documented body with a bearer and unwraps resultJson", async () => {
    mockAuth(true, "tok-abc");
    const fetchHarness = mockFetch(200, { ok: true, resultJson: "the answer" });
    const { executeEdgeTool } = await importBridge();

    const result = await executeEdgeTool("search_knowledge", { q: "x" }, "agent-1");

    expect(result).toEqual({ ok: true, result: "the answer" });
    expect(fetchHarness.calls).toHaveLength(1);
    const call = fetchHarness.calls[0];
    expect(call.url).toBe(`${API_BASE}/api/edge/tools/execute`);
    expect(call.init.method).toBe("POST");
    expect((call.init.headers as Record<string, string>).Authorization).toBe("Bearer tok-abc");
    expect(JSON.parse(String(call.init.body))).toEqual({
      agentId: "agent-1",
      toolName: "search_knowledge",
      argumentsJson: JSON.stringify({ q: "x" }),
    });
  });

  it("still asks for the token silently, so no reauth prompt can fire", async () => {
    const authHarness = mockAuth(true);
    mockFetch(200, { ok: true, resultJson: "ok" });
    const { executeEdgeTool } = await importBridge();

    await executeEdgeTool("search_knowledge", {}, "agent-1");

    expect(authHarness.tokenCalls).toEqual([{ silent: true }]);
  });

  it("still maps a server 401 to rejected, not to the guard's unavailable", async () => {
    mockAuth(true);
    mockFetch(401, { error: "nope" });
    const { executeEdgeTool } = await importBridge();

    const result = await executeEdgeTool("search_knowledge", {}, "agent-1");

    expect(result.ok).toBe(false);
    if (result.ok === false) expect(result.code).toBe("rejected");
  });
});

describe("executeEdgeTool — sticky 404 still sticks", () => {
  it("marks the surface gone on a 404 and stops fetching for the session", async () => {
    mockAuth(true);
    const fetchHarness = mockFetch(404, "");
    const { executeEdgeTool, edgeToolsUnavailable, resetEdgeToolBridge } = await importBridge();

    const first = await executeEdgeTool("search_knowledge", {}, "agent-1");
    expect(first).toEqual({
      ok: false,
      code: "unavailable",
      detail: "edge tool surface unavailable",
    });
    expect(edgeToolsUnavailable()).toBe(true);

    const second = await executeEdgeTool("search_knowledge", {}, "agent-1");
    expect(second).toEqual({
      ok: false,
      code: "unavailable",
      detail: "edge tool surface unavailable",
    });
    // One fetch total: the sticky flag short-circuits every later call, and the
    // guard (which passes here) did not displace it.
    expect(fetchHarness.calls).toHaveLength(1);

    resetEdgeToolBridge();
    expect(edgeToolsUnavailable()).toBe(false);
  });

  it("reports the 404 stickiness ahead of the guard once the surface is gone", async () => {
    const authHarness = mockAuth(true);
    mockFetch(404, "");
    const { executeEdgeTool } = await importBridge();

    await executeEdgeTool("search_knowledge", {}, "agent-1");
    authHarness.setAuthenticated(false);
    const result = await executeEdgeTool("search_knowledge", {}, "agent-1");

    if (result.ok === false) expect(result.detail).toBe("edge tool surface unavailable");
  });
});
