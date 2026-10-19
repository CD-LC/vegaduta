import { describe, expect, it } from "vitest";
import { formatToolMs, toolCallSummary } from "./NodeLivePanel";

describe("toolCallSummary", () => {
  it("returns zeros for empty or missing input", () => {
    expect(toolCallSummary(undefined)).toEqual({ running: 0, ok: 0, error: 0, totalMs: 0 });
    expect(toolCallSummary(null)).toEqual({ running: 0, ok: 0, error: 0, totalMs: 0 });
    expect(toolCallSummary([])).toEqual({ running: 0, ok: 0, error: 0, totalMs: 0 });
  });

  it("counts each status and sums finite durations", () => {
    const s = toolCallSummary([
      { status: "ok", ms: 120 },
      { status: "ok", ms: 80 },
      { status: "error", ms: 40 },
      { status: "running" },
      { status: "running", ms: Number.NaN },
      { status: "ok", ms: -5 },
    ]);
    expect(s).toEqual({ running: 2, ok: 3, error: 1, totalMs: 240 });
  });
});

describe("formatToolMs", () => {
  it("formats ms, seconds and minutes; blanks invalid values", () => {
    expect(formatToolMs(undefined)).toBe("");
    expect(formatToolMs(-1)).toBe("");
    expect(formatToolMs(320)).toMatch(/^320 ms$/);
    expect(formatToolMs(1400)).toMatch(/^1[.,]4 s$/);
    expect(formatToolMs(126_000)).toMatch(/^2[.,]1 min$/);
  });
});
