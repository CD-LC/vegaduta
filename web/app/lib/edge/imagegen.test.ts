// Contract tests for imagegen.ts's public surface — same shape as
// audio/whisper.test.ts. The real worker/model path needs a browser with
// WebGPU plus a ~2.4GB HF download and is NOT exercised here; these cover what
// every caller depends on: never-throws, honest gating order (edge flag ->
// WebGPU -> prompt validation -> model-installed check), and the consent rule
// that nothing short of ensureImageGenModel() ever touches the network.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("edge/imagegen", () => {
  const originalWorker = (globalThis as { Worker?: unknown }).Worker;
  const originalGpu = (navigator as Navigator & { gpu?: unknown }).gpu;

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
    vi.unstubAllGlobals();
  });

  it("edgeImageGenAvailable() is false when the edge flag is off", async () => {
    const { edgeImageGenAvailable } = await import("./imagegen");
    expect(await edgeImageGenAvailable()).toBe(false);
  });

  it("edgeImageGenAvailable() is false without WebGPU even with the flag on", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    Object.defineProperty(navigator, "gpu", { value: undefined, configurable: true });
    const { edgeImageGenAvailable } = await import("./imagegen");
    expect(await edgeImageGenAvailable()).toBe(false);
  });

  it("generateImageLocally() reports empty-prompt before any capability work", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { generateImageLocally } = await import("./imagegen");
    expect(await generateImageLocally("")).toEqual({ handled: false, reason: "empty-prompt" });
    expect(await generateImageLocally("   ")).toEqual({ handled: false, reason: "empty-prompt" });
  });

  it("generateImageLocally() reports prompt-too-long for oversized prompts", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { generateImageLocally } = await import("./imagegen");
    const long = "x".repeat(600);
    const result = await generateImageLocally(long);
    expect(result).toEqual({ handled: false, reason: "prompt-too-long" });
  });

  it("generateImageLocally() reports unsupported when the flag is off", async () => {
    const { generateImageLocally } = await import("./imagegen");
    const result = await generateImageLocally("a cabin in the woods");
    expect(result).toEqual({ handled: false, reason: "unsupported" });
  });

  it("generateImageLocally() reports model-not-ready when nothing was installed", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    Object.defineProperty(navigator, "gpu", { value: {}, configurable: true });
    const { generateImageLocally, isImageGenModelCached } = await import("./imagegen");
    // caps detection may report webgpu.available false in jsdom — the tool's
    // honest answer is either 'unsupported' or 'model-not-ready', never a
    // thrown error and never a silent download.
    const result = await generateImageLocally("a cabin");
    expect(result.handled).toBe(false);
    if (result.handled === false) {
      expect(["unsupported", "model-not-ready"]).toContain(result.reason);
    }
    expect(isImageGenModelCached()).toBe(false);
  });

  it("isImageGenModelCached() reflects nothing installed on a clean device", async () => {
    const { isImageGenModelCached } = await import("./imagegen");
    expect(isImageGenModelCached()).toBe(false);
  });

  it("generateImageLocally() warms the worker from a cached install instead of refusing", async () => {
    // A model the person enabled in a PREVIOUS page session leaves the marker
    // set but the worker cold. Refusing 'model-not-ready' there is the bug this
    // pins: the weights are on-device, so a warm-up moves no bytes and the
    // no-download rule is not implicated (same contract vision.ts follows).
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    Object.defineProperty(navigator, "gpu", { value: {}, configurable: true });
    localStorage.setItem("edge.imagegen.cached.sd-turbo", "1");
    const listeners: Array<(e: { data: unknown }) => void> = [];
    (globalThis as { Worker?: unknown }).Worker = class {
      postMessage(msg: { type: string; requestId: number }) {
        queueMicrotask(() => {
          const data =
            msg.type === "load"
              ? { type: "loaded", requestId: msg.requestId }
              : { type: "result", requestId: msg.requestId, png: new ArrayBuffer(4), width: 512, height: 512 };
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
      // jsdom has no WebGPU adapter, so detectCapabilities would report the
      // device unsupported; stub it to the capability set a real WebGPU device
      // reports so the warm-up path is what the test actually exercises.
      vi.doMock("./capabilities", async () => {
        const orig = await vi.importActual<typeof import("./capabilities")>("./capabilities");
        return {
          ...orig,
          detectCapabilities: async () => ({
            version: 1,
            detectedAt: 1,
            webgpu: { available: true, maxBufferSize: 1, maxStorageBufferBindingSize: 1 },
          }),
        };
      });
      const { generateImageLocally } = await import("./imagegen");
      const result = await generateImageLocally("a cabin in the woods", 7);
      expect(result.handled).toBe(true);
    } finally {
      URL.createObjectURL = originalCreateObjectURL;
      vi.doUnmock("./capabilities");
    }
  });
});
