import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { fetchEdgeMemory, memoryFactsBlock, resetEdgeMemory } from "./edgeMemory";

vi.mock("../auth", () => ({
  auth: {
    isAuthenticated: vi.fn(() => true),
    getToken: vi.fn(async () => "test-token"),
  },
}));
vi.mock("../api", () => ({ API_BASE: "https://api.test" }));

import { auth } from "../auth";

function mockFetch(status: number, body: unknown) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fn = vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    } as unknown as Response;
  });
  vi.stubGlobal("fetch", fn);
  return calls;
}

describe("fetchEdgeMemory", () => {
  beforeEach(() => {
    resetEdgeMemory();
    vi.mocked(auth.isAuthenticated).mockReturnValue(true);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("returns the facts and sends a bearer", async () => {
    const calls = mockFetch(200, { facts: ["likes dark mode", "works at Acme"] });
    const facts = await fetchEdgeMemory("agent-1");

    expect(facts).toEqual(["likes dark mode", "works at Acme"]);
    expect(calls[0].url).toBe("https://api.test/api/edge/memory?agentId=agent-1");
    expect((calls[0].init.headers as Record<string, string>).Authorization).toBe("Bearer test-token");
  });

  it("fetches once per agent per session — the whole point of caching", async () => {
    const calls = mockFetch(200, { facts: ["a"] });
    await fetchEdgeMemory("agent-1");
    await fetchEdgeMemory("agent-1");
    expect(calls).toHaveLength(1);
  });

  it("caps at 24 facts so a large memory cannot eat a 4k context window", async () => {
    mockFetch(200, { facts: Array.from({ length: 100 }, (_, i) => `fact ${i}`) });
    const facts = await fetchEdgeMemory("agent-1");
    expect(facts).toHaveLength(24);
  });

  it("a 404 disables the surface for the session — old core, stop asking", async () => {
    const calls = mockFetch(404, {});
    expect(await fetchEdgeMemory("agent-1")).toBeNull();
    expect(await fetchEdgeMemory("agent-2")).toBeNull();
    expect(calls).toHaveLength(1);
  });

  it("never throws when the network fails — the turn must still happen", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new Error("offline"); }));
    await expect(fetchEdgeMemory("agent-1")).resolves.toBeNull();
  });

  it("does not call the server for a signed-out visitor", async () => {
    vi.mocked(auth.isAuthenticated).mockReturnValue(false);
    const calls = mockFetch(200, { facts: ["x"] });
    expect(await fetchEdgeMemory("agent-1")).toBeNull();
    expect(calls).toHaveLength(0);
  });

  it("does not call the server with no agent id", async () => {
    const calls = mockFetch(200, { facts: ["x"] });
    expect(await fetchEdgeMemory("")).toBeNull();
    expect(calls).toHaveLength(0);
  });

  it("treats an empty fact list as nothing, not an empty block", async () => {
    mockFetch(200, { facts: [] });
    expect(await fetchEdgeMemory("agent-1")).toBeNull();
  });
});

describe("memoryFactsBlock", () => {
  it("is empty for no facts, so no stray header reaches the prompt", () => {
    expect(memoryFactsBlock(null)).toBe("");
    expect(memoryFactsBlock([])).toBe("");
  });

  it("keeps the hosted wording, including the ignore-if-not hedge", () => {
    const block = memoryFactsBlock(["likes dark mode"]);
    // The hedge is load-bearing: a session-scoped blind fetch WILL include
    // facts irrelevant to this turn, and a small model told to use them all
    // will drag them in.
    expect(block).toContain("use if relevant, ignore if not");
    expect(block).toContain("- likes dark mode");
  });
});
