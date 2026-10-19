import { describe, expect, it } from "vitest";
import { loadBuiltinFallbackManifest } from "./registry";
import { KNOWN_WEBLLM_MODEL_IDS } from "./models/webllmIds";
import { formatSize, sanitizeTier1Manifest } from "./select";
import { estimateTier1Tokens, computeTier1PromptBudget } from "./promptBudget";
import { parseToolCall } from "./toolProtocol";

describe("tier1 built-in catalogue", () => {
  it("keeps the original six-model order", async () => {
    const m = await loadBuiltinFallbackManifest();
    expect(m.version).toBe("builtin-fallback-1");
    expect(m.models.map((x) => x.id)).toEqual([
      "Llama-3.2-3B-Instruct-q4f16_1-MLC",
      "Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
      "Llama-3.2-1B-Instruct-q4f16_1-MLC",
      "Phi-3.5-mini-instruct-q4f16_1-MLC",
      "Hermes-2-Pro-Mistral-7B-q4f16_1-MLC",
      "gemma-2-2b-it-q4f16_1-MLC",
    ]);
  });

  it("every entry satisfies the manifest schema and is servable by web-llm", async () => {
    const m = await loadBuiltinFallbackManifest();
    const ids = new Set<string>();
    for (const e of m.models) {
      expect(ids.has(e.id)).toBe(false);
      ids.add(e.id);
      expect(KNOWN_WEBLLM_MODEL_IDS.has(e.id)).toBe(true);
      expect(e.tier).toBe(1);
      expect(e.displayName.length).toBeGreaterThan(0);
      expect(e.detailName ?? "").not.toBe("");
      expect(e.sizeBytes).toBeGreaterThan(0);
      expect(e.minDeviceMemoryGB).toBeGreaterThan(0);
      expect(e.minMaxBufferSize).toBeGreaterThan(0);
      expect(["fast", "capable"]).toContain(e.speedTier);
      expect(e.contextWindowSize).toBeGreaterThan(0);
      expect(e.url).toBeNull();
      expect(e.sha256).toBeNull();
    }
  });

  it("sanitize keeps a servable catalogue untouched", async () => {
    const m = await loadBuiltinFallbackManifest();
    expect(sanitizeTier1Manifest(m).models).toHaveLength(m.models.length);
  });
});

describe("tier1 pure helpers", () => {
  it("formats sizes", () => {
    expect(formatSize(2_263_690_000)).toBe("2.3 GB");
    expect(formatSize(5_000_000)).toBe("5 MB");
  });
  it("budgets and estimates tokens", () => {
    expect(estimateTier1Tokens("")).toBe(0);
    expect(computeTier1PromptBudget(4096)).toBe(4096 - 1024 - Math.ceil(4096 * 0.08));
  });
  it("parses the tool tag", () => {
    expect(parseToolCall('<tool_call>{"name":"a","arguments":{}}</tool_call>')).toEqual({ name: "a", arguments: {} });
    expect(parseToolCall("hello")).toBeNull();
  });
});
