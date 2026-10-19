import { describe, expect, it } from "vitest";
import { SURPRISE_PROMPTS, pickSurprise } from "./surprisePrompts";

describe("SURPRISE_PROMPTS", () => {
  it("has at least 24 unique, substantial prompts", () => {
    expect(SURPRISE_PROMPTS.length).toBeGreaterThanOrEqual(24);
    expect(new Set(SURPRISE_PROMPTS).size).toBe(SURPRISE_PROMPTS.length);
    for (const p of SURPRISE_PROMPTS) {
      expect(p.length).toBeGreaterThan(40);
      expect(p.length).toBeLessThan(300);
    }
  });

  it("includes the India-relevant staples", () => {
    const all = SURPRISE_PROMPTS.join(" ").toLowerCase();
    for (const word of ["kirana", "tuition", "whatsapp", "upi"]) expect(all).toContain(word);
  });
});

describe("pickSurprise", () => {
  it("is deterministic with an injected rand and stays in range", () => {
    expect(pickSurprise("", () => 0)).toBe(SURPRISE_PROMPTS[0]);
    expect(pickSurprise("", () => 0.9999999)).toBe(SURPRISE_PROMPTS[SURPRISE_PROMPTS.length - 1]);
  });

  it("never returns the prompt already in the box", () => {
    const current = SURPRISE_PROMPTS[0];
    for (const r of [0, 0.01, 0.5, 0.99]) {
      expect(pickSurprise(current, () => r)).not.toBe(current);
    }
  });
});
