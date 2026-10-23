import { describe, expect, it } from "vitest";
import type { ActivityStep } from "../AgentActivityTrace";
import { chipText, deriveLiveChips, type LiveChip } from "./liveStatus";

const base = { sending: true, hasText: false, waitingApprovals: 0, approvalContinuing: false };
const kinds = (chips: LiveChip[]) => chips.map((c) => c.kind);

describe("deriveLiveChips", () => {
  it("shows nothing when nothing is happening", () => {
    expect(deriveLiveChips({ ...base, sending: false })).toEqual([]);
  });

  it("says the agent is getting ready before any step arrives", () => {
    expect(kinds(deriveLiveChips(base))).toEqual(["preparing"]);
  });

  it("maps the server's pre-token stages and the model call", () => {
    const stage = (s: string): ActivityStep[] => [{ type: "phase", id: 1, stage: s }];
    expect(kinds(deriveLiveChips({ ...base, steps: stage("guardrails") }))).toEqual(["preparing"]);
    expect(kinds(deriveLiveChips({ ...base, steps: stage("model") }))).toEqual(["modelCall"]);
  });

  it("shows the running tool by name, and the model call again once it has closed", () => {
    const open: ActivityStep[] = [{ type: "tool_call", id: 1, tool: "web_search" }];
    expect(deriveLiveChips({ ...base, steps: open })[0]).toMatchObject({ kind: "tool", tool: "web_search" });
    const closed: ActivityStep[] = [...open, { type: "tool_result", id: 1, tool: "web_search" }];
    expect(kinds(deriveLiveChips({ ...base, steps: closed }))).toEqual(["modelCall"]);
  });

  it("shows writing once reply text has streamed", () => {
    expect(kinds(deriveLiveChips({ ...base, hasText: true }))).toEqual(["writing"]);
  });

  it("puts a decision the person owes first, with the count", () => {
    const chips = deriveLiveChips({ ...base, sending: false, waitingApprovals: 2 });
    expect(chips).toEqual([expect.objectContaining({ kind: "waitingApproval", tone: "attention", count: 2 })]);
  });

  it("reads an approval event as waiting until a later tool result closes it", () => {
    const parked: ActivityStep[] = [{ type: "approval_required", id: 3, tool: "send_email" }];
    expect(kinds(deriveLiveChips({ ...base, steps: parked }))).toContain("waitingApproval");
    const done: ActivityStep[] = [...parked, { type: "tool_result", id: 3, tool: "send_email" }];
    expect(kinds(deriveLiveChips({ ...base, steps: done }))).not.toContain("waitingApproval");
  });

  it("shows the agent carrying on after a decided approval, even with no turn in flight", () => {
    const chips = deriveLiveChips({ ...base, sending: false, approvalContinuing: true });
    expect(chips).toEqual([expect.objectContaining({ kind: "continuing", afterApproval: true })]);
  });

  it("shows a continuation pass with its numbers", () => {
    const steps: ActivityStep[] = [{ type: "continuation", id: 4, details: { pass: 1, maxPasses: 2 } }];
    const chip = deriveLiveChips({ ...base, steps }).find((c) => c.kind === "continuing");
    expect(chip).toMatchObject({ pass: 1, maxPasses: 2 });
  });

  it("shows a retry with attempt and a readable reason", () => {
    const steps: ActivityStep[] = [
      { type: "retry", id: 5, details: { attempt: 2, maxAttempts: 3, reason: "rate_limited" } },
    ];
    expect(deriveLiveChips({ ...base, steps }).find((c) => c.kind === "retrying")).toMatchObject({
      attempt: 2,
      maxAttempts: 3,
      reason: "rate limited",
      tone: "attention",
    });
  });

  it("falls back to the request lifecycle's own retry counter", () => {
    const chips = deriveLiveChips({ ...base, attempt: 2, maxAttempts: 3 });
    expect(chips.find((c) => c.kind === "retrying")).toMatchObject({ attempt: 2, maxAttempts: 3 });
    expect(kinds(deriveLiveChips({ ...base, attempt: 1, maxAttempts: 3 }))).not.toContain("retrying");
  });

  it("shows a switch to a backup model with the model and the reason", () => {
    const steps: ActivityStep[] = [
      { type: "failover", id: 6, details: { toModel: "gpt-4o-mini", reason: "timeout" } },
    ];
    expect(deriveLiveChips({ ...base, steps }).find((c) => c.kind === "failover")).toMatchObject({
      model: "gpt-4o-mini",
      reason: "timeout",
    });
  });

  it("reads other spellings of the same events", () => {
    const steps: ActivityStep[] = [
      { type: "provider_failover", id: 1, message: "primary down" },
      { type: "model_retry", id: 2, message: "overloaded" },
    ];
    expect(kinds(deriveLiveChips({ ...base, steps }))).toEqual(expect.arrayContaining(["failover", "retrying"]));
  });
});

describe("chipText", () => {
  const t = (key: string, vars?: Record<string, string | number>) => (vars ? `${key}:${JSON.stringify(vars)}` : key);

  it("picks the most specific sentence the chip's details allow", () => {
    expect(chipText(t, { id: "a", kind: "tool", tone: "progress", tool: "x" })).toBe('chipTool:{"tool":"x"}');
    expect(chipText(t, { id: "a", kind: "tool", tone: "progress" })).toBe("chipToolUnnamed");
    expect(chipText(t, { id: "a", kind: "retrying", tone: "attention" })).toBe("chipRetrying");
    expect(chipText(t, { id: "a", kind: "retrying", tone: "attention", attempt: 2, maxAttempts: 3, reason: "r" })).toBe(
      'chipRetryingAttemptReason:{"attempt":2,"max":3,"reason":"r"}'
    );
    expect(chipText(t, { id: "a", kind: "failover", tone: "attention", model: "m", reason: "r" })).toBe(
      'chipFailoverToReason:{"model":"m","reason":"r"}'
    );
    expect(chipText(t, { id: "a", kind: "failover", tone: "attention" })).toBe("chipFailover");
    expect(chipText(t, { id: "a", kind: "continuing", tone: "progress", pass: 1, maxPasses: 2 })).toBe(
      'chipContinuingPass:{"pass":1,"max":2}'
    );
    expect(chipText(t, { id: "a", kind: "continuing", tone: "progress" })).toBe("chipContinuing");
  });
});
