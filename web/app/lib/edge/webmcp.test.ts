// E-2b — publishing the agent's edge-safe tools to the browser's WebMCP model
// context (webmcp.ts). Style matches the sibling edge tests: resetModules +
// storage clear + doMock + a fresh dynamic import per case.
//
// navigator.modelContext does not exist in jsdom (it barely exists in
// Chrome), so every test installs a fake implementation recording what was
// registered. That is the honest shape of the test: this module's whole job
// is translating one contract into another, and what matters is WHAT it hands
// the browser — names, schemas, and an execute() that lands on the server's
// guarded endpoint rather than anywhere else.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

interface FakeTool {
  name: string;
  description: string;
  inputSchema?: unknown;
  execute: (input: unknown) => Promise<unknown>;
  annotations?: { readOnlyHint?: boolean };
}

/** Install a fake navigator.modelContext. `throwOn` makes registerTool
 * reject one specific name, standing in for a duplicate/invalid-schema
 * InvalidStateError. */
function installModelContext(options: { throwOn?: string } = {}): {
  tools: Map<string, FakeTool>;
  unregistered: string[];
} {
  const tools = new Map<string, FakeTool>();
  const unregistered: string[] = [];
  Object.defineProperty(navigator, "modelContext", {
    configurable: true,
    writable: true,
    value: {
      registerTool(tool: FakeTool) {
        if (options.throwOn && tool.name === options.throwOn) {
          throw new Error("InvalidStateError");
        }
        tools.set(tool.name, tool);
      },
      unregisterTool(name: string) {
        unregistered.push(name);
        tools.delete(name);
      },
    },
  });
  return { tools, unregistered };
}

function removeModelContext(): void {
  Reflect.deleteProperty(navigator as unknown as Record<string, unknown>, "modelContext");
  Reflect.deleteProperty(navigator as unknown as Record<string, unknown>, "modelContextTesting");
}

const TOOLS = [
  { name: "search_knowledge", description: "Search the tenant knowledge base.", inputSchema: { type: "object" } },
  { name: "Create Ticket", description: "", inputSchema: { type: "object" } },
];

function mockToolBridge(execResult: unknown = { ok: true, result: "done" }): { executed: unknown[] } {
  const executed: unknown[] = [];
  vi.doMock("./toolBridge", () => ({
    fetchEdgeTools: vi.fn(async () => TOOLS),
    executeEdgeTool: vi.fn(async (toolName: string, args: unknown, agentId: string | null) => {
      executed.push({ toolName, args, agentId });
      return execResult;
    }),
  }));
  return { executed };
}

async function importWebMcp() {
  return import("./webmcp");
}

describe("edge/webmcp — feature detection", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    removeModelContext();
  });

  afterEach(() => {
    removeModelContext();
    vi.doUnmock("./toolBridge");
  });

  it("reports not-implemented where navigator.modelContext is absent (every browser but Chrome today)", async () => {
    mockToolBridge();
    const { detectWebMcp } = await importWebMcp();

    expect(detectWebMcp()).toEqual({ supported: false, api: null, reason: "not-implemented" });
  });

  it("publishing is a silent no-op without the API — nothing throws, nothing is reported", async () => {
    mockToolBridge();
    const { publishWebMcpTools } = await importWebMcp();

    const state = await publishWebMcpTools("agent-1");

    expect(state.toolNames).toEqual([]);
  });

  it("distinguishes Chrome's flagged testing surface from the shipped one", async () => {
    mockToolBridge();
    Object.defineProperty(navigator, "modelContextTesting", {
      configurable: true,
      writable: true,
      value: { registerTool: () => {}, unregisterTool: () => {} },
    });
    const { detectWebMcp } = await importWebMcp();

    expect(detectWebMcp()).toEqual({ supported: true, api: "modelContextTesting" });
  });
});

describe("edge/webmcp — publishing", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    removeModelContext();
  });

  afterEach(() => {
    removeModelContext();
    vi.doUnmock("./toolBridge");
  });

  it("registers every server tool under a vegaduta_ name, carrying the server's schema verbatim", async () => {
    mockToolBridge();
    const ctx = installModelContext();
    const { publishWebMcpTools } = await importWebMcp();

    const state = await publishWebMcpTools("agent-1");

    expect(state.toolNames).toEqual(["vegaduta_search_knowledge", "vegaduta_create_ticket"]);
    expect(ctx.tools.get("vegaduta_search_knowledge")?.inputSchema).toBe(TOOLS[0].inputSchema);
    // The raw server name must stay visible to a visiting agent.
    expect(ctx.tools.get("vegaduta_create_ticket")?.description).toContain("Create Ticket");
  });

  it("never claims a tool is read-only — the server's schema doesn't say, so neither does this", async () => {
    mockToolBridge();
    const ctx = installModelContext();
    const { publishWebMcpTools } = await importWebMcp();

    await publishWebMcpTools("agent-1");

    for (const tool of ctx.tools.values()) {
      expect(tool.annotations?.readOnlyHint).toBe(false);
    }
  });

  it("execute() lands on the server's guarded endpoint with the RAW tool name and the agent id", async () => {
    const bridge = mockToolBridge({ ok: true, result: "3 results" });
    const ctx = installModelContext();
    const { publishWebMcpTools } = await importWebMcp();
    await publishWebMcpTools("agent-1");

    const result = (await ctx.tools.get("vegaduta_search_knowledge")!.execute({ query: "refunds" })) as {
      ok: boolean;
      result: string;
      content: Array<{ text: string }>;
    };

    expect(bridge.executed).toEqual([
      { toolName: "search_knowledge", args: { query: "refunds" }, agentId: "agent-1" },
    ]);
    expect(result.ok).toBe(true);
    expect(result.result).toBe("3 results");
    // MCP-shaped content too, for agents that only read that.
    expect(result.content[0].text).toBe("3 results");
  });

  it("a server refusal comes back as a readable tool result, not a thrown error", async () => {
    mockToolBridge({ ok: false, code: "rejected", detail: "needs approval" });
    const ctx = installModelContext();
    const { publishWebMcpTools } = await importWebMcp();
    await publishWebMcpTools("agent-1");

    const result = (await ctx.tools.get("vegaduta_search_knowledge")!.execute({})) as {
      ok: boolean;
      error: string;
    };

    expect(result.ok).toBe(false);
    expect(result.error).toContain("needs approval");
  });

  it("one rejected registration doesn't cost the rest of the set", async () => {
    mockToolBridge();
    const ctx = installModelContext({ throwOn: "vegaduta_search_knowledge" });
    const { publishWebMcpTools } = await importWebMcp();

    const state = await publishWebMcpTools("agent-1");

    expect(state.toolNames).toEqual(["vegaduta_create_ticket"]);
    expect(state.skipped).toBe(1);
    expect(ctx.tools.size).toBe(1);
  });

  it("switching agents withdraws the previous agent's tools before publishing the new ones", async () => {
    mockToolBridge();
    const ctx = installModelContext();
    const { publishWebMcpTools } = await importWebMcp();

    await publishWebMcpTools("agent-1");
    await publishWebMcpTools("agent-2");

    expect(ctx.unregistered).toEqual(["vegaduta_search_knowledge", "vegaduta_create_ticket"]);
    expect(ctx.tools.size).toBe(2);
  });

  it("turning the preference off withdraws what is already published", async () => {
    mockToolBridge();
    const ctx = installModelContext();
    const { publishWebMcpTools, getWebMcpPublishState } = await importWebMcp();
    await publishWebMcpTools("agent-1");
    expect(ctx.tools.size).toBe(2);

    localStorage.setItem("edge.webmcp", "0");
    await publishWebMcpTools("agent-1");

    expect(ctx.tools.size).toBe(0);
    expect(getWebMcpPublishState().toolNames).toEqual([]);
  });

  it("publishes nothing when the server has no edge tool surface (404 / older core)", async () => {
    vi.doMock("./toolBridge", () => ({
      fetchEdgeTools: vi.fn(async () => null),
      executeEdgeTool: vi.fn(),
    }));
    const ctx = installModelContext();
    const { publishWebMcpTools } = await importWebMcp();

    const state = await publishWebMcpTools("agent-1");

    expect(state.toolNames).toEqual([]);
    expect(ctx.tools.size).toBe(0);
  });
});

// ---------------------------------------------------------------------------
// Surface-drift regression tests (added 2026-09-17)
//
// WHY THESE EXIST: every test above installs the fake on `navigator` and gives
// it `unregisterTool`. That is not the API any current browser ships — upstream
// moved the surface to `document.modelContext` and REMOVED unregisterTool in
// favour of aborting an AbortSignal passed on the descriptor. So the suite was
// green against a shape that does not exist, which is exactly why the drift
// went unnoticed: 11 passing tests certifying fiction.
//
// These tests pin the real shape. If someone reverts the detection to
// navigator-only, or drops the signal, they fail.
// ---------------------------------------------------------------------------

/** The CURRENT shape: lives on document, no unregisterTool, withdrawal is abort. */
function installDocumentModelContext(): { tools: Map<string, FakeTool> } {
  const tools = new Map<string, FakeTool>();
  Object.defineProperty(document, "modelContext", {
    configurable: true,
    writable: true,
    value: {
      registerTool(tool: FakeTool & { signal?: AbortSignal }) {
        tools.set(tool.name, tool);
        // The browser's own withdrawal mechanism: drop the tool when aborted.
        tool.signal?.addEventListener("abort", () => tools.delete(tool.name));
        return Promise.resolve(undefined);
      },
      // Deliberately NO unregisterTool — that is the point.
    },
  });
  return { tools };
}

function clearDocumentModelContext(): void {
  Object.defineProperty(document, "modelContext", {
    configurable: true,
    writable: true,
    value: undefined,
  });
}

describe("WebMCP current surface (document.modelContext, AbortSignal withdrawal)", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
  });

  afterEach(() => {
    clearDocumentModelContext();
    removeModelContext();
    vi.doUnmock("./toolBridge");
  });

  it("detects document.modelContext — navigator-only detection reported this as unsupported", async () => {
    installDocumentModelContext();
    const { detectWebMcp } = await importWebMcp();

    const support = detectWebMcp();
    expect(support.supported).toBe(true);
    expect(support.api).toBe("documentModelContext");
  });

  it("registers against the document surface", async () => {
    mockToolBridge();
    const ctx = installDocumentModelContext();
    const { publishWebMcpTools } = await importWebMcp();

    const state = await publishWebMcpTools("agent-1");

    expect(state.toolNames.length).toBeGreaterThan(0);
    expect(ctx.tools.size).toBe(state.toolNames.length);
  });

  it("passes an AbortSignal on every descriptor — without it the tool cannot be withdrawn", async () => {
    mockToolBridge();
    const ctx = installDocumentModelContext();
    const { publishWebMcpTools } = await importWebMcp();

    await publishWebMcpTools("agent-1");

    for (const [name, tool] of ctx.tools) {
      expect((tool as { signal?: AbortSignal }).signal, `${name} has no signal`).toBeInstanceOf(
        AbortSignal
      );
    }
  });

  it("THE BUG: unpublish actually withdraws when there is no unregisterTool", async () => {
    // Before the fix this branched on typeof api.unregisterTool === "function",
    // fell through to an absent provideContext, and withdrew NOTHING — tools
    // stayed published to the agent after the user revoked them.
    mockToolBridge();
    const ctx = installDocumentModelContext();
    const { publishWebMcpTools, unpublishWebMcpTools } = await importWebMcp();

    await publishWebMcpTools("agent-1");
    expect(ctx.tools.size).toBeGreaterThan(0);

    unpublishWebMcpTools();

    expect(ctx.tools.size).toBe(0);
  });

  it("re-publishing withdraws the previous set rather than leaking it", async () => {
    mockToolBridge();
    const ctx = installDocumentModelContext();
    const { publishWebMcpTools } = await importWebMcp();

    await publishWebMcpTools("agent-1");
    const firstCount = ctx.tools.size;
    await publishWebMcpTools("agent-2");

    // Not firstCount * 2: the old controller is aborted before the new set
    // registers, so the surface never holds two generations at once.
    expect(ctx.tools.size).toBe(firstCount);
  });

  it("document wins over navigator when both are present", async () => {
    installDocumentModelContext();
    installModelContext();
    const { detectWebMcp } = await importWebMcp();

    expect(detectWebMcp().api).toBe("documentModelContext");
  });
});
