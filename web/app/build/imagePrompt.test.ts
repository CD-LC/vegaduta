import { describe, expect, it } from "vitest";
import {
  IMAGE_KINDS,
  MAX_IMAGE_PROMPT_CHARS,
  defaultSizeFor,
  assetUsageInstruction,
  validateImageRequest,
} from "./imagePrompt";

describe("imagePrompt", () => {
  it("rejects empty and whitespace prompts", () => {
    expect(validateImageRequest("   ", "logo")).toMatch(/Describe/);
  });
  it("rejects overlong prompts and unknown kinds", () => {
    expect(validateImageRequest("a".repeat(MAX_IMAGE_PROMPT_CHARS + 1), "logo")).toMatch(/under/);
    expect(validateImageRequest("fox", "poster")).toMatch(/type/);
  });
  it("accepts every supported kind", () => {
    for (const k of IMAGE_KINDS) expect(validateImageRequest("a fox", k)).toBeNull();
  });
  it("uses a wide default size only for hero", () => {
    expect(defaultSizeFor("hero")).toBe("1536x1024");
    expect(defaultSizeFor("logo")).toBe("1024x1024");
  });
  it("builds a per-kind instruction referencing the asset path", () => {
    expect(assetUsageInstruction("logo", "assets/logo-x.png")).toContain("assets/logo-x.png");
    expect(assetUsageInstruction("hero", "assets/h.png")).toMatch(/hero/);
    expect(assetUsageInstruction("logo", "a")).not.toBe(assetUsageInstruction("icon", "a"));
  });
});
