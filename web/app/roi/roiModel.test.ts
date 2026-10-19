import { describe, expect, it } from "vitest";
import type { AgentRoiRowDto } from "../lib/api/roi";
import {
  DASH,
  budgetFill,
  budgetTone,
  formatCharges,
  formatMoney,
  formatPercentFromRatio,
  formatRoiPercent,
  formatUsd,
  parseAmount,
  presetRange,
  roiNoteKey,
  totals,
  validateRange,
} from "./roiModel";

const row = (over: Partial<AgentRoiRowDto> = {}): AgentRoiRowDto => ({
  agentId: "a", agentName: "A", conversations: 0, resolved: 0, resolvedConfirmed: 0, resolvedAssumed: 0, resolvedByHuman: 0,
  handedToHuman: 0, abandoned: 0, unresolved: 0, humanCorrected: 0, handoverRate: null, humanCorrectionRate: null,
  approvalsDecided: 0, approvalsDenied: 0, approvalDenialRate: null, byokEstimatedCostUsd: 0, platformHostedEstimatedCostUsd: 0,
  estimatedCostUsd: 0, estimatedCostPerResolvedUsd: null, costComplete: true, platformChargesMinor: 0, platformChargesCurrency: null,
  valuePerResolution: null, valueCurrency: null, minutesSavedPerResolution: null, valueTotal: null, minutesSavedTotal: null,
  netValue: null, roiPercent: null, roiNote: "NO_ASSUMPTIONS", ...over,
});

describe("number display", () => {
  it("shows no-data as a dash, never as zero", () => {
    expect(formatUsd(null)).toBe(DASH);
    expect(formatPercentFromRatio(null)).toBe(DASH);
    expect(formatRoiPercent(null)).toBe(DASH);
    expect(formatMoney(null, "USD")).toBe(DASH);
    expect(formatPercentFromRatio(0)).toBe("0%");
  });

  it("formats server decimals sent as strings or numbers", () => {
    expect(formatUsd("12.5")).toBe("$12.50");
    expect(formatUsd(1234.5)).toBe("$1,234.50");
    expect(formatPercentFromRatio("0.3333")).toBe("33.3%");
    expect(formatRoiPercent(233.3)).toBe("233.3%");
  });

  it("does not round a real sub-cent cost down to free", () => {
    expect(formatUsd(0.0004)).toBe("<$0.01");
    expect(formatUsd(0)).toBe("$0.00");
  });

  it("keeps the tenant's own currency and never converts", () => {
    expect(formatMoney(2500, "INR")).toBe("2,500 INR");
    expect(formatCharges(12345, "INR")).toBe("123.45 INR");
    expect(formatCharges(0, "INR")).toBe(DASH);
    expect(formatCharges(500, null)).toBe(DASH);
  });
});

describe("budget bar", () => {
  it("is neutral without a cap or a percentage", () => {
    expect(budgetTone(null, false)).toBe("none");
    expect(budgetTone(50, false)).toBe("none");
    expect(budgetTone(null, true)).toBe("none");
  });

  it("turns warn at 80 and over at 100, matching the two alerts", () => {
    expect(budgetTone(79.9, true)).toBe("ok");
    expect(budgetTone(80, true)).toBe("warn");
    expect(budgetTone("99.9", true)).toBe("warn");
    expect(budgetTone(100, true)).toBe("over");
    expect(budgetTone(250, true)).toBe("over");
  });

  it("never fills past the bar", () => {
    expect(budgetFill(250)).toBe(100);
    expect(budgetFill(-5)).toBe(0);
    expect(budgetFill(null)).toBe(0);
    expect(budgetFill("42.5")).toBe(42.5);
  });
});

describe("roi notes", () => {
  it("maps each server note to a message key, and OK to none", () => {
    expect(roiNoteKey("NO_ASSUMPTIONS")).toBe("roiNoteNoAssumptions");
    expect(roiNoteKey("CURRENCY_MISMATCH")).toBe("roiNoteCurrency");
    expect(roiNoteKey("COST_INCOMPLETE")).toBe("roiNoteIncomplete");
    expect(roiNoteKey("OK")).toBeNull();
  });
});

describe("totals", () => {
  it("sums rows, is incomplete if any row is, and is zero for no rows", () => {
    const t = totals([row({ conversations: 2, resolved: 1, estimatedCostUsd: "1.5" }), row({ conversations: 3, resolved: 0, estimatedCostUsd: 2, costComplete: false })]);
    expect(t).toEqual({ conversations: 5, resolved: 1, estimatedCostUsd: 3.5, costComplete: false });
    expect(totals([])).toEqual({ conversations: 0, resolved: 0, estimatedCostUsd: 0, costComplete: true });
  });
});

describe("date range", () => {
  it("builds inclusive UTC presets", () => {
    expect(presetRange(7, new Date("2026-10-14T12:00:00Z"))).toEqual({ from: "2026-10-08", to: "2026-10-14" });
    expect(presetRange(1, new Date("2026-10-14T23:59:00Z"))).toEqual({ from: "2026-10-14", to: "2026-10-14" });
  });

  it("validates format, order and the one-year limit the server also enforces", () => {
    expect(validateRange("2026-10-01", "2026-10-14")).toBeNull();
    expect(validateRange("", "2026-10-14")).toBe("rangeInvalid");
    expect(validateRange("2026-10-15", "2026-10-14")).toBe("rangeOrder");
    expect(validateRange("2024-01-01", "2026-10-14")).toBe("rangeTooLong");
  });
});

describe("tenant-typed amounts", () => {
  it("treats blank as no assumption and rejects junk and negatives", () => {
    expect(parseAmount("")).toBeNull();
    expect(parseAmount("  ")).toBeNull();
    expect(parseAmount("12.5")).toBe(12.5);
    expect(parseAmount("0")).toBe(0);
    expect(parseAmount("-1")).toBeUndefined();
    expect(parseAmount("abc")).toBeUndefined();
  });
});
