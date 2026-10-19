// Lightweight tests for kokoro.ts's public API surface — same rationale and
// scope as the sibling whisper.test.ts (see its header comment): no worker/
// model/network interaction, just the contract CallPanel's fallback chain
// actually depends on: never-throws, honest gating order, and that a never-
// installed model never silently "succeeds" with no audio.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("edge/audio/kokoro", () => {
  const originalWorker = (globalThis as any).Worker;

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "";
    (globalThis as any).Worker = class {
      constructor() {}
      postMessage() {}
      addEventListener() {}
      terminate() {}
    };
  });

  afterEach(() => {
    (globalThis as any).Worker = originalWorker;
    vi.unstubAllGlobals();
  });

  it("edgeTtsAvailable() is false when the edge flag is off", async () => {
    const { edgeTtsAvailable } = await import("./kokoro");
    expect(await edgeTtsAvailable()).toBe(false);
  });

  describe("ttsModelFilesPresent() - the guard for pages that must never cause a download", () => {
    const stubCaches = (urls: string[] | null) =>
      vi.stubGlobal("caches", {
        has: async () => urls !== null,
        open: async () => ({ keys: async () => (urls || []).map((url) => ({ url })) }),
      });

    it("is false when the model was never installed, without touching the cache", async () => {
      const has = vi.fn(async () => true);
      vi.stubGlobal("caches", { has, open: vi.fn() });
      const { ttsModelFilesPresent } = await import("./kokoro");
      expect(await ttsModelFilesPresent()).toBe(false);
      expect(has).not.toHaveBeenCalled();
    });

    it("is false when the installed marker is set but the browser evicted the files", async () => {
      localStorage.setItem("edge.tts.cached.kokoro-82m", "1");
      stubCaches(null);
      const { ttsModelFilesPresent } = await import("./kokoro");
      expect(await ttsModelFilesPresent()).toBe(false);
      stubCaches(["https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/config.json"]);
      expect(await ttsModelFilesPresent()).toBe(false);
    });

    it("is true when the marker is set and the weights are in the cache", async () => {
      localStorage.setItem("edge.tts.cached.kokoro-82m", "1");
      stubCaches(["https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_quantized.onnx"]);
      const { ttsModelFilesPresent } = await import("./kokoro");
      expect(await ttsModelFilesPresent()).toBe(true);
    });

    it("answers false, never rejects, when the Cache API is missing or throws", async () => {
      localStorage.setItem("edge.tts.cached.kokoro-82m", "1");
      vi.stubGlobal("caches", undefined);
      const { ttsModelFilesPresent } = await import("./kokoro");
      expect(await ttsModelFilesPresent()).toBe(false);
      vi.stubGlobal("caches", {
        has: async () => {
          throw new Error("SecurityError");
        },
      });
      expect(await ttsModelFilesPresent()).toBe(false);
    });
  });

  it("edgeTtsAvailable() is true with the flag on, Worker support, and WASM", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { edgeTtsAvailable } = await import("./kokoro");
    expect(await edgeTtsAvailable()).toBe(true);
  });

  it("synthesizeSpeechLocally() never throws and reports edge-disabled when the flag is off", async () => {
    const { synthesizeSpeechLocally } = await import("./kokoro");
    const result = await synthesizeSpeechLocally("hello there");
    expect(result).toEqual({ handled: false, reason: "edge-disabled" });
  });

  it("synthesizeSpeechLocally() reports no-text for empty/whitespace input", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { synthesizeSpeechLocally } = await import("./kokoro");
    expect(await synthesizeSpeechLocally("   ")).toEqual({ handled: false, reason: "no-text" });
    expect(await synthesizeSpeechLocally("")).toEqual({ handled: false, reason: "no-text" });
  });

  it("synthesizeSpeechLocally() reports model-not-ready when the model was never installed", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { synthesizeSpeechLocally, isTtsModelCached } = await import("./kokoro");
    expect(isTtsModelCached()).toBe(false);
    const result = await synthesizeSpeechLocally("On-device replies are ready.");
    expect(result).toEqual({ handled: false, reason: "model-not-ready" });
  });

  it("this gating is exactly what CallPanel's fallback chain relies on to never call into a missing model", async () => {
    // Regression guard for the CallPanel wiring contract: as long as the
    // model was never installed, synthesizeSpeechLocally must resolve
    // handled:false (never throw, never hang) so the existing server-audio
    // -> browser-speechSynthesis chain in CallPanel runs unmodified.
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { synthesizeSpeechLocally } = await import("./kokoro");
    const result = await synthesizeSpeechLocally("Short reply chunk.");
    expect(result.handled).toBe(false);
  });

  it("ensureTtsModel() rejects with a display-safe error when the device can't run TTS", async () => {
    const { ensureTtsModel } = await import("./kokoro");
    await expect(ensureTtsModel()).rejects.toThrow(/not available/i);
  });

  it("isTtsModelCached() reflects nothing installed on a clean device", async () => {
    const { isTtsModelCached } = await import("./kokoro");
    expect(isTtsModelCached()).toBe(false);
  });
});
