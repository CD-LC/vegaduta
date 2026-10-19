// Automated coverage for the "warm-up before first request" scenario
// (2026-08-10 edge/WebLLM audit finding #12): prewarmTier1Engine's own doc
// comment claims a background prewarm means "the person's real first message
// [doesn't pay] the full engine-load cost synchronously" — i.e. a prewarm
// that already loaded the engine must make the FIRST createTier1Session/
// prompt() a cache hit, not a second CreateMLCEngine call. Before this file,
// none of the four existing edge test files (tier1.budget, tier1.concurrency,
// tierRouter.policy, routingState) exercised prewarmTier1Engine at all.
//
// Also covers the primeTier1EngineShaderCompile call added alongside the
// warm-up fix (finding #1): it must fire once per successful engine
// ACQUISITION (a fresh CreateMLCEngine or a reload — see acquireEngine's
// shared call site at the end of both branches), never once per TURN. A
// naive "call it in runTurn instead" implementation would fire it on every
// prompt() and make every reply pay a discarded extra generation.
//
// Style follows the sibling tier1.concurrency.test.ts exactly: real
// @mlc-ai/web-llm model ids (sanitizeTier1Manifest drops anything else),
// vi.doMock("@mlc-ai/web-llm", ...) with a shared fake engine object mutated
// by reload() the same way the real module-scope singleton is, a fake Cache
// API + fetch mock for the manifest endpoint, and poll-until-event waits
// instead of fixed sleeps for anything timing-sensitive.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const MODEL_A = "Hermes-2-Pro-Llama-3-8B-q4f16_1-MLC";
const MODEL_B = "Hermes-3-Llama-3.2-3B-q4f16_1-MLC";

function weightUrls(modelId: string) {
  const base = `https://huggingface.co/mlc-ai/${modelId}/resolve/main/`;
  return { base, indexUrl: `${base}ndarray-cache.json`, weightUrl: `${base}params_shard_0.bin` };
}

class FakeCache {
  async keys() {
    return [MODEL_A, MODEL_B].flatMap((id) => {
      const { indexUrl, weightUrl } = weightUrls(id);
      return [{ url: indexUrl }, { url: weightUrl }];
    });
  }
  async match(url: string) {
    for (const id of [MODEL_A, MODEL_B]) {
      const { indexUrl } = weightUrls(id);
      if (url === indexUrl) {
        return { json: async () => ({ records: [{ dataPath: "params_shard_0.bin" }] }) };
      }
    }
    return undefined;
  }
}

function buildCaps() {
  return {
    version: 1 as const,
    detectedAt: 0,
    webgpu: { available: true, maxBufferSize: 1_000_000_000, maxStorageBufferBindingSize: 1_000_000_000 },
    promptApi: { shape: "none" as const, availability: "unavailable" as const },
    deviceMemoryGB: 8,
    hardwareConcurrency: 8,
    connection: { saveData: false, effectiveType: "4g" },
  };
}

/** One shared fake engine, mirroring tier1.ts's own module-scope singleton —
 * reload() mutates this SAME object rather than a fresh one. The
 * chat.completions.create mock distinguishes the priming call
 * (primeTier1EngineShaderCompile's `{ messages, max_tokens: 1 }`, non-
 * streaming) from a real turn's call (runTurn/streamCompletion's
 * `{ stream: true, messages }`) purely by shape, exactly like the real
 * engine would see two structurally different requests. */
function makeFakeEngine(events: string[]) {
  return {
    setInitProgressCallback: vi.fn(),
    interruptGenerate: vi.fn(),
    unload: vi.fn(async () => {
      events.push("unload");
    }),
    reload: vi.fn(async (modelId: string) => {
      events.push(`reload:${modelId}`);
    }),
    chat: {
      completions: {
        create: vi.fn(async (opts: { stream?: boolean; max_tokens?: number }) => {
          if (opts && opts.max_tokens === 1) {
            events.push("prime");
            // Priming discards the result — any resolved shape is fine.
            return { choices: [{ message: { content: "" } }] };
          }
          events.push("turn");
          return {
            async *[Symbol.asyncIterator]() {
              yield { choices: [{ delta: { content: "ok" } }] };
            },
          };
        }),
      },
    },
  };
}

describe("edge/tier1 — warm-up before first request (audit finding #12)", () => {
  let fakeEngine: ReturnType<typeof makeFakeEngine>;
  let events: string[];
  let createEngineMock: ReturnType<typeof vi.fn>;
  let debugSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    events = [];
    fakeEngine = makeFakeEngine(events);
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";

    debugSpy = vi.spyOn(console, "debug").mockImplementation(() => {});

    (globalThis as any).caches = {
      has: async () => true,
      open: async () => new FakeCache(),
    };

    (globalThis as any).fetch = vi.fn(async (url: string) => {
      if (String(url).includes("/api/edge/manifest")) {
        return {
          ok: true,
          json: async () => ({
            version: "1",
            models: [
              { id: MODEL_A, displayName: "Model A", sizeBytes: 1_000_000, minDeviceMemoryGB: 0, minMaxBufferSize: 0, tier: 1, speedTier: "fast" },
              { id: MODEL_B, displayName: "Model B", sizeBytes: 1_000_000, minDeviceMemoryGB: 0, minMaxBufferSize: 0, tier: 1, speedTier: "fast" },
            ],
          }),
        };
      }
      // Tool discovery / anything else - treat as unavailable, no tools.
      return { ok: false, status: 404, json: async () => ({}) };
    });

    createEngineMock = vi.fn(async (modelId: string) => {
      events.push(`create:${modelId}`);
      return fakeEngine;
    });
    vi.doMock("@mlc-ai/web-llm", () => ({
      CreateMLCEngine: createEngineMock,
    }));

    // Both models "downloaded" per the local marker each cache-check reads.
    localStorage.setItem(`edge.tier1.cached.${MODEL_A}`, "1");
    localStorage.setItem(`edge.tier1.cached.${MODEL_B}`, "1");
  });

  afterEach(() => {
    delete (globalThis as any).caches;
    vi.unstubAllGlobals();
    vi.doUnmock("@mlc-ai/web-llm");
    debugSpy.mockRestore();
  });

  /** Poll on the structured console.debug("[edge:tier1]", event) calls
   * logTier1Event emits, rather than a fixed sleep, until a "prewarm" event
   * with the given outcome has landed — deterministic completion signal for
   * prewarmTier1Engine's fire-and-forget internal async work. */
  async function waitForPrewarmOutcome(outcome: string, timeoutMs = 2000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const hit = debugSpy.mock.calls.some(
        (call: unknown[]) => call[0] === "[edge:tier1]" && (call[1] as any)?.phase === "prewarm" && (call[1] as any)?.outcome === outcome
      );
      if (hit) return;
      await new Promise((r) => setTimeout(r, 5));
    }
    throw new Error(`timed out waiting for prewarm outcome "${outcome}"`);
  }

  it("prewarmTier1Engine loading the engine ahead of time makes the first session a cache hit — no redundant CreateMLCEngine call", async () => {
    const { createTier1Session, prewarmTier1Engine, isTier1EngineWarm } = await import("./tier1");
    const { setModelOverride } = await import("./capabilities");

    // Pinned explicitly (matches the sibling concurrency test's convention)
    // so the model pick is deterministic rather than depending on the
    // auto-selector's tie-break between two equally-sized fast models.
    setModelOverride(MODEL_A);

    // Background warm-up, exactly as ChatPanel/AgentPlayground/
    // IntelligenceSourceControl call it on mount/selection — fire-and-forget,
    // no await available on the real return type (void).
    prewarmTier1Engine(buildCaps());
    await waitForPrewarmOutcome("warmed");

    expect(createEngineMock).toHaveBeenCalledTimes(1);
    expect(createEngineMock.mock.calls[0][0]).toBe(MODEL_A);
    expect(isTier1EngineWarm(MODEL_A)).toBe(true);

    // The turn arrives some time later — the exact scenario the audit
    // comment describes ("a turn arriving seconds after mount"). This must
    // be a pure cache hit: createTier1Session's own acquireEngine call finds
    // engineInstance already set to MODEL_A and returns it synchronously,
    // never touching CreateMLCEngine again.
    const session = await createTier1Session(buildCaps());
    expect(createEngineMock).toHaveBeenCalledTimes(1);

    // The actual first reply itself must not trigger a second load either.
    const reply = await session.prompt("hello");
    expect(reply).toBe("ok");
    expect(createEngineMock).toHaveBeenCalledTimes(1);
  });

  it("without a prewarm, the first session still only loads the engine once (sanity baseline)", async () => {
    const { createTier1Session } = await import("./tier1");
    const { setModelOverride } = await import("./capabilities");

    setModelOverride(MODEL_A);
    await createTier1Session(buildCaps());
    expect(createEngineMock).toHaveBeenCalledTimes(1);
  });

  it("primeTier1EngineShaderCompile fires once per successful engine acquisition, never once per turn", async () => {
    const { createTier1Session } = await import("./tier1");
    const { setModelOverride } = await import("./capabilities");

    setModelOverride(MODEL_A);
    const session = await createTier1Session(buildCaps());

    // One acquisition so far (the initial CreateMLCEngine) -> exactly one
    // priming call, discarded before any real turn happens.
    expect(events.filter((e) => e === "prime")).toHaveLength(1);
    expect(events.filter((e) => e === "create:" + MODEL_A)).toHaveLength(1);

    // Two real turns on the SAME already-warm engine must not add any more
    // priming calls — this is the literal claim being tested: priming is
    // tied to acquisition, not to every prompt() call.
    await expect(session.prompt("first turn")).resolves.toBe("ok");
    await expect(session.prompt("second turn")).resolves.toBe("ok");
    expect(events.filter((e) => e === "prime")).toHaveLength(1);
    expect(events.filter((e) => e === "turn")).toHaveLength(2);

    // A second, genuinely new acquisition (a model switch forcing
    // acquireEngine's reload-in-place branch) must prime again — proving the
    // count tracks ACQUISITIONS, not "once ever" or "once per turn". No new
    // CreateMLCEngine call is expected here: reload() reuses the same engine
    // object.
    setModelOverride(MODEL_B);
    await createTier1Session(buildCaps());
    expect(fakeEngine.reload).toHaveBeenCalledWith(MODEL_B);
    expect(createEngineMock).toHaveBeenCalledTimes(1);
    expect(events.filter((e) => e === "prime")).toHaveLength(2);
  });
});
