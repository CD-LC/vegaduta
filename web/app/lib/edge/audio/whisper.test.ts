// Lightweight tests for whisper.ts's public API surface. No test file exists
// yet for the sibling vision.ts module this one was modeled on (checked
// before writing these), so this establishes the pattern for the edge/audio
// modules rather than following an established test convention.
//
// The real worker/model path is NOT exercised here (that needs a browser,
// WebGPU/WASM and a real network fetch) - these tests cover the contract
// every caller actually depends on: never-throws, honest gating order
// (edge flag -> Worker support -> input validation -> model-installed
// check), and that ensureSttModel() refuses on an unavailable device.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

describe("edge/audio/whisper", () => {
  const originalWorker = (globalThis as any).Worker;

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "";
    // jsdom has no Worker global; stub a harmless one so "Worker support"
    // checks pass without ever constructing a real worker in these tests
    // (the code paths under test all return before touching `new Worker`).
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

  it("edgeSttAvailable() is false when the edge flag is off", async () => {
    const { edgeSttAvailable } = await import("./whisper");
    expect(await edgeSttAvailable()).toBe(false);
  });

  it("edgeSttAvailable() is true with the flag on, Worker support, and WASM", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { edgeSttAvailable } = await import("./whisper");
    expect(await edgeSttAvailable()).toBe(true);
  });

  it("transcribeAudioLocally() never throws and reports edge-disabled when the flag is off", async () => {
    const { transcribeAudioLocally } = await import("./whisper");
    const result = await transcribeAudioLocally(new Blob(["not really audio"]));
    expect(result).toEqual({ handled: false, reason: "edge-disabled" });
  });

  it("transcribeAudioLocally() reports no-audio for an empty blob", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { transcribeAudioLocally } = await import("./whisper");
    const result = await transcribeAudioLocally(new Blob([]));
    expect(result).toEqual({ handled: false, reason: "no-audio" });
  });

  it("transcribeAudioLocally() reports model-not-ready when the model was never installed", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { transcribeAudioLocally, isSttModelCached } = await import("./whisper");
    expect(isSttModelCached()).toBe(false);
    const result = await transcribeAudioLocally(new Blob(["some bytes"]));
    expect(result).toEqual({ handled: false, reason: "model-not-ready" });
  });

  it("transcribeAudioLocally() never rejects even on unexpected input", async () => {
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    const { transcribeAudioLocally } = await import("./whisper");
    await expect(transcribeAudioLocally(null as any)).resolves.toMatchObject({ handled: false });
  });

  it("ensureSttModel() rejects with a display-safe error when the device can't run STT", async () => {
    // Flag off -> edgeSttAvailable() is false -> ensureSttModel must refuse
    // rather than silently doing nothing (E-0b: never a silent no-op that
    // looks like success from the caller's perspective).
    const { ensureSttModel } = await import("./whisper");
    await expect(ensureSttModel()).rejects.toThrow(/not available/i);
  });

  it("isSttModelCached() reflects nothing installed on a clean device", async () => {
    const { isSttModelCached } = await import("./whisper");
    expect(isSttModelCached()).toBe(false);
  });
});
