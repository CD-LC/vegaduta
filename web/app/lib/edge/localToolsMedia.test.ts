// Tests for the local media tools: registry presence, capability gating,
// argument validation, and the "model isn't installed -> honest refusal"
// path. The generation backends are stubbed via vi.mock so no worker or
// download ever runs.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("./imagegen", () => ({
  edgeImageGenAvailable: vi.fn(async () => true),
  isImageGenModelCached: vi.fn(() => false),
  generateImageLocally: vi.fn(),
  ensureImageGenModel: vi.fn(),
  deleteImageGenModel: vi.fn(),
  IMAGE_GEN_ASSET_ID: "edge-imagegen-sd-turbo",
  IMAGE_GEN_MODEL_SIZE_BYTES: 2_500_000_000,
}));
vi.mock("./audiogen", () => ({
  edgeAudioGenAvailable: vi.fn(async () => true),
  isAudioGenModelCached: vi.fn(() => false),
  generateAudioLocally: vi.fn(),
  ensureAudioGenModel: vi.fn(),
  deleteAudioGenModel: vi.fn(),
  AUDIO_GEN_ASSET_ID: "edge-audiogen-musicgen",
  AUDIO_GEN_MODEL_SIZE_BYTES: 1_800_000_000,
}));

import * as imagegen from "./imagegen";
import * as audiogen from "./audiogen";

describe("localToolsMedia", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.mocked(imagegen.edgeImageGenAvailable).mockResolvedValue(true);
    vi.mocked(imagegen.isImageGenModelCached).mockReturnValue(false);
    vi.mocked(audiogen.edgeAudioGenAvailable).mockResolvedValue(true);
    vi.mocked(audiogen.isAudioGenModelCached).mockReturnValue(false);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("registers local_generate_image and local_generate_audio", async () => {
    const { MEDIA_LOCAL_TOOLS } = await import("./localToolsMedia");
    const names = MEDIA_LOCAL_TOOLS.map((t) => t.name);
    expect(names).toContain("local_generate_image");
    expect(names).toContain("local_generate_audio");
    for (const t of MEDIA_LOCAL_TOOLS) {
      expect(t.network).toBe("none");
      expect(t.name.startsWith("local_")).toBe(true);
      expect(t.timeoutMs).toBeGreaterThan(0);
    }
  });

  it("local_generate_image refuses with a pointing message when the model is absent", async () => {
    const { MEDIA_LOCAL_TOOLS } = await import("./localToolsMedia");
    const tool = MEDIA_LOCAL_TOOLS.find((t) => t.name === "local_generate_image")!;
    const { runLocalTool } = await import("./localTools");
    const res = await runLocalTool(tool, { prompt: "a cabin in the woods" });
    expect(res.ok).toBe(false);
    if (res.ok === false) {
      expect(res.detail).toMatch(/edge console/i);
    }
  });

  it("local_generate_audio refuses on a missing prompt", async () => {
    const { MEDIA_LOCAL_TOOLS } = await import("./localToolsMedia");
    const tool = MEDIA_LOCAL_TOOLS.find((t) => t.name === "local_generate_audio")!;
    const { runLocalTool } = await import("./localTools");
    const res = await runLocalTool(tool, {});
    expect(res.ok).toBe(false);
    if (res.ok === false) expect(res.code).toBe("rejected");
  });

  it("local_generate_image returns a registry id and keeps the blob", async () => {
    vi.mocked(imagegen.isImageGenModelCached).mockReturnValue(true);
    vi.mocked(imagegen.generateImageLocally).mockResolvedValue({
      handled: true,
      blob: new Blob([new Uint8Array([1, 2, 3])], { type: "image/png" }),
      artifact: {
        id: "edge-media-test",
        kind: "image",
        mime: "image/png",
        url: "blob:test",
        byteSize: 3,
        description: "a cabin",
        width: 512,
        height: 512,
        createdAt: Date.now(),
      },
      width: 512,
      height: 512,
    });
    const { MEDIA_LOCAL_TOOLS } = await import("./localToolsMedia");
    const tool = MEDIA_LOCAL_TOOLS.find((t) => t.name === "local_generate_image")!;
    const { runLocalTool } = await import("./localTools");
    const res = await runLocalTool(tool, { prompt: "a cabin", seed: 42 });
    expect(res.ok).toBe(true);
    if (res.ok) {
      expect(res.result).toContain("edge-media-test");
      expect(res.result).toContain("512x512");
    }
  });
});
