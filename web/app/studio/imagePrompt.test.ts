import { describe, expect, it } from "vitest";
import { MAX_PROMPT, buildImagePrompt, pickIdea, playgroundHref, reusePrompt } from "./imagePrompt";

describe("buildImagePrompt", () => {
  it("is empty for a blank idea", () => {
    expect(buildImagePrompt("   ", "photo", "wide")).toBe("");
  });
  it("adds style and shape phrases", () => {
    expect(buildImagePrompt("a  red fox", "watercolor", "tall")).toBe(
      "Generate an image of a red fox, soft watercolor painting, tall 9:16 portrait framing."
    );
  });
  it("works with no options and never exceeds the playground limit", () => {
    expect(buildImagePrompt("a fox", null, null)).toBe("Generate an image of a fox.");
    expect(buildImagePrompt("x".repeat(5000), "photo", "wide").length).toBeLessThanOrEqual(MAX_PROMPT);
  });
});

describe("helpers", () => {
  it("encodes the prompt in the playground link", () => {
    expect(playgroundHref("a&b c")).toBe("/playground?prompt=a%26b%20c");
  });
  it("reuses a caption without doubling the verb", () => {
    expect(reusePrompt("Generate an image of a lighthouse")).toBe("Generate an image of a lighthouse.");
    expect(reusePrompt("a lighthouse")).toBe("Generate an image of a lighthouse.");
  });
  it("picks deterministically and tolerates an empty list", () => {
    expect(pickIdea(["a", "b", "c"], 4)).toBe("b");
    expect(pickIdea([], 1)).toBe("");
  });
});
