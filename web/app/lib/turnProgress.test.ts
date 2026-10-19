import { describe, expect, it } from "vitest";
import { deriveTurnProgress, formatElapsed, thinkingTail } from "./turnProgress";
import type { ActivityStep } from "../components/agents/AgentActivityTrace";

const now = 10_000;

describe("deriveTurnProgress", () => {
  it("starts as 'starting' with no events and counts from startedAt", () => {
    expect(deriveTurnProgress([], { hasText: false, startedAt: 7_500, now })).toEqual({ phase: "starting", elapsedMs: 2_500 });
  });

  it("follows the server's pre-token phases in order", () => {
    const steps: ActivityStep[] = [
      { type: "phase", id: 1, stage: "guardrails", ms: 40 },
      { type: "phase", id: 2, stage: "routing", ms: 90 },
    ];
    expect(deriveTurnProgress(steps, { hasText: false, now }).phase).toBe("routing");
    steps.push({ type: "phase", id: 3, stage: "context", ms: 120 });
    expect(deriveTurnProgress(steps, { hasText: false, now }).phase).toBe("context");
    steps.push({ type: "run_started", id: 4 }, { type: "phase", id: 5, stage: "model", ms: 900 });
    expect(deriveTurnProgress(steps, { hasText: false, now })).toEqual({ phase: "model", elapsedMs: 900 });
  });

  it("uses the latest heartbeat for elapsed time when the caller has no start", () => {
    const steps: ActivityStep[] = [
      { type: "phase", id: 1, stage: "model", ms: 500 },
      { type: "heartbeat", id: 2, ms: 15_000 },
    ];
    expect(deriveTurnProgress(steps, { hasText: false, now }).elapsedMs).toBe(15_000);
  });

  it("joins a run of thinking deltas into one reasoning text", () => {
    const steps: ActivityStep[] = [
      { type: "phase", id: 1, stage: "model" },
      { type: "thinking", id: 2, message: "The user wants " },
      { type: "thinking", id: 3, message: "a summary." },
    ];
    expect(deriveTurnProgress(steps, { hasText: false, now })).toMatchObject({
      phase: "thinking",
      thinking: "The user wants a summary.",
    });
  });

  it("an open tool call wins even once text has streamed", () => {
    const steps: ActivityStep[] = [{ type: "tool_call", id: 1, tool: "web_search" }];
    expect(deriveTurnProgress(steps, { hasText: true, now })).toMatchObject({ phase: "tool", tool: "web_search" });
    steps.push({ type: "tool_result", id: 1, tool: "web_search" });
    expect(deriveTurnProgress(steps, { hasText: false, now }).phase).toBe("afterTool");
    expect(deriveTurnProgress(steps, { hasText: true, now }).phase).toBe("writing");
  });

  it("a tool error reads as recovering and an unknown server phase as model", () => {
    expect(deriveTurnProgress([{ type: "tool_call", id: 1, tool: "x" }, { type: "error", id: 1, tool: "x" }], { hasText: false, now }).phase).toBe("recovering");
    expect(deriveTurnProgress([{ type: "phase", id: 1, stage: "something_new" }], { hasText: false, now }).phase).toBe("model");
  });
});

describe("formatElapsed / thinkingTail", () => {
  it("formats compactly", () => {
    expect(formatElapsed(null)).toBe("");
    expect(formatElapsed(12_400)).toBe("12 s");
    expect(formatElapsed(65_000)).toBe("1 m 05 s");
  });

  it("keeps the end of a long thought", () => {
    expect(thinkingTail("  a   b ")).toBe("a b");
    const long = "x".repeat(200) + "END";
    expect(thinkingTail(long, 10)).toBe("…xxxxxxxEND");
  });
});
