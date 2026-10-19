import { describe, it, expect } from "vitest";
import {
  countByStatus,
  filterTenants,
  sortTenants,
  tenantStatusTone,
  type PlatformTenantSummary,
} from "./tenantRoster";

function t(name: string, over: Partial<PlatformTenantSummary> = {}): PlatformTenantSummary {
  return {
    tenantId: name,
    name,
    status: "ACTIVE",
    plan: "FREE",
    createdAt: null,
    ownerEmail: null,
    lastActivityAt: null,
    spend30dUsd: null,
    hostedSpend30dUsd: null,
    llmCalls30d: null,
    agentCount: 0,
    workflowCount: 0,
    userCount: 0,
    metricsComplete: true,
    ...over,
  };
}

const names = (rows: PlatformTenantSummary[]) => rows.map((r) => r.name);

describe("sortTenants", () => {
  const rows = [
    t("acme", { spend30dUsd: 5, createdAt: "2026-01-01T00:00:00Z", lastActivityAt: "2026-10-01T00:00:00Z" }),
    t("beta", { spend30dUsd: null, createdAt: "2026-03-01T00:00:00Z", lastActivityAt: null }),
    t("cobalt", { spend30dUsd: 0, createdAt: null, lastActivityAt: "2026-10-08T00:00:00Z" }),
    t("delta", { spend30dUsd: 12.5, createdAt: "2025-12-01T00:00:00Z", lastActivityAt: "2026-09-01T00:00:00Z" }),
  ];

  it("spend: unavailable sorts last in both directions; a measured zero is a real value", () => {
    expect(names(sortTenants(rows, "spend", "desc"))).toEqual(["delta", "acme", "cobalt", "beta"]);
    expect(names(sortTenants(rows, "spend", "asc"))).toEqual(["cobalt", "acme", "delta", "beta"]);
  });

  it("lastActivity and created sort by instant, nulls last", () => {
    expect(names(sortTenants(rows, "lastActivity", "desc"))).toEqual(["cobalt", "acme", "delta", "beta"]);
    expect(names(sortTenants(rows, "created", "asc"))).toEqual(["delta", "acme", "beta", "cobalt"]);
  });

  it("name sort is case-insensitive and does not mutate the input", () => {
    const input = [t("b"), t("A"), t("c")];
    expect(names(sortTenants(input, "name", "asc"))).toEqual(["A", "b", "c"]);
    expect(names(input)).toEqual(["b", "A", "c"]);
  });

  it("ties fall back to name", () => {
    const tied = [t("zeta", { spend30dUsd: 1 }), t("alpha", { spend30dUsd: 1 })];
    expect(names(sortTenants(tied, "spend", "desc"))).toEqual(["alpha", "zeta"]);
  });
});

describe("filterTenants", () => {
  const rows = [
    t("Acme", { ownerEmail: "ops@acme.io" }),
    t("Beta", { status: "SUSPENDED" }),
    t("Gamma", { status: "PROVISIONING_FAILED" }),
  ];
  it("filters by status and by name or owner email", () => {
    expect(names(filterTenants(rows, "", "ALL"))).toEqual(["Acme", "Beta", "Gamma"]);
    expect(names(filterTenants(rows, "", "SUSPENDED"))).toEqual(["Beta"]);
    expect(names(filterTenants(rows, "acme.io", ""))).toEqual(["Acme"]);
    expect(names(filterTenants(rows, "gam", "ACTIVE"))).toEqual([]);
  });

  it("counts per status", () => {
    expect(countByStatus(rows)).toEqual({ ACTIVE: 1, SUSPENDED: 1, PROVISIONING_FAILED: 1 });
  });
});

describe("tenantStatusTone", () => {
  it("maps statuses to tones", () => {
    expect(tenantStatusTone("ACTIVE")).toBe("ok");
    expect(tenantStatusTone("PROVISIONING")).toBe("warn");
    expect(tenantStatusTone("SUSPENDED")).toBe("bad");
    expect(tenantStatusTone("PROVISIONING_FAILED")).toBe("bad");
    expect(tenantStatusTone("SOMETHING_NEW")).toBe("warn");
  });
});
