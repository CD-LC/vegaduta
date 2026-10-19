import { describe, expect, it } from "vitest";
import {
  SPEND_CAP_ERROR,
  capUsageFraction,
  formatCapUsd,
  formatResetDate,
  knownGuardError,
  nextMonthStartIso,
  parseSpendCapBody,
  parseSpendCapPayload,
  spendCapError,
  spendCapMessage,
} from "./spendCap";
import { READ_ONLY_ERROR, READ_ONLY_MESSAGE } from "./viewAs";

const body = {
  error: SPEND_CAP_ERROR,
  message: "cap",
  capUsd: 50,
  monthToDateUsd: 50.12,
  resetsAt: "2026-11-01T00:00:00Z",
};

describe("parse", () => {
  it("parses the 402 body", () => {
    expect(parseSpendCapBody(body)).toEqual({
      capUsd: 50,
      monthToDateUsd: 50.12,
      resetsAt: "2026-11-01T00:00:00Z",
      message: "cap",
    });
    expect(parseSpendCapBody({ error: "other" })).toBeNull();
    expect(parseSpendCapBody(null)).toBeNull();
    expect(parseSpendCapBody({ error: SPEND_CAP_ERROR, capUsd: "x" })?.capUsd).toBeNull();
  });
  it("parses SSE data text", () => {
    expect(parseSpendCapPayload(JSON.stringify(body))?.capUsd).toBe(50);
    expect(parseSpendCapPayload(" " + JSON.stringify(body))?.capUsd).toBe(50);
    expect(parseSpendCapPayload("plain text")).toBeNull();
    expect(parseSpendCapPayload("{not json")).toBeNull();
    expect(parseSpendCapPayload(null)).toBeNull();
  });
});

describe("format", () => {
  it("formatCapUsd", () => {
    expect(formatCapUsd(50)).toBe("$50");
    expect(formatCapUsd(12.4)).toBe("$12.40");
    expect(formatCapUsd(1500)).toBe("$1,500");
    expect(formatCapUsd(null)).toBe("—");
    expect(formatCapUsd("abc")).toBe("—");
  });
  it("formatResetDate uses the UTC calendar date", () => {
    expect(formatResetDate("2026-11-01T00:00:00Z", "en-US")).toBe("Nov 1, 2026");
    expect(formatResetDate("bad", "en-US")).toBeNull();
    expect(formatResetDate(null)).toBeNull();
  });
  it("nextMonthStartIso", () => {
    expect(nextMonthStartIso("2026-10-01T00:00:00Z")).toBe("2026-11-01T00:00:00.000Z");
    expect(nextMonthStartIso("2026-12-01T00:00:00Z")).toBe("2027-01-01T00:00:00.000Z");
    expect(nextMonthStartIso(null, Date.parse("2026-10-09T12:00:00Z"))).toBe("2026-11-01T00:00:00.000Z");
  });
  it("spendCapMessage", () => {
    const block = parseSpendCapBody(body)!;
    expect(spendCapMessage(block, "en-US")).toBe(
      "Your workspace reached its monthly AI spend limit ($50). It resets on Nov 1, 2026. Contact your platform administrator."
    );
    expect(spendCapMessage({ capUsd: null, monthToDateUsd: null, resetsAt: null, message: null })).toBe(
      "Your workspace reached its monthly AI spend limit. It resets at the start of next month. Contact your platform administrator."
    );
  });
  it("capUsageFraction", () => {
    expect(capUsageFraction({ capUsd: 50, monthToDateUsd: 12.5 })).toBe(0.25);
    expect(capUsageFraction({ capUsd: 50, monthToDateUsd: 60 })).toBe(1.2);
    expect(capUsageFraction({ capUsd: null, monthToDateUsd: 1 })).toBeNull();
    expect(capUsageFraction({ capUsd: 0, monthToDateUsd: 1 })).toBeNull();
    expect(capUsageFraction(null)).toBeNull();
  });
});

describe("knownGuardError", () => {
  it("maps the spend cap 402", () => {
    const err: any = knownGuardError(402, body);
    expect(err.status).toBe(402);
    expect(err.code).toBe(SPEND_CAP_ERROR);
    expect(err.message).toContain("monthly AI spend limit ($50)");
    expect(err.spendCap.capUsd).toBe(50);
  });
  it("maps the read-only 403", () => {
    const err: any = knownGuardError(403, { error: READ_ONLY_ERROR });
    expect(err.message).toBe(READ_ONLY_MESSAGE);
    expect(err.readOnly).toBe(true);
  });
  it("ignores everything else", () => {
    expect(knownGuardError(402, { error: "payment_required" })).toBeNull();
    expect(knownGuardError(403, { error: "nope" })).toBeNull();
    expect(knownGuardError(500, body)).toBeNull();
  });
  it("spendCapError defaults to 402", () => {
    expect((spendCapError(parseSpendCapBody(body)!) as any).status).toBe(402);
  });
});
