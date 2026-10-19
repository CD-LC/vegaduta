import { describe, it, expect } from "vitest";
import {
  announcementLive,
  announcementProblems,
  barGeometry,
  compareVersions,
  failedJobCount,
  filterJobs,
  formatCompact,
  formatDurationMs,
  isoToLocalInput,
  jobStatusMeta,
  lastDaysRange,
  localInputToIso,
  maintenanceConfirmSatisfied,
  migrationDrift,
  ownBuildSha,
  resolveExternalTool,
  riskMeta,
  shortSha,
  sortJobs,
  sortTenantMigrations,
  sumMetrics,
  tenantMigrationState,
  unreachableServices,
  versionLabel,
} from "./opsFormat";
import {
  queryString,
  type AnnouncementState,
  type ScheduledJob,
  type ServiceVersion,
  type TenantMigration,
} from "./adminApi/operations";
import { UNAVAILABLE } from "./platformFormat";

function job(over: Partial<ScheduledJob>): ScheduledJob {
  return {
    id: "j",
    bean: "Bean",
    method: "run",
    schedule: "0 * * * *",
    lastStartedAt: null,
    lastFinishedAt: null,
    lastStatus: null,
    lastError: null,
    lastDurationMs: null,
    runNowAllowed: false,
    ...over,
  };
}

function tenant(over: Partial<TenantMigration>): TenantMigration {
  return { tenantId: "t", name: "T", schema: "tenant_t", version: "300", pending: 0, failed: 0, status: "OK", ...over };
}

const ANN: AnnouncementState = { enabled: true, message: "Hello", level: "info", linkUrl: null, expiresAt: null };

describe("number formatting", () => {
  it("formatDurationMs never turns unknown into 0", () => {
    expect(formatDurationMs(null)).toBe(UNAVAILABLE);
    expect(formatDurationMs(undefined)).toBe(UNAVAILABLE);
    expect(formatDurationMs(-1)).toBe(UNAVAILABLE);
    expect(formatDurationMs(0)).toBe("0 ms");
    expect(formatDurationMs(850)).toBe("850 ms");
    expect(formatDurationMs(1250)).toBe("1.25 s");
    expect(formatDurationMs(125_000)).toBe("2.1 min");
  });

  it("formatCompact", () => {
    expect(formatCompact(null)).toBe(UNAVAILABLE);
    expect(formatCompact(999)).toBe("999");
    expect(formatCompact(1234)).toBe("1.2k");
    expect(formatCompact(25_000)).toBe("25k");
    expect(formatCompact(2_500_000)).toBe("2.5M");
  });

  it("shortSha", () => {
    expect(shortSha("abcdef1234567")).toBe("abcdef1");
    expect(shortSha(null)).toBe(UNAVAILABLE);
    expect(shortSha("  ")).toBe(UNAVAILABLE);
  });
});

describe("versions", () => {
  it("versionLabel prefers the version, falls back to the short SHA, else unavailable", () => {
    expect(versionLabel({ version: "1.4.2", gitSha: "abc" })).toBe("1.4.2");
    expect(versionLabel({ version: null, gitSha: "abcdef1234" })).toBe("abcdef1");
    expect(versionLabel({ version: null, gitSha: null })).toBe(UNAVAILABLE);
  });

  it("unreachableServices", () => {
    const s: ServiceVersion[] = [
      { name: "core", version: "1", gitSha: null, buildTime: null, status: "ok" as const },
      { name: "sidecar", version: null, gitSha: null, buildTime: null, status: "unreachable" as const },
    ];
    expect(unreachableServices(s).map((x) => x.name)).toEqual(["sidecar"]);
    expect(unreachableServices(null)).toEqual([]);
  });

  it("ownBuildSha treats blank as not stamped", () => {
    expect(ownBuildSha("")).toBeNull();
    expect(ownBuildSha(undefined)).toBeNull();
    expect(ownBuildSha(" abc ")).toBe("abc");
  });
});

describe("jobs", () => {
  it("sorts failed first, then running, never-run, succeeded", () => {
    const sorted = sortJobs([
      job({ id: "a", method: "a", lastStatus: "SUCCESS" }),
      job({ id: "b", method: "b", lastStatus: null }),
      job({ id: "c", method: "c", lastStatus: "FAILED" }),
      job({ id: "d", method: "d", lastStatus: "RUNNING" }),
    ]);
    expect(sorted.map((j) => j.id)).toEqual(["c", "d", "b", "a"]);
  });

  it("filters across bean/method/schedule/error, case-insensitive", () => {
    const jobs = [job({ id: "1", bean: "UsageRollup" }), job({ id: "2", lastError: "Connection REFUSED" })];
    expect(filterJobs(jobs, "rollup").map((j) => j.id)).toEqual(["1"]);
    expect(filterJobs(jobs, "refused").map((j) => j.id)).toEqual(["2"]);
    expect(filterJobs(jobs, "  ")).toHaveLength(2);
  });

  it("failedJobCount is null when the list is unavailable", () => {
    expect(failedJobCount(null)).toBeNull();
    expect(failedJobCount([job({ lastStatus: "FAILED" }), job({ lastStatus: "SUCCESS" })])).toBe(1);
  });

  it("jobStatusMeta labels null as never run", () => {
    expect(jobStatusMeta(null).label).toBe("Never run");
    expect(jobStatusMeta("FAILED").tone).toBe("bad");
  });
});

describe("migrations", () => {
  it("compareVersions is numeric, not lexical", () => {
    expect(compareVersions("45", "312")! < 0).toBe(true);
    expect(compareVersions("1.10", "1.9")! > 0).toBe(true);
    expect(compareVersions("312", "312")).toBe(0);
    expect(compareVersions(null, "1")).toBeNull();
  });

  it("tenantMigrationState", () => {
    expect(tenantMigrationState(tenant({ failed: 1 }), "300")).toBe("failed");
    expect(tenantMigrationState(tenant({ status: "FAILED" }), "300")).toBe("failed");
    expect(tenantMigrationState(tenant({ pending: 2 }), "300")).toBe("behind");
    expect(tenantMigrationState(tenant({ version: "299" }), "300")).toBe("behind");
    expect(tenantMigrationState(tenant({ version: "300" }), "300")).toBe("ok");
    expect(tenantMigrationState(tenant({ version: null }), "300")).toBe("unknown");
  });

  it("migrationDrift counts and flags the control plane", () => {
    const d = migrationDrift({
      controlPlane: { version: "310", pending: 1, failed: 0 },
      expectedTenantVersion: "300",
      tenants: [
        tenant({ tenantId: "a", failed: 1 }),
        tenant({ tenantId: "b", version: "1" }),
        tenant({ tenantId: "c" }),
      ],
    });
    expect(d).toEqual({ failed: 1, behind: 1, unknown: 0, current: 1, controlPlaneProblem: true });
    expect(migrationDrift(null)).toBeNull();
  });

  it("sortTenantMigrations puts failed then behind first", () => {
    const rows = sortTenantMigrations({
      controlPlane: { version: "1", pending: 0, failed: 0 },
      expectedTenantVersion: "300",
      tenants: [
        tenant({ tenantId: "ok", name: "A" }),
        tenant({ tenantId: "behind", name: "B", version: "2" }),
        tenant({ tenantId: "failed", name: "C", failed: 1 }),
      ],
    });
    expect(rows.map((r) => r.tenantId)).toEqual(["failed", "behind", "ok"]);
  });
});

describe("maintenance & announcement", () => {
  it("enabling from off needs the exact word; disabling does not", () => {
    expect(maintenanceConfirmSatisfied(false, true, "")).toBe(false);
    expect(maintenanceConfirmSatisfied(false, true, "maintenance")).toBe(false);
    expect(maintenanceConfirmSatisfied(false, true, " MAINTENANCE ")).toBe(true);
    expect(maintenanceConfirmSatisfied(true, false, "")).toBe(true);
    expect(maintenanceConfirmSatisfied(true, true, "")).toBe(true);
  });

  it("datetime-local round trip", () => {
    const iso = localInputToIso("2026-10-09T14:30");
    expect(iso).not.toBeNull();
    expect(isoToLocalInput(iso)).toBe("2026-10-09T14:30");
    expect(localInputToIso("")).toBeNull();
    expect(isoToLocalInput(null)).toBe("");
    expect(isoToLocalInput("garbage")).toBe("");
  });

  it("announcementProblems", () => {
    const now = Date.parse("2026-10-09T00:00:00Z");
    expect(announcementProblems(ANN, now)).toEqual([]);
    expect(announcementProblems({ ...ANN, message: " " }, now)).toHaveLength(1);
    expect(announcementProblems({ ...ANN, enabled: false, message: "" }, now)).toEqual([]);
    expect(announcementProblems({ ...ANN, linkUrl: "javascript:alert(1)" }, now)).toHaveLength(1);
    expect(announcementProblems({ ...ANN, linkUrl: "https://vegaduta.ai/x" }, now)).toEqual([]);
    expect(announcementProblems({ ...ANN, expiresAt: "2026-10-08T00:00:00Z" }, now)).toHaveLength(1);
  });

  it("announcementLive respects enabled, message and expiry", () => {
    const now = Date.parse("2026-10-09T00:00:00Z");
    expect(announcementLive(ANN, now)).toBe(true);
    expect(announcementLive({ ...ANN, enabled: false }, now)).toBe(false);
    expect(announcementLive({ ...ANN, expiresAt: "2026-10-08T00:00:00Z" }, now)).toBe(false);
    expect(announcementLive({ ...ANN, expiresAt: "2026-10-10T00:00:00Z" }, now)).toBe(true);
    expect(announcementLive(null, now)).toBe(false);
  });
});

describe("external tools", () => {
  const def = { key: "grafana", label: "Grafana", envName: "GRAFANA_URL", use: "" };
  it("unset or invalid means not configured - no localhost fallback", () => {
    expect(resolveExternalTool({ ...def, url: undefined })).toEqual({ configured: false, url: null });
    expect(resolveExternalTool({ ...def, url: "" })).toEqual({ configured: false, url: null });
    expect(resolveExternalTool({ ...def, url: "grafana" })).toEqual({ configured: false, url: null });
    expect(resolveExternalTool({ ...def, url: "https://grafana.vegaduta.ai" })).toEqual({
      configured: true,
      url: "https://grafana.vegaduta.ai",
    });
  });
});

describe("charts & metrics", () => {
  it("barGeometry: null gets no bar, all-zero does not divide by zero", () => {
    const g = barGeometry([2, null, 4], { width: 300, height: 100, padTop: 0, padBottom: 0 });
    expect(g.max).toBe(4);
    expect(g.slot).toBe(100);
    expect(g.points[1].value).toBeNull();
    expect(g.points[1].h).toBe(0);
    expect(g.points[2].h).toBe(100);
    expect(g.points[0].h).toBe(50);
    const z = barGeometry([0, 0], { width: 100, height: 50 });
    expect(z.points.every((p) => Number.isFinite(p.h) && p.h === 0)).toBe(true);
    expect(barGeometry([], { width: 100, height: 50 }).points).toEqual([]);
  });

  it("sumMetrics keeps unknown as null, not 0", () => {
    expect(sumMetrics([])).toEqual({ traces: null, costUsd: null });
    expect(
      sumMetrics([
        { traces: 2, costUsd: null },
        { traces: 3, costUsd: 0.5 },
      ])
    ).toEqual({ traces: 5, costUsd: 0.5 });
  });

  it("lastDaysRange", () => {
    const now = Date.parse("2026-10-09T00:00:00Z");
    expect(lastDaysRange(7, now)).toEqual({ from: "2026-10-02T00:00:00.000Z", to: "2026-10-09T00:00:00.000Z" });
  });
});

describe("misc", () => {
  it("queryString skips empty values", () => {
    expect(queryString({ q: "", page: 1, limit: undefined, tenantId: null, name: "a b" })).toBe("?page=1&name=a+b");
    expect(queryString({})).toBe("");
  });

  it("riskMeta", () => {
    expect(riskMeta("high").tone).toBe("bad");
    expect(riskMeta("none").tone).toBe("ok");
    expect(riskMeta(undefined).label).toBe("Not scanned");
  });
});
