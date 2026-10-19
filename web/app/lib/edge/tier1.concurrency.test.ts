// Automated proof for the two verify-pass race-condition fixes in tier1.ts
// (module-scope engine singleton reload/idle-teardown vs. an active
// generation). Written because live browser click-through wasn't available
// in this environment (no authenticated agent workspace) - this drives the
// actual concurrency deterministically via a mocked WebLLM engine instead of
// relying on manual timing in a real browser, which is strictly stronger
// evidence for a race condition than a manual click-through would be.
//
// Two scenarios:
//  1. A second turn wanting a DIFFERENT model while the first is mid-stream
//     must NOT call engine.reload() until the first turn's stream finishes
//     (the critical finding: reload must never happen mid-generation).
//  2. A turn re-acquiring a model for its OWN next message must NOT stall
//     waiting on its own activity (the self-deadlock finding from the second
//     verify pass - fixed by engineReaderCount instead of the broader
//     getEdgeActivity() signal).

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Must be real @mlc-ai/web-llm model ids - tier1.ts's sanitizeTier1Manifest
// drops any tier-1 entry whose id isn't in its KNOWN_WEBLLM_MODEL_IDS
// whitelist (repairWebLlmModelId), regardless of source (fetched or
// fallback), so a made-up test id silently produces an empty model list.
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
      const { indexUrl, weightUrl } = weightUrls(id);
      if (url === indexUrl) {
        return { json: async () => ({ records: [{ dataPath: weightUrl.slice(indexUrl.lastIndexOf("/") + 1) === weightUrl ? weightUrl : "params_shard_0.bin" }] }) };
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

// Shape of a WebLLM streaming chat-completion response, as consumed by
// tier1.ts's reader loop. Both per-test mockImplementation(Once) calls below
// (lines ~155 and ~221) return exactly this shape - declared once here so
// the default vi.fn() implementation (which only ever throws) still gives
// the mock the right static return type for those overrides to satisfy.
type FakeChatStream = {
  [Symbol.asyncIterator](): AsyncGenerator<{ choices: { delta: { content: string } }[] }, void, unknown>;
};
type FakeChatCreate = (opts: { stream?: boolean }) => Promise<FakeChatStream>;

// One shared fake engine instance for every CreateMLCEngine() call, mirroring
// the real module's own module-scope engine singleton - reload() mutates
// this SAME object rather than a fresh one, exactly the shape the bug lived in.
function makeFakeEngine(events: string[]) {
  let currentModel: string | null = null;
  return {
    setInitProgressCallback: vi.fn(),
    interruptGenerate: vi.fn(),
    unload: vi.fn(async () => {
      events.push("unload");
    }),
    reload: vi.fn(async (modelId: string) => {
      events.push(`reload:${modelId}`);
      currentModel = modelId;
    }),
    chat: {
      completions: {
        create: vi.fn<FakeChatCreate>(async (_opts) => {
          throw new Error("override create() per-test");
        }),
      },
    },
    __getCurrentModel: () => currentModel,
    __setCurrentModel: (m: string) => {
      currentModel = m;
    },
  };
}

describe("edge/tier1 concurrency (engine reload vs. active generation)", () => {
  let fakeEngine: ReturnType<typeof makeFakeEngine>;
  let events: string[];

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    events = [];
    fakeEngine = makeFakeEngine(events);
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";

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

    vi.doMock("@mlc-ai/web-llm", () => ({
      CreateMLCEngine: vi.fn(async (modelId: string) => {
        fakeEngine.__setCurrentModel(modelId);
        events.push(`create:${modelId}`);
        return fakeEngine;
      }),
    }));

    // Both models "downloaded" per the local marker each cache-check reads.
    localStorage.setItem(`edge.tier1.cached.${MODEL_A}`, "1");
    localStorage.setItem(`edge.tier1.cached.${MODEL_B}`, "1");
  });

  afterEach(() => {
    delete (globalThis as any).caches;
    vi.unstubAllGlobals();
    vi.doUnmock("@mlc-ai/web-llm");
  });

  it("never reloads the shared engine while a different turn is still streaming from it", async () => {
    const { createTier1Session } = await import("./tier1");
    const { setModelOverride } = await import("./capabilities");

    let releaseChunk2: () => void;
    const chunk2Gate = new Promise<void>((resolve) => {
      releaseChunk2 = resolve;
    });

    // Turn A pins model A explicitly and streams slowly (pauses mid-reply).
    setModelOverride(MODEL_A);
    const sessionA = await createTier1Session(buildCaps());
    fakeEngine.chat.completions.create.mockImplementationOnce(async () => ({
      async *[Symbol.asyncIterator]() {
        events.push("A:chunk1");
        yield { choices: [{ delta: { content: "Hello" } }] };
        events.push("A:pausing");
        await chunk2Gate;
        events.push("A:chunk2");
        yield { choices: [{ delta: { content: " world" } }] };
      },
    }));
    const turnA = sessionA.prompt("hi from A");

    // Wait until turn A is genuinely mid-stream (past its first chunk, paused
    // on the gate) before turn B tries to switch models - poll rather than a
    // fixed sleep so this isn't a timing guess.
    const deadline = Date.now() + 2000;
    while (!events.includes("A:pausing") && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 5));
    }
    expect(events).toContain("A:pausing");
    expect(fakeEngine.reload).not.toHaveBeenCalled();

    // Turn B wants a DIFFERENT model while A is still mid-stream. This is
    // exactly the scenario the critical fix targets: acquireEngine's
    // reload-in-place branch must wait, not reload the live object.
    setModelOverride(MODEL_B);
    const sessionBPromise = createTier1Session(buildCaps());

    // Give the event loop a few turns - if the bug were still present,
    // reload() would already have fired here, corrupting turn A's stream.
    await new Promise((r) => setTimeout(r, 50));
    expect(fakeEngine.reload).not.toHaveBeenCalled();
    expect(events).not.toContain("A:chunk2"); // turn A must still be paused

    // Let turn A finish.
    releaseChunk2!();
    const resultA = await turnA;
    expect(resultA).toBe("Hello world");
    expect(events.indexOf("A:chunk2")).toBeLessThan(
      events.includes("reload:test-model-b") ? events.indexOf("reload:test-model-b") : Infinity
    );

    // NOW turn B's session creation (which needed the reload) should be able
    // to proceed and complete.
    await sessionBPromise;
    expect(fakeEngine.reload).toHaveBeenCalledWith(MODEL_B);
    // The reload must have happened strictly AFTER turn A's stream completed.
    expect(events.indexOf("A:chunk2")).toBeLessThan(events.indexOf(`reload:${MODEL_B}`));
  });

  it("does not stall a turn on its own activity when the engine was switched by someone else since this session started", async () => {
    // The exact self-deadlock shape the second verify pass found: a
    // session's OWN `state.model` is fixed at creation (never re-resolved
    // per prompt() call), so reproducing the bug requires something ELSE to
    // have changed which model is loaded in between this session's own
    // prompt() calls - not the same session's own override change (that
    // never triggers a reload at all, since state.model doesn't change).
    // beginEdgeActivity() fires before acquireEngine() on EVERY prompt()
    // call, first or not - so if session A's second turn needs a reload
    // (because session B's creation loaded a different model in the
    // meantime), and the reload's wait condition were still the broad
    // per-turn activity signal, it would see ITS OWN just-begun activity as
    // "active" and wait on itself up to the full ceiling.
    const { createTier1Session } = await import("./tier1");
    const { setModelOverride } = await import("./capabilities");

    fakeEngine.chat.completions.create.mockImplementation(async () => ({
      async *[Symbol.asyncIterator]() {
        yield { choices: [{ delta: { content: "ok" } }] };
      },
    }));

    setModelOverride(MODEL_A);
    const sessionA = await createTier1Session(buildCaps());
    await expect(sessionA.prompt("first, on model A")).resolves.toBe("ok");
    expect(fakeEngine.reload).not.toHaveBeenCalled(); // first load was a fresh create, not a reload

    // Someone else (another session/tab-equivalent in this same module
    // scope) switches the loaded model to B in between session A's turns.
    setModelOverride(MODEL_B);
    await createTier1Session(buildCaps());
    expect(fakeEngine.reload).toHaveBeenCalledWith(MODEL_B);

    // Session A's own next turn now needs the engine back on model A - this
    // is the call that must not wait on its own just-begun activity.
    const started = Date.now();
    const result = await sessionA.prompt("second - engine was switched away and must switch back");
    const elapsedMs = Date.now() - started;

    expect(result).toBe("ok");
    // Must resolve almost immediately (well under the 90s ceiling) - a
    // regression back to the broad per-turn signal would stall this for the
    // full ACTIVITY_IDLE_WAIT_CEILING_MS since session A's own activity flag
    // is already active (beginEdgeActivity runs before acquireEngine on
    // every prompt() call) by the time it reaches the reload-wait.
    expect(elapsedMs).toBeLessThan(5_000);
    expect(fakeEngine.reload).toHaveBeenCalledWith(MODEL_A);
  });
});
