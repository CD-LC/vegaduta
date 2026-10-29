// Contract tests for audiogen.ts — same shape as whisper.test.ts/imagegen.test.ts.
// Real MusicGen inference needs a browser and ~1.8GB download; these tests cover
// the gating contract callers depend on.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("edge/audiogen", () => {
  const originalWorker = (globalThis as { Worker?: unknown }).Worker;
  const originalGpu = (navigator as Navigator & { gpu?: unknown }).gpu;
  const originalMem = (navigator as Navigator & { deviceMemory?: number }).deviceMemory;

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "";
    (globalThis as { Worker?: unknown }).Worker = class {
      postMessage() {}
      addEventListener() {}
      terminate() {}
    };
  });

  afterEach(() => {
    (globalThis as { Worker?: unknown }).Worker = originalWorker;
    Object.defineProperty(navigator, "gpu", { value: originalGpu, configurable: true });
    Object.defineProperty(navigator, "deviceMemory", { value: originalMem, configurable: true });
    vi.unstubAllGlobals();
  });

  it("edgeAudioGenAvailable() is false when the edge flag is off", async () => {
    const { edgeAudioGenAvailable } = await import("./audiogen");
    expect(await edgeAudioGenAvailable()).toBe(false);
  });

  it("edgeAudioGenAvailable() is true with WebGPU", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    Object.defineProperty(navigator, "gpu", { value: {}, configurable: true });
    const { edgeAudioGenAvailable } = await import("./audiogen");
    expect(await edgeAudioGenAvailable()).toBe(true);
  });

  it("edgeAudioGenAvailable() is true on wasm only for 8GB+ devices", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    Object.defineProperty(navigator, "gpu", { value: undefined, configurable: true });
    Object.defineProperty(navigator, "deviceMemory", { value: 8, configurable: true });
    const { edgeAudioGenAvailable } = await import("./audiogen");
    expect(await edgeAudioGenAvailable()).toBe(true);
  });

  it("edgeAudioGenAvailable() is false on a small wasm-only device", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    Object.defineProperty(navigator, "gpu", { value: undefined, configurable: true });
    Object.defineProperty(navigator, "deviceMemory", { value: 4, configurable: true });
    const { edgeAudioGenAvailable } = await import("./audiogen");
    expect(await edgeAudioGenAvailable()).toBe(false);
  });

  it("generateAudioLocally() reports empty-prompt and prompt-too-long", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { generateAudioLocally } = await import("./audiogen");
    expect(await generateAudioLocally("")).toEqual({ handled: false, reason: "empty-prompt" });
    expect(await generateAudioLocally("y".repeat(600))).toEqual({ handled: false, reason: "prompt-too-long" });
  });

  it("generateAudioLocally() reports unsupported when the flag is off", async () => {
    const { generateAudioLocally } = await import("./audiogen");
    expect(await generateAudioLocally("lofi beat")).toEqual({ handled: false, reason: "unsupported" });
  });

  it("generateAudioLocally() reports model-not-ready on an empty device", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    Object.defineProperty(navigator, "gpu", { value: {}, configurable: true });
    const { generateAudioLocally } = await import("./audiogen");
    const result = await generateAudioLocally("lofi beat", 8);
    expect(result.handled).toBe(false);
    if (result.handled === false) expect(result.reason).toBe("model-not-ready");
  });

  it("generateAudioLocally() warms the worker from a cached install instead of refusing", async () => {
    // Same warm-up contract imagegen.ts pins: a cached install + cold worker
    // must load from the local cache (no bytes move), not refuse as if the
    // model were never installed.
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    Object.defineProperty(navigator, "gpu", { value: {}, configurable: true });
    localStorage.setItem("edge.audiogen.cached.musicgen-small", "1");
    const listeners: Array<(e: { data: unknown }) => void> = [];
    (globalThis as { Worker?: unknown }).Worker = class {
      postMessage(msg: { type: string; requestId: number }) {
        queueMicrotask(() => {
          const data =
            msg.type === "load"
              ? { type: "loaded", requestId: msg.requestId }
              : {
                  type: "result",
                  requestId: msg.requestId,
                  wav: new ArrayBuffer(4),
                  sampleRate: 32_000,
                  durationSeconds: 1,
                };
          listeners.forEach((l) => l({ data }));
        });
      }
      addEventListener(_: string, fn: (e: { data: unknown }) => void) {
        listeners.push(fn);
      }
      terminate() {}
    };
    const originalCreateObjectURL = URL.createObjectURL;
    URL.createObjectURL = (() => "blob:fake") as typeof URL.createObjectURL;
    try {
      const { generateAudioLocally } = await import("./audiogen");
      const result = await generateAudioLocally("lofi beat", 8);
      expect(result.handled).toBe(true);
    } finally {
      URL.createObjectURL = originalCreateObjectURL;
    }
  });
});
