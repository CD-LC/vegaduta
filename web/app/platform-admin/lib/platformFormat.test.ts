import { describe, it, expect } from "vitest";
import {
  UNAVAILABLE,
  formatCountOrUnavailable,
  formatExact,
  formatRelative,
  formatUsdOrUnavailable,
} from "./platformFormat";

describe("formatUsdOrUnavailable", () => {
  it("null/undefined/garbage is unavailable, never $0", () => {
    expect(formatUsdOrUnavailable(null)).toBe(UNAVAILABLE);
    expect(formatUsdOrUnavailable(undefined)).toBe(UNAVAILABLE);
    expect(formatUsdOrUnavailable("")).toBe(UNAVAILABLE);
    expect(formatUsdOrUnavailable("abc")).toBe(UNAVAILABLE);
    expect(formatUsdOrUnavailable(Number.NaN)).toBe(UNAVAILABLE);
  });

  it("a measured zero stays $0.00, sub-dollar keeps 4 decimals", () => {
    expect(formatUsdOrUnavailable(0)).toBe("$0.00");
    expect(formatUsdOrUnavailable(0.00421)).toBe("$0.0042");
    expect(formatUsdOrUnavailable(12.345)).toBe("$12.35");
    expect(formatUsdOrUnavailable("3.5")).toBe("$3.50");
  });
});

describe("formatCountOrUnavailable", () => {
  it("distinguishes unavailable from zero", () => {
    expect(formatCountOrUnavailable(null)).toBe(UNAVAILABLE);
    expect(formatCountOrUnavailable(0)).toBe("0");
    expect(formatCountOrUnavailable(1234567)).toBe("1,234,567");
  });
});

describe("formatRelative", () => {
  const now = Date.parse("2026-10-09T12:00:00Z");
  it("buckets past instants", () => {
    expect(formatRelative("2026-10-09T11:59:40Z", now)).toBe("just now");
    expect(formatRelative("2026-10-09T11:55:00Z", now)).toBe("5m ago");
    expect(formatRelative("2026-10-09T09:00:00Z", now)).toBe("3h ago");
    expect(formatRelative("2026-09-27T12:00:00Z", now)).toBe("12d ago");
    expect(formatRelative("2026-06-09T12:00:00Z", now)).toBe("4mo ago");
    expect(formatRelative("2024-10-01T12:00:00Z", now)).toBe("2y ago");
  });

  it("future instants read forward (clock skew), missing reads unavailable", () => {
    expect(formatRelative("2026-10-09T15:00:00Z", now)).toBe("in 3h");
    expect(formatRelative(null, now)).toBe(UNAVAILABLE);
    expect(formatRelative("not a date", now)).toBe(UNAVAILABLE);
  });
});

describe("formatExact", () => {
  it("unavailable for missing or unparseable values", () => {
    expect(formatExact(null)).toBe(UNAVAILABLE);
    expect(formatExact("nope")).toBe(UNAVAILABLE);
    expect(formatExact("2026-10-09T12:00:00Z")).not.toBe(UNAVAILABLE);
  });
});
