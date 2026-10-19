// Prod signed-in pass 2026-10-05, bug 12: every /agents and /playground load
// logged a 404 for /api/browser/takeover/capabilities (flag off), and agents
// without data logged 404s for quality/summary and improvements/health. A
// browser logs a 404 no matter how the code handles it, so the fix is to not
// ask a question whose answer is already known.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const fetchMock = vi.fn();
vi.mock("./core", () => ({
  apiFetch: (...args: unknown[]) => fetchMock(...args),
  toJson: (res: { json: () => unknown }) => res.json(),
  toJsonQuietOn: () => (res: { json: () => unknown }) => res.json(),
  jsonBody: (b: unknown) => ({ body: JSON.stringify(b) }),
}));
vi.mock("../auth", () => ({ auth: {} }));

const takeoverCaps = vi.fn();
vi.mock("../api", () => ({ api: { getBrowserTakeoverCapabilities: () => takeoverCaps() } }));

const notFound = () => Object.assign(new Error("Not found"), { status: 404 });

describe("negativeCache", () => {
  beforeEach(() => {
    vi.resetModules();
    sessionStorage.clear();
    vi.useRealTimers();
  });

  it("remembers a miss for its TTL, then forgets it", async () => {
    vi.useFakeTimers();
    const { isKnownMissing, rememberMissing } = await import("./negativeCache");
    expect(isKnownMissing("k")).toBe(false);
    rememberMissing("k", 1000);
    expect(isKnownMissing("k")).toBe(true);
    vi.advanceTimersByTime(1001);
    expect(isKnownMissing("k")).toBe(false);
  });

  it("survives a reload within the browser session (sessionStorage), with or without a TTL", async () => {
    const first = await import("./negativeCache");
    first.rememberMissing("flag");
    first.rememberMissing("agent", 60_000);
    vi.resetModules(); // a page reload: module state gone, sessionStorage kept
    const second = await import("./negativeCache");
    expect(second.isKnownMissing("flag")).toBe(true);
    expect(second.isKnownMissing("agent")).toBe(true);
    expect(second.isKnownMissing("other")).toBe(false);
  });

  it("still works when sessionStorage throws", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const { isKnownMissing, rememberMissing } = await import("./negativeCache");
    expect(() => rememberMissing("k")).not.toThrow();
    expect(isKnownMissing("k")).toBe(true);
    setItem.mockRestore();
  });
});

describe("browser takeover capability probe (flag off = 404)", () => {
  beforeEach(() => {
    vi.resetModules();
    sessionStorage.clear();
    takeoverCaps.mockReset();
  });

  it("asks once per browser session when the feature is off, however many times it mounts or reloads", async () => {
    takeoverCaps.mockRejectedValue(notFound());
    const first = await import("../../components/agents/useBrowserTakeoverCapabilities");
    expect(await first.loadBrowserTakeoverCapabilities()).toBeNull();
    expect(await first.loadBrowserTakeoverCapabilities()).toBeNull(); // second mount
    vi.resetModules(); // reload / next page
    const second = await import("../../components/agents/useBrowserTakeoverCapabilities");
    expect(await second.loadBrowserTakeoverCapabilities()).toBeNull();

    expect(takeoverCaps).toHaveBeenCalledTimes(1);
  });

  it("does NOT cache a failure that is not an answer (401 before the token is ready)", async () => {
    takeoverCaps.mockRejectedValueOnce(Object.assign(new Error("Unauthorized"), { status: 401 }));
    takeoverCaps.mockResolvedValueOnce({ enabled: true, fps: 2, idleTimeoutSeconds: 300 });
    const mod = await import("../../components/agents/useBrowserTakeoverCapabilities");
    expect(await mod.loadBrowserTakeoverCapabilities()).toBeNull();
    expect(await mod.loadBrowserTakeoverCapabilities()).toMatchObject({ enabled: true });
    expect(takeoverCaps).toHaveBeenCalledTimes(2);
  });
});

describe("agent health score (404 = not scored yet)", () => {
  beforeEach(() => {
    vi.resetModules();
    sessionStorage.clear();
    fetchMock.mockReset();
  });
  afterEach(() => vi.useRealTimers());

  it("does not re-ask for an agent the sweep has not scored, until the TTL passes", async () => {
    vi.useFakeTimers();
    fetchMock.mockResolvedValue({ status: 404, json: async () => ({}) });
    const { agentsApi, AGENT_HEALTH_MISSING_TTL_MS } = await import("./agents");

    expect(await agentsApi.getAgentHealthScore("a1")).toBeNull();
    expect(await agentsApi.getAgentHealthScore("a1")).toBeNull();
    expect(await agentsApi.getAgentHealthScore("a1")).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // Another agent is its own question.
    await agentsApi.getAgentHealthScore("a2");
    expect(fetchMock).toHaveBeenCalledTimes(2);

    vi.advanceTimersByTime(AGENT_HEALTH_MISSING_TTL_MS + 1);
    await agentsApi.getAgentHealthScore("a1");
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("a scored agent is always fetched fresh", async () => {
    fetchMock.mockResolvedValue({ status: 200, json: async () => ({ score: 0.9, status: "HEALTHY" }) });
    const { agentsApi } = await import("./agents");
    await agentsApi.getAgentHealthScore("a1");
    await agentsApi.getAgentHealthScore("a1");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

describe("quality summary is only requested when there are evaluations", () => {
  it("empty history means the summary would 404 - do not ask", async () => {
    const { hasQualityRecords } = await import("./quality");
    const empty = { agentId: "a", timestamps: [] as string[], overallScores: [] as number[], scoresByType: {}, recordCount: 0 };
    expect(hasQualityRecords(empty)).toBe(false);
    expect(hasQualityRecords({ ...empty, timestamps: ["t"], overallScores: [0.8], recordCount: 1 })).toBe(true);
    // No recordCount on an older core: fall back to the list itself.
    expect(hasQualityRecords({ agentId: "a", timestamps: [], overallScores: [], scoresByType: {} } as never)).toBe(false);
    // History failed to load: unknown, so the summary is still tried.
    expect(hasQualityRecords(null)).toBe(true);
  });
});
