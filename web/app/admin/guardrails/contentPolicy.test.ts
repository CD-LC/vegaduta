import { describe, expect, it } from "vitest";
import {
  DEFAULT_CONTENT_POLICY,
  isContentPolicyDirty,
  isPiiRedactionInert,
  normalizeContentPolicy,
} from "./contentPolicy";

describe("normalizeContentPolicy", () => {
  it("returns disabled defaults for null / non-object input", () => {
    expect(normalizeContentPolicy(null)).toEqual(DEFAULT_CONTENT_POLICY);
    expect(normalizeContentPolicy("nope")).toEqual(DEFAULT_CONTENT_POLICY);
  });

  it("keeps valid server values and fills gaps with defaults", () => {
    const p = normalizeContentPolicy({ piiRedactionEnabled: true, piiRedactPhone: false, injectionScreeningAction: "block" });
    expect(p.piiRedactionEnabled).toBe(true);
    expect(p.piiRedactPhone).toBe(false);
    expect(p.piiRedactEmail).toBe(true);
    expect(p.injectionScreeningAction).toBe("BLOCK");
  });

  it("falls back to FLAG for an unknown action", () => {
    expect(normalizeContentPolicy({ injectionScreeningAction: "DROP" }).injectionScreeningAction).toBe("FLAG");
  });
});

describe("isContentPolicyDirty", () => {
  it("detects any field change", () => {
    expect(isContentPolicyDirty(DEFAULT_CONTENT_POLICY, { ...DEFAULT_CONTENT_POLICY })).toBe(false);
    expect(isContentPolicyDirty(DEFAULT_CONTENT_POLICY, { ...DEFAULT_CONTENT_POLICY, injectionScreeningAction: "BLOCK" })).toBe(true);
  });
});

describe("isPiiRedactionInert", () => {
  it("is true only when enabled with no categories", () => {
    const none = { ...DEFAULT_CONTENT_POLICY, piiRedactionEnabled: true, piiRedactEmail: false, piiRedactPhone: false, piiRedactGovernmentId: false, piiRedactCreditCard: false };
    expect(isPiiRedactionInert(none)).toBe(true);
    expect(isPiiRedactionInert({ ...none, piiRedactEmail: true })).toBe(false);
    expect(isPiiRedactionInert({ ...none, piiRedactionEnabled: false })).toBe(false);
  });
});
