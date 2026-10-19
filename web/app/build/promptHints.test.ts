import { describe, expect, it } from "vitest";
import { analysePrompt, applyHint } from "./promptHints";
import { SURPRISE_PROMPTS } from "./surprisePrompts";

const ids = (t: string) => analysePrompt(t).hints.map((h) => h.id);

describe("analysePrompt", () => {
  it("is empty for blank input", () => {
    expect(analysePrompt("   ")).toEqual({ strength: "empty", words: 0, hints: [] });
  });

  it("flags a vague one-liner on all three counts", () => {
    const a = analysePrompt("a bot");
    expect(a.strength).toBe("weak");
    expect(ids("a bot")).toEqual(["short", "audience", "outcome"]);
  });

  it("flags missing audience but accepts an outcome", () => {
    expect(ids("Answer questions about pricing in a friendly tone and book calls quickly")).toEqual(["audience"]);
  });

  it("flags missing outcome but accepts an audience", () => {
    expect(ids("Something nice for my customers on our website every day of the week")).toEqual(["outcome"]);
  });

  it("is strong when audience, outcome and length are all present", () => {
    const a = analysePrompt("Answer my customers' questions about delivery and returns, and pass refunds to me");
    expect(a.hints).toEqual([]);
    expect(a.strength).toBe("strong");
  });

  it("rates every curated surprise prompt as strong", () => {
    const weak = SURPRISE_PROMPTS.filter((p) => analysePrompt(p).hints.length > 0);
    expect(weak).toEqual([]);
  });
});

describe("applyHint", () => {
  it("appends with a sentence break and leaves no double spaces", () => {
    const h = { addition: " It is for my customers." };
    expect(applyHint("Answer pricing questions", h)).toBe("Answer pricing questions. It is for my customers.");
    expect(applyHint("Answer pricing questions.  ", h)).toBe("Answer pricing questions. It is for my customers.");
  });

  it("trims the addition when the goal is empty", () => {
    expect(applyHint("", { addition: " Hello." })).toBe("Hello.");
  });
});
