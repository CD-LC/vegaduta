import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// The engine singleton is shared mutable state; every test starts from a fresh
// module so a leaked handle from one test cannot colour the next.
async function load() {
  vi.resetModules();
  return import("./engineState");
}

function fakeEngine() {
  return { unload: vi.fn(async () => {}) } as unknown as import("@mlc-ai/web-llm").MLCEngineInterface;
}

describe("tier1 engine state", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("starts empty and is not warm", async () => {
    const { tier1Engine, isTier1EngineWarm } = await load();
    expect(tier1Engine.instance).toBeNull();
    expect(tier1Engine.readerCount).toBe(0);
    expect(isTier1EngineWarm("m")).toBe(false);
  });

  it("is warm only for the loaded model and not while loading or tearing down", async () => {
    const { tier1Engine, isTier1EngineWarm } = await load();
    tier1Engine.instance = fakeEngine();
    tier1Engine.modelId = "m";
    expect(isTier1EngineWarm("m")).toBe(true);
    expect(isTier1EngineWarm("other")).toBe(false);
    tier1Engine.inFlight = new Promise(() => {});
    expect(isTier1EngineWarm("m")).toBe(false);
    tier1Engine.inFlight = null;
    tier1Engine.tearingDown = Promise.resolve();
    expect(isTier1EngineWarm("m")).toBe(false);
  });

  it("waitForEdgeActivityIdle resolves at once with no readers and waits for readers to drain", async () => {
    const { tier1Engine, waitForEdgeActivityIdle } = await load();
    await expect(waitForEdgeActivityIdle()).resolves.toBeUndefined();
    tier1Engine.readerCount = 1;
    let done = false;
    void waitForEdgeActivityIdle().then(() => {
      done = true;
    });
    await vi.advanceTimersByTimeAsync(200);
    expect(done).toBe(false);
    tier1Engine.readerCount = 0;
    await vi.advanceTimersByTimeAsync(100);
    expect(done).toBe(true);
  });

  it("waitForEdgeActivityIdle gives up at the ceiling instead of wedging", async () => {
    const { tier1Engine, waitForEdgeActivityIdle } = await load();
    tier1Engine.readerCount = 3;
    let done = false;
    void waitForEdgeActivityIdle().then(() => {
      done = true;
    });
    await vi.advanceTimersByTimeAsync(91_000);
    expect(done).toBe(true);
  });

  it("releaseTier1EngineForBackendChange clears the handles and unloads in the background", async () => {
    const { tier1Engine, releaseTier1EngineForBackendChange } = await load();
    const engine = fakeEngine();
    tier1Engine.instance = engine;
    tier1Engine.modelId = "m";
    tier1Engine.cacheBackend = "cache";
    releaseTier1EngineForBackendChange();
    expect(tier1Engine.instance).toBeNull();
    expect(tier1Engine.modelId).toBeNull();
    expect(tier1Engine.cacheBackend).toBeNull();
    await vi.advanceTimersByTimeAsync(10);
    expect(engine.unload).toHaveBeenCalledTimes(1);
  });

  it("never unloads a service-worker-backed engine on release", async () => {
    const { tier1Engine, releaseTier1EngineForBackendChange } = await load();
    const engine = fakeEngine();
    tier1Engine.instance = engine;
    tier1Engine.isServiceWorkerBacked = true;
    releaseTier1EngineForBackendChange();
    await vi.advanceTimersByTimeAsync(10);
    expect(tier1Engine.instance).toBeNull();
    expect(engine.unload).not.toHaveBeenCalled();
  });

  it("idle teardown defers while a reader is active, then unloads", async () => {
    const { tier1Engine, teardownIdleTier1Engine } = await load();
    const engine = fakeEngine();
    tier1Engine.instance = engine;
    tier1Engine.modelId = "m";
    tier1Engine.readerCount = 1;
    await teardownIdleTier1Engine();
    expect(engine.unload).not.toHaveBeenCalled();
    expect(tier1Engine.instance).toBe(engine);
    tier1Engine.readerCount = 0;
    await teardownIdleTier1Engine();
    expect(engine.unload).toHaveBeenCalledTimes(1);
    expect(tier1Engine.instance).toBeNull();
  });
});
