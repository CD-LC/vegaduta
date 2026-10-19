import { describe, it, expect } from "vitest";
import { demoSandboxSummary, normalizeTenantCounts } from "./tenantCounts";

describe("normalizeTenantCounts", () => {
  it("passes the three figures through untouched and never sums them", () => {
    const counts = normalizeTenantCounts({ registeredActive: 42, demoLive: 17, demoExpiredUnreclaimed: 230 });
    expect(counts).toEqual({ registeredActive: 42, demoLive: 17, demoExpiredUnreclaimed: 230 });
  });

  it("an older core (no endpoint, error body, array) is 'unknown', not zero", () => {
    expect(normalizeTenantCounts(null)).toBeNull();
    expect(normalizeTenantCounts([])).toBeNull();
    expect(normalizeTenantCounts({ status: 404, error: "Not Found" })).toBeNull();
    expect(normalizeTenantCounts({ registeredActive: 42, demoLive: 17 })).toBeNull();
    expect(normalizeTenantCounts({ registeredActive: "42", demoLive: 1, demoExpiredUnreclaimed: 1 })).toBeNull();
    expect(normalizeTenantCounts({ registeredActive: -1, demoLive: 1, demoExpiredUnreclaimed: 1 })).toBeNull();
  });
});

describe("demoSandboxSummary", () => {
  it("keeps live and expired apart", () => {
    expect(demoSandboxSummary({ registeredActive: 42, demoLive: 17, demoExpiredUnreclaimed: 230 })).toBe(
      "17 live · 230 expired"
    );
  });
});
