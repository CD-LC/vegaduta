import { describe, expect, it } from "vitest";
import { isProviderRejected, providerHealthOf } from "./providerHealth";

describe("providerHealth - reading core's per-credential health defensively", () => {
  it("reads the documented shape", () => {
    expect(providerHealthOf({ providerName: "GOOGLE_VERTEX", health: "rejected", recentFailures: 24, lastError: "401 invalid credentials" })).toEqual({
      rejected: true,
      recentFailures: 24,
      lastError: "401 invalid credentials",
    });
    expect(providerHealthOf({ health: "ok", recentFailures: 0 })).toMatchObject({ rejected: false, recentFailures: 0, lastError: null });
  });

  it("treats a missing health, a non-object row or an unknown state as not rejected", () => {
    expect(providerHealthOf({ providerName: "OPENROUTER" })).toBeNull();
    expect(providerHealthOf(null)).toBeNull();
    expect(providerHealthOf(undefined)).toBeNull();
    expect(providerHealthOf({ health: "degraded" })?.rejected).toBe(false);
    expect(providerHealthOf({ health: 42 })?.rejected).toBe(false);
  });

  it("is case-insensitive and also reads a nested object", () => {
    expect(providerHealthOf({ health: "REJECTED" })?.rejected).toBe(true);
    expect(providerHealthOf({ health: { status: "rejected", recentFailures: 3, lastError: "bad key" } })).toEqual({
      rejected: true,
      recentFailures: 3,
      lastError: "bad key",
    });
  });

  it("cleans odd counts and blank errors", () => {
    expect(providerHealthOf({ health: "rejected", recentFailures: "x", lastError: "  " })).toEqual({ rejected: true, recentFailures: 0, lastError: null });
    expect(providerHealthOf({ health: "rejected", recentFailures: -4 })?.recentFailures).toBe(0);
  });

  it("finds a rejected provider by name", () => {
    const rows = [
      { providerName: "OPENROUTER", health: "ok" },
      { providerName: "GOOGLE_VERTEX", health: "rejected" },
      { providerName: "ANTHROPIC" },
    ];
    expect(isProviderRejected(rows, "GOOGLE_VERTEX")).toBe(true);
    expect(isProviderRejected(rows, "OPENROUTER")).toBe(false);
    expect(isProviderRejected(rows, "ANTHROPIC")).toBe(false);
    expect(isProviderRejected(rows, "NOT_THERE")).toBe(false);
    expect(isProviderRejected(rows, null)).toBe(false);
    expect(isProviderRejected(undefined, "GOOGLE_VERTEX")).toBe(false);
  });
});
