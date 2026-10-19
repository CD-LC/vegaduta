import { describe, expect, it } from "vitest";
import {
  MAX_SPECIALTIES,
  checkNewTag,
  displayScore,
  healthTone,
  normalizeSpecialtyTag,
  sameSpecialties,
} from "./agentInsights";

describe("healthTone", () => {
  it("maps statuses to state tones", () => {
    expect(healthTone("HEALTHY")).toBe("success");
    expect(healthTone("AT_RISK")).toBe("warning");
    expect(healthTone("DEGRADED")).toBe("danger");
    expect(healthTone("???")).toBe("neutral");
    expect(healthTone(null)).toBe("neutral");
  });
});

describe("displayScore", () => {
  it("rounds and clamps, rejects non-numbers", () => {
    expect(displayScore(87.6)).toBe(88);
    expect(displayScore(150)).toBe(100);
    expect(displayScore(-3)).toBe(0);
    expect(displayScore(NaN)).toBeNull();
    expect(displayScore("90")).toBeNull();
  });
});

describe("checkNewTag", () => {
  it("normalises and accepts a good tag", () => {
    expect(normalizeSpecialtyTag("  Tax Advice ")).toBe("tax-advice");
    expect(checkNewTag("  Tax Advice ", [])).toEqual({ tag: "tax-advice", problem: null });
  });
  it("flags empty, format, length, duplicate and cap", () => {
    expect(checkNewTag("   ", []).problem).toBe("empty");
    expect(checkNewTag("bad$tag", []).problem).toBe("format");
    expect(checkNewTag("a".repeat(51), []).problem).toBe("tooLong");
    expect(checkNewTag("Billing", ["billing"]).problem).toBe("duplicate");
    const full = Array.from({ length: MAX_SPECIALTIES }, (_, i) => `t${i}`);
    expect(checkNewTag("extra", full).problem).toBe("tooMany");
  });
});

describe("sameSpecialties", () => {
  it("ignores order and blank-vs-null descriptions", () => {
    expect(
      sameSpecialties(
        [{ capabilityTag: "a", description: null }, { capabilityTag: "b", description: "x" }],
        [{ capabilityTag: "b", description: "x " }, { capabilityTag: "a", description: "" }],
      ),
    ).toBe(true);
    expect(sameSpecialties([{ capabilityTag: "a" }], [{ capabilityTag: "b" }])).toBe(false);
  });
});
