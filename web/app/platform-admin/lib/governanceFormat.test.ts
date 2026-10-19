import { describe, expect, it } from "vitest";
import {
  capBarWidth,
  capPercent,
  capTone,
  formatCapUsage,
  formatCapUsd,
  formatTimeLeft,
  impersonationState,
  isSafeViewUrl,
  parseCapInput,
  reasonProblem,
} from "./governanceFormat";
import { countCaps, filterByCap, type PlatformTenantSummary } from "./tenantRoster";

describe("cap formatting", () => {
  it("formatCapUsd", () => {
    expect(formatCapUsd(50)).toBe("$50");
    expect(formatCapUsd(12.4)).toBe("$12.40");
    expect(formatCapUsd(2500)).toBe("$2,500");
    expect(formatCapUsd(null)).toBe("—");
  });
  it("formatCapUsage", () => {
    expect(formatCapUsage(50, 12.4)).toBe("$12.40 / $50 (25%)");
    expect(formatCapUsage(50, 0)).toBe("$0 / $50 (0%)");
    expect(formatCapUsage(null, 12)).toBe("No cap");
    expect(formatCapUsage(50, null)).toBe("— / $50");
    expect(formatCapUsage(20, 30)).toBe("$30 / $20 (150%)");
  });
  it("capPercent prefers core's figure", () => {
    expect(capPercent(50, 10, 33.3)).toBe(33);
    expect(capPercent(50, 10)).toBe(20);
    expect(capPercent(null, 10)).toBeNull();
    expect(capPercent(0, 10)).toBeNull();
  });
  it("capTone", () => {
    expect(capTone({ capUsd: null, monthToDateUsd: 5 })).toBe("muted");
    expect(capTone({ capUsd: 50, monthToDateUsd: 5 })).toBe("ok");
    expect(capTone({ capUsd: 50, monthToDateUsd: 40 })).toBe("warn");
    expect(capTone({ capUsd: 50, monthToDateUsd: 5, alertStage: 80 })).toBe("warn");
    expect(capTone({ capUsd: 50, monthToDateUsd: 50 })).toBe("bad");
    expect(capTone({ capUsd: 50, monthToDateUsd: 1, blocked: true })).toBe("bad");
  });
  it("capBarWidth clamps", () => {
    expect(capBarWidth(150)).toBe(100);
    expect(capBarWidth(-3)).toBe(0);
    expect(capBarWidth(null)).toBe(0);
    expect(capBarWidth(42)).toBe(42);
  });
});

describe("parseCapInput", () => {
  it("accepts amounts and no-cap", () => {
    expect(parseCapInput("50", false)).toEqual({ ok: true, value: 50 });
    expect(parseCapInput(" $1,200.50 ", false)).toEqual({ ok: true, value: 1200.5 });
    expect(parseCapInput("anything", true)).toEqual({ ok: true, value: null });
  });
  it("rejects bad input", () => {
    expect(parseCapInput("", false).ok).toBe(false);
    expect(parseCapInput("0", false).ok).toBe(false);
    expect(parseCapInput("-5", false).ok).toBe(false);
    expect(parseCapInput("1.234", false).ok).toBe(false);
    expect(parseCapInput("abc", false).ok).toBe(false);
    expect(parseCapInput("2000000", false).ok).toBe(false);
  });
});

describe("view-as helpers", () => {
  it("reasonProblem enforces 10 characters", () => {
    expect(reasonProblem("")).toMatch(/required/);
    expect(reasonProblem("  short  ")).toMatch(/5 more characters/);
    expect(reasonProblem("123456789")).toMatch(/1 more character\)/);
    expect(reasonProblem("Ticket #4821 debug")).toBeNull();
  });
  it("isSafeViewUrl only allows /view-as with the grant in the fragment", () => {
    expect(isSafeViewUrl("https://app.vegaduta.ai/view-as#grant=abc123")).toBe(true);
    expect(isSafeViewUrl("https://app.vegaduta.ai/view-as?grant=abc123")).toBe(false);
    expect(isSafeViewUrl("https://app.vegaduta.ai/home#grant=abc123")).toBe(false);
    expect(isSafeViewUrl("javascript:alert(1)//view-as#grant=x")).toBe(false);
    expect(isSafeViewUrl("https://app.vegaduta.ai/view-as")).toBe(false);
    expect(isSafeViewUrl(null)).toBe(false);
  });
  it("impersonationState + formatTimeLeft", () => {
    const now = Date.parse("2026-10-09T10:00:00Z");
    expect(impersonationState({ endedAt: null, expiresAt: "2026-10-09T10:30:00Z" }, now)).toBe("active");
    expect(impersonationState({ endedAt: null, expiresAt: "2026-10-09T09:59:00Z" }, now)).toBe("expired");
    expect(impersonationState({ endedAt: "2026-10-09T09:50:00Z", expiresAt: "2026-10-09T10:30:00Z" }, now)).toBe(
      "ended"
    );
    expect(formatTimeLeft("2026-10-09T10:30:00Z", now)).toBe("30m left");
    expect(formatTimeLeft("2026-10-09T10:00:45Z", now)).toBe("45s left");
    expect(formatTimeLeft("2026-10-09T09:00:00Z", now)).toBe("expired");
    expect(formatTimeLeft(null, now)).toBe("—");
  });
});

describe("roster cap filter", () => {
  const base: Omit<PlatformTenantSummary, "tenantId" | "name"> = {
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
  };
  const rows: PlatformTenantSummary[] = [
    { ...base, tenantId: "a", name: "a", spendCapUsd: 50, capMonthToDateUsd: 10, capBlocked: false },
    { ...base, tenantId: "b", name: "b", spendCapUsd: 20, capMonthToDateUsd: 20, capBlocked: true },
    { ...base, tenantId: "c", name: "c", spendCapUsd: null, capMonthToDateUsd: null, capBlocked: null },
    { ...base, tenantId: "d", name: "d" },
  ];
  it("filters and counts", () => {
    expect(filterByCap(rows, "ALL").map((r) => r.name)).toEqual(["a", "b", "c", "d"]);
    expect(filterByCap(rows, "CAPPED").map((r) => r.name)).toEqual(["a", "b"]);
    expect(filterByCap(rows, "BLOCKED").map((r) => r.name)).toEqual(["b"]);
    expect(countCaps(rows)).toEqual({ capped: 2, blocked: 1 });
  });
});
