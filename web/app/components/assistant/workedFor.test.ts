import { describe, expect, it } from "vitest";
import { formatDuration, isVisibleStep, liveStatusKey, skippedReason, summarizeWork } from "./workedFor";

describe("summarizeWork", () => {
  const steps = [
    { type: "run_started", id: 0 },
    { type: "tool_call", id: 1, tool: "web_search" },
    { type: "tool_result", id: 1, tool: "web_search", ms: 300 },
    { type: "tool_call", id: 2, tool: "browser_navigate" },
    { type: "error", id: 2, tool: "browser_navigate", message: "timed out" },
    { type: "guardrail", id: 3, tool: "channel_send_message", message: "needs approval" },
    { type: "tool_call", id: 4, tool: "google_calendar" },
  ];

  it("counts calls, failures and skips and knows when a call is still open", () => {
    const s = summarizeWork(steps, 1_000, null, 61_000);
    expect(s).toMatchObject({ toolCalls: 3, failed: 1, skipped: 1, running: true, durationMs: 60_000 });
  });

  it("prefers the server's run_finished duration once the turn settled", () => {
    const done = [...steps, { type: "tool_result", id: 4 }, { type: "run_finished", id: 9, ms: 4_242 }];
    expect(summarizeWork(done, 1_000, 90_000, 999_999)).toMatchObject({ running: false, durationMs: 4_242 });
    // No run_finished (older core): wall clock between start and end.
    expect(summarizeWork(steps, 1_000, 31_000, 999_999)).toMatchObject({ running: false, durationMs: 30_000 });
  });

  it("handles a turn with no steps and no timing", () => {
    expect(summarizeWork(undefined, null, null, 5)).toEqual({
      toolCalls: 0,
      failed: 0,
      skipped: 0,
      running: false,
      durationMs: null,
    });
  });
});

describe("formatDuration", () => {
  it("formats compactly", () => {
    expect(formatDuration(0)).toBe("<1 s");
    expect(formatDuration(12_400)).toBe("12 s");
    expect(formatDuration(252_000)).toBe("4 m 12 s");
    expect(formatDuration(3_720_000)).toBe("1 h 2 m");
    expect(formatDuration(null)).toBe("");
  });
});

describe("skippedReason / isVisibleStep", () => {
  it("reads the guardrail reason and hides the run frame", () => {
    expect(skippedReason({ type: "guardrail", id: 1, message: " blocked by policy " })).toBe("blocked by policy");
    expect(skippedReason({ type: "guardrail", id: 1 })).toBeNull();
    expect(isVisibleStep({ type: "heartbeat", id: 1 })).toBe(false);
    expect(isVisibleStep({ type: "run_finished", id: 1 })).toBe(false);
    expect(isVisibleStep({ type: "plan", id: 1, message: "first, search" })).toBe(true);
  });
});

describe("liveStatusKey", () => {
  it("reports the freshest activity", () => {
    expect(liveStatusKey([])).toEqual({ key: "starting" });
    expect(liveStatusKey([{ type: "tool_call", id: 1, tool: "web_search" }])).toEqual({
      key: "tool",
      tool: "web_search",
    });
    expect(
      liveStatusKey([
        { type: "tool_call", id: 1, tool: "web_search" },
        { type: "tool_result", id: 1 },
      ])
    ).toEqual({ key: "afterTool" });
    expect(liveStatusKey([{ type: "thinking", id: 1, message: "hmm" }])).toEqual({
      key: "analyzing",
      thinking: "hmm",
    });
    // heartbeat/run frame never drives the status
    expect(
      liveStatusKey([
        { type: "thinking", id: 1, message: "x" },
        { type: "heartbeat", id: 2 },
      ])
    ).toEqual({ key: "analyzing", thinking: "x" });
  });
});
