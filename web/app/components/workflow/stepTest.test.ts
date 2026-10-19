// Pure helpers behind "Run this step" plus the response classification of
// testWorkflowNode. apiFetch is mocked at the seam - no network.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
vi.mock("../../lib/api/core", () => ({
  apiFetch: apiFetchMock,
  jsonBody: (body: unknown) => ({
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  }),
}));

import {
  MOCK_KEY_PREFIX,
  TESTABLE_TYPES,
  buildTestBody,
  isTestable,
  loadMocks,
  saveMocks,
  setMock,
  stepStatusKey,
  testWorkflowNode,
  untestableReason,
  upstreamIds,
} from "./stepTest";

function res(status: number, body: string): Response {
  return new Response(body, { status });
}

describe("isTestable / untestableReason", () => {
  it("allows the contract's testable types", () => {
    for (const t of ["agent", "supervisor", "code", "condition", "knowledge", "trigger"]) {
      expect(isTestable(t, {})).toBe(true);
    }
    expect(TESTABLE_TYPES.has("http")).toBe(true);
  });

  it("allows http only for GET (default GET when unset)", () => {
    expect(isTestable("http", {})).toBe(true);
    expect(isTestable("http", { method: "get" })).toBe(true);
    expect(isTestable("http", { method: "POST" })).toBe(false);
    expect(untestableReason("http", { method: "delete" })).toMatch(/DELETE/);
  });

  it("allows media except render", () => {
    expect(isTestable("media", { operation: "tts" })).toBe(true);
    expect(isTestable("media", { operation: "render_status" })).toBe(true);
    expect(isTestable("media", { operation: "render" })).toBe(false);
  });

  it("refuses side-effecting, human-wait and multi-node types with a reason", () => {
    for (const t of ["bulk_email", "crm_lead", "device_control", "tool", "output", "workflow"]) {
      expect(untestableReason(t, {})).toMatch(/side effects/);
    }
    for (const t of ["approval", "human_input", "voice_menu"]) {
      expect(untestableReason(t, {})).toMatch(/waits for a person/);
    }
    for (const t of ["loop", "parallel", "fan_out", "sub_flow"]) {
      expect(untestableReason(t, {})).toMatch(/runs other nodes/);
    }
  });

  it("refuses requiresApproval and agent inputMapping like the server", () => {
    expect(isTestable("code", { requiresApproval: true })).toBe(false);
    expect(isTestable("agent", { inputMapping: { topic: "{{input}}" } })).toBe(false);
    expect(isTestable("agent", { inputMapping: {} })).toBe(true);
  });

  it("refuses unknown/missing types and tolerates null config", () => {
    expect(isTestable("mystery", null)).toBe(false);
    expect(isTestable(undefined)).toBe(false);
    expect(isTestable("AGENT", undefined)).toBe(true);
  });
});

describe("upstreamIds", () => {
  it("returns direct sources in edge order, de-duplicated, ignoring self-loops", () => {
    const edges = [
      { source: "a", target: "x" },
      { source: "b", target: "y" },
      { source: "c", target: "x" },
      { source: "a", target: "x" },
      { source: "x", target: "x" },
    ];
    expect(upstreamIds("x", edges)).toEqual(["a", "c"]);
    expect(upstreamIds("z", edges)).toEqual([]);
    expect(upstreamIds("x", null)).toEqual([]);
  });
});

describe("mock store", () => {
  beforeEach(() => localStorage.clear());

  it("round-trips under the vd.wf.mocks. prefix", () => {
    expect(saveMocks("wf1", { n1: "hello", n2: "{\"a\":1}" })).toBe(true);
    expect(localStorage.getItem(`${MOCK_KEY_PREFIX}wf1`)).not.toBeNull();
    expect(loadMocks("wf1")).toEqual({ n1: "hello", n2: "{\"a\":1}" });
    expect(loadMocks("wf2")).toEqual({});
  });

  it("drops non-string values and survives corrupt JSON", () => {
    localStorage.setItem(`${MOCK_KEY_PREFIX}wf1`, JSON.stringify({ ok: "x", bad: 3 }));
    expect(loadMocks("wf1")).toEqual({ ok: "x" });
    localStorage.setItem(`${MOCK_KEY_PREFIX}wf1`, "{nope");
    expect(loadMocks("wf1")).toEqual({});
  });

  it("setMock sets and clears; an empty map removes the key", () => {
    expect(setMock("wf1", "n1", "v")).toEqual({ n1: "v" });
    expect(setMock("wf1", "n1", null)).toEqual({});
    expect(localStorage.getItem(`${MOCK_KEY_PREFIX}wf1`)).toBeNull();
  });
});

describe("buildTestBody", () => {
  it("omits a blank input so pinned inputs are used", () => {
    expect(buildTestBody("  ", { a: "x", b: "" })).toEqual({ pinnedInputs: { a: "x" } });
  });
  it("keeps typed input verbatim", () => {
    expect(buildTestBody(" hi ", {})).toEqual({ input: " hi " });
  });
});

describe("stepStatusKey", () => {
  it("never relies on colour alone - maps to a named key", () => {
    expect(stepStatusKey("OK")).toBe("ok");
    expect(stepStatusKey("ERROR")).toBe("error");
    expect(stepStatusKey("SKIPPED")).toBe("skipped");
    expect(stepStatusKey("AWAITING_APPROVAL")).toBe("approval");
    expect(stepStatusKey(null)).toBe("other");
  });
  it("treats a PINNED node as success-like", () => {
    expect(stepStatusKey("PINNED")).toBe("ok");
    expect(stepStatusKey("pinned")).toBe("ok");
  });
});

describe("testWorkflowNode", () => {
  beforeEach(() => apiFetchMock.mockReset());

  it("POSTs to the node test route and returns the entry", async () => {
    apiFetchMock.mockResolvedValue(res(200, JSON.stringify({ nodeId: "n1", status: "OK", durationMs: 12 })));
    const r = await testWorkflowNode("wf1", "n1", { input: "hi" });
    expect(r).toEqual({ ok: true, entry: { nodeId: "n1", status: "OK", durationMs: 12 } });
    const [path, opts] = apiFetchMock.mock.calls[0];
    expect(path).toBe("/api/workflows/wf1/nodes/n1/test");
    expect(opts.method).toBe("POST");
    expect(JSON.parse(opts.body)).toEqual({ input: "hi" });
  });

  it("409 -> blocked with the server's reason", async () => {
    apiFetchMock.mockResolvedValue(res(409, JSON.stringify({ error: "'tool' has real side effects" })));
    expect(await testWorkflowNode("wf", "n", {})).toEqual({ ok: false, kind: "blocked", message: "'tool' has real side effects" });
  });

  it("404 unknown node (JSON with a node message) -> not-found", async () => {
    apiFetchMock.mockResolvedValue(res(404, JSON.stringify({ error: "Node 'n' is not in this workflow's saved graph - save the canvas first." })));
    const r = await testWorkflowNode("wf", "n", {});
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.kind).toBe("not-found");
  });

  it("404 route missing (older core) -> unsupported, JSON or not", async () => {
    apiFetchMock.mockResolvedValue(res(404, JSON.stringify({ error: "No endpoint matches this request." })));
    let r = await testWorkflowNode("wf", "n", {});
    if (!r.ok) expect(r.kind).toBe("unsupported");
    apiFetchMock.mockResolvedValue(res(404, "<html>Not Found</html>"));
    r = await testWorkflowNode("wf", "n", {});
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.kind).toBe("unsupported");
  });

  it("405/501 -> unsupported; 500 and network errors -> error", async () => {
    apiFetchMock.mockResolvedValue(res(405, ""));
    let r = await testWorkflowNode("wf", "n", {});
    if (!r.ok) expect(r.kind).toBe("unsupported");
    apiFetchMock.mockResolvedValue(res(500, JSON.stringify({ error: "boom" })));
    r = await testWorkflowNode("wf", "n", {});
    expect(r).toEqual({ ok: false, kind: "error", message: "boom" });
  });

  it("network failure -> error, never throws", async () => {
    apiFetchMock.mockImplementationOnce(() => Promise.reject(new TypeError("Failed to fetch")));
    const r = await testWorkflowNode("wf", "n", {});
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.kind).toBe("error");
  });
});
