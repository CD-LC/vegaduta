// What the live chips and outcome notices can really show, checked against what core really sends.
//
// The chips for "retrying", "switched to a backup model", "continuing" and "waiting for approval", and the
// cut-off / refused / unusable-tool-call notices (with their "Continue" button), are driven by the lifecycle step
// events named in lib/turnLifecycle.ts. The unit tests feed those events by hand, which proves the UI reacts to them
// but not that core ever sends them. This file reads core's step-event source (when the checkout has it) and pins
// both halves:
//   1. the payloads core sends (phase, tool_call, tool_result, error..., and now finish, retry, failover,
//      continuation and approval_required) light the right chips and notices, with the exact fields core writes;
//   2. core still sends each lifecycle event, under the name this console listens for, with the fields this console
//      reads. If core renames or drops one, the matching test here fails instead of a chip silently never showing.
// The reply-text markers stay only as the fallback for a turn that has no `finish` event (an older core).
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { normalizeServerStep } from "../AgentActivityTrace";
import {
  APPROVAL_STEP_TYPES,
  CONTINUATION_STEP_TYPES,
  FAILOVER_STEP_TYPES,
  FINISH_STEP_TYPES,
  LIFECYCLE_REASON_KEYS,
  RETRY_STEP_TYPES,
} from "../../../lib/turnLifecycle";
import { GUARDRAIL_SUBJECT_FAILOVER_BLOCKED, GUARDRAIL_SUBJECT_TOOLS_OFF, chipText, deriveLiveChips } from "./liveStatus";
import { deriveTurnOutcome } from "./turnOutcome";
import en from "../../../../messages/en.json";

const REPO_ROOT = join(__dirname, "..", "..", "..", "..", "..");
const PAYLOADS = join(
  REPO_ROOT,
  "core/agents/src/main/java/com/agenticai/core/agents/tools/AgentStepEventPayloads.java"
);

/** Every event type core builds, read from `event.put("type", "<name>")` in the payload factory. */
function coreEmittedTypes(): Set<string> | null {
  if (!existsSync(PAYLOADS)) return null;
  const src = readFileSync(PAYLOADS, "utf8");
  return new Set([...src.matchAll(/event\.put\("type",\s*"([a-z_]+)"\)/g)].map((m) => m[1]));
}

/** The field names the factory of `type` writes (after "type"), in order; null when the source is not here. */
function coreFieldsOf(type: string): string[] | null {
  if (!existsSync(PAYLOADS)) return null;
  const src = readFileSync(PAYLOADS, "utf8");
  const start = src.indexOf(`event.put("type", "${type}")`);
  if (start < 0) return [];
  const end = src.indexOf("return event;", start);
  return [...src.slice(start, end).matchAll(/event\.put\("([A-Za-z]+)"/g)].map((m) => m[1]).filter((f) => f !== "type");
}

const steps = (...events: Array<Record<string, unknown>>) => events.map((e, i) => normalizeServerStep(e, i + 1));
const chipKinds = (chips: ReturnType<typeof deriveLiveChips>) => chips.map((c) => c.kind);
const base = { sending: true, hasText: false, waitingApprovals: 0, approvalContinuing: false };
const t = (key: string, vars?: Record<string, string | number>) =>
  String((en.agentPlayground as Record<string, string>)[key] ?? key).replace(/\{(\w+)\}/g, (_, k) => String(vars?.[k] ?? `{${k}}`));

describe("live chips from the payloads core sends today", () => {
  it("run_started + phase events walk preparing -> model call", () => {
    const early = steps({ type: "run_started", sessionId: "s", agentId: "a" }, { type: "phase", phase: "guardrails", ms: 3 });
    expect(chipKinds(deriveLiveChips({ ...base, steps: early }))).toEqual(["preparing"]);
    const model = steps(
      { type: "phase", phase: "guardrails", ms: 3 },
      { type: "phase", phase: "routing", ms: 9 },
      { type: "phase", phase: "context", ms: 20 },
      { type: "phase", phase: "model", ms: 41 }
    );
    expect(chipKinds(deriveLiveChips({ ...base, steps: model }))).toEqual(["modelCall"]);
  });

  it("an open tool_call shows the tool, and its tool_result closes it", () => {
    const open = steps({ type: "phase", phase: "model", ms: 41 }, { type: "tool_call", id: 1, tool: "web_search", args: "{}" });
    expect(deriveLiveChips({ ...base, steps: open })).toMatchObject([{ kind: "tool", tool: "web_search" }]);
    const done = steps(
      { type: "tool_call", id: 1, tool: "web_search", args: "{}" },
      { type: "tool_result", id: 1, tool: "web_search", preview: "ok", ms: 80 }
    );
    expect(chipKinds(deriveLiveChips({ ...base, steps: done }))).not.toContain("tool");
  });

  it("heartbeat, memory, guardrail, hook and context_compacted events never invent a chip", () => {
    const quiet = steps(
      { type: "phase", phase: "model", ms: 41 },
      { type: "heartbeat", ms: 5000 },
      { type: "memory", blocks: 1, blockLabels: ["persona"], privateFacts: 0, sharedFacts: 0, factIds: [] },
      { type: "guardrail", tool: "input", reason: "ok" },
      { type: "context_compacted", tokensBefore: 9000, tokensAfter: 3000, cleared: 2, compacted: 4, summary: "x" }
    );
    expect(chipKinds(deriveLiveChips({ ...base, steps: quiet }))).toEqual(["modelCall"]);
  });
});

describe("the two recoveries core reports as guardrail events", () => {
  // DegradedRetrySignal sends these today: a retry with the tools withheld, and a backup model NOT used because the
  // turn had already run tools. Both are `guardrail` events whose `tool` is the subject, not a tool name.
  it("a guardrail event about the tools shows 'trying again without tools'", () => {
    const s = steps(
      { type: "phase", phase: "model", ms: 41 },
      { type: "guardrail", tool: "tools", reason: "Tools are switched off for this retry." }
    );
    expect(chipKinds(deriveLiveChips({ ...base, steps: s }))).toEqual(["modelCall", "toolsOff"]);
  });

  it("a guardrail event about failover shows that no backup model was used", () => {
    const s = steps(
      { type: "tool_call", id: 1, tool: "send_email", args: "{}" },
      { type: "tool_result", id: 1, tool: "send_email", preview: "sent", ms: 80 },
      { type: "guardrail", tool: "failover", reason: "A backup model was not used because tools already ran." }
    );
    expect(chipKinds(deriveLiveChips({ ...base, steps: s }))).toContain("failoverBlocked");
  });

  it("a guardrail event about a real tool or the input shows neither chip", () => {
    const s = steps({ type: "guardrail", tool: "web_search", reason: "blocked" }, { type: "guardrail", tool: "input", reason: "x" });
    const kinds = chipKinds(deriveLiveChips({ ...base, steps: s }));
    expect(kinds).not.toContain("toolsOff");
    expect(kinds).not.toContain("failoverBlocked");
  });

  it("core still sends those two subjects (this test reads the source)", () => {
    const file = join(REPO_ROOT, "core/agents-turn/src/main/java/com/agenticai/core/agents/turn/DegradedRetrySignal.java");
    if (!existsSync(file)) return;
    const src = readFileSync(file, "utf8");
    expect(src).toContain(`emitGuardrail(stepEvents, "${GUARDRAIL_SUBJECT_FAILOVER_BLOCKED}"`);
    expect(src).toMatch(new RegExp(`stepEvents\\.guardrail\\(\\s*"${GUARDRAIL_SUBJECT_TOOLS_OFF}"`));
  });
});

describe("the lifecycle events core sends, as the console reads them", () => {
  // The exact payloads core writes (AgentStepEventPayloads; core's TurnLifecycleStepEventsE2ETest asserts the same
  // values on a real turn). Update both together.
  const RETRY = { type: "retry", attempt: 2, maxAttempts: 3, reason: "rate_limited" };
  const FAILOVER = { type: "failover", toModel: "gpt-4o-mini", reason: "provider_unavailable" };
  const CONTINUATION = { type: "continuation", pass: 1, maxPasses: 2, reason: "cut_off" };
  const APPROVAL = { type: "approval_required", tool: "send_email", approvalId: "c0ffee00-0000-4000-8000-000000000001" };

  it("a retry event lights the retrying chip with the try about to run and a reason in the bundle's words", () => {
    const chips = deriveLiveChips({ ...base, steps: steps({ type: "phase", phase: "model", ms: 4 }, RETRY) });
    const chip = chips.find((c) => c.kind === "retrying");
    expect(chip).toMatchObject({ attempt: 2, maxAttempts: 3, reasonCode: "rate_limited", tone: "attention" });
    expect(chipText(t, chip!)).toBe("Trying again (attempt 2 of 3): rate limited");
  });

  it("a failover event lights the backup-model chip with the model and the reason", () => {
    const chip = deriveLiveChips({ ...base, steps: steps(FAILOVER) }).find((c) => c.kind === "failover");
    expect(chip).toMatchObject({ model: "gpt-4o-mini", reasonCode: "provider_unavailable" });
    expect(chipText(t, chip!)).toBe("Switched to gpt-4o-mini: provider unavailable");
  });

  it("a failover to a model core cannot name still says a backup was used", () => {
    const chip = deriveLiveChips({ ...base, steps: steps({ type: "failover", reason: "timeout" }) }).find(
      (c) => c.kind === "failover"
    );
    expect(chipText(t, chip!)).toBe("Switched to a backup model: timed out");
  });

  it("a continuation event lights the continuing chip with its pass numbers", () => {
    const chip = deriveLiveChips({ ...base, steps: steps(CONTINUATION) }).find((c) => c.kind === "continuing");
    expect(chip).toMatchObject({ pass: 1, maxPasses: 2 });
    expect(chip?.afterApproval).toBeUndefined();
    expect(chipText(t, chip!)).toBe("Continuing the reply (pass 1 of 2)");
  });

  it("a continuation after a decided approval shows the carrying-on chip once, with or without the queue's flag", () => {
    const approval = steps({ type: "continuation", pass: 1, maxPasses: 1, reason: "approval" });
    const fromEvent = deriveLiveChips({ ...base, steps: approval }).filter((c) => c.kind === "continuing");
    expect(fromEvent).toEqual([expect.objectContaining({ afterApproval: true })]);
    const both = deriveLiveChips({ ...base, steps: approval, approvalContinuing: true }).filter((c) => c.kind === "continuing");
    expect(both).toHaveLength(1);
  });

  it("an approval_required event keeps the waiting chip until a later tool result closes it", () => {
    const parked = steps(
      { type: "tool_call", id: 1, tool: "send_email", args: "{}" },
      { type: "tool_result", id: 1, tool: "send_email", preview: "waiting for approval", ms: 5 },
      APPROVAL
    );
    const chip = deriveLiveChips({ ...base, steps: parked }).find((c) => c.kind === "waitingApproval");
    expect(chip).toMatchObject({ tool: "send_email" });
    expect(parked[2].details).toMatchObject({ approvalId: APPROVAL.approvalId });
    const later = [...parked, ...steps({ type: "tool_result", id: 2, tool: "lookup", preview: "x", ms: 1 })];
    expect(chipKinds(deriveLiveChips({ ...base, steps: later }))).not.toContain("waitingApproval");
  });

  it.each([
    ["stop", null],
    ["length", { kind: "truncated", source: "event" }],
    ["interrupted", { kind: "truncated", source: "event" }],
    ["safety", { kind: "refused", source: "event" }],
    ["malformed_tool_call", { kind: "malformedToolCall", source: "event" }],
    ["empty", { kind: "empty", source: "event" }],
  ])("a finish event with finishReason %s gives the outcome %j", (finishReason, expected) => {
    const outcome = deriveTurnOutcome({ steps: steps({ type: "finish", finishReason }), content: "text" });
    if (expected === null) expect(outcome).toBeNull();
    else expect(outcome).toMatchObject(expected);
  });

  it("the provider's own word for a refusal travels in detail", () => {
    const outcome = deriveTurnOutcome({
      steps: steps({ type: "finish", finishReason: "safety", detail: "content_filter" }),
    });
    expect(outcome).toEqual({ kind: "refused", reason: "content filter", source: "event" });
  });

  it("with a finish event the notice text is never consulted, in any wording or language", () => {
    const stopped = steps({ type: "finish", finishReason: "stop" });
    // A reply that mentions the old marker, or reads like the old notice, is still just an answer.
    expect(deriveTurnOutcome({ steps: stopped, content: "Part [reply interrupted]" })).toBeNull();
    // And a cut-off reply keeps "Continue" when the notice was reworded or is in another language.
    const cut = steps({ type: "finish", finishReason: "length" });
    expect(deriveTurnOutcome({ steps: cut, content: "Teil einer Antwort ... [gekürzt]" })).toMatchObject({
      kind: "truncated",
      source: "event",
    });
  });

  it("every reason code core sends has a sentence in the bundle", () => {
    for (const key of Object.values(LIFECYCLE_REASON_KEYS)) {
      expect((en.agentPlayground as Record<string, string>)[key], key).toBeTruthy();
    }
  });
});

describe("what core sends versus what the lifecycle chips and notices listen for", () => {
  const CANONICAL: Record<string, ReadonlySet<string>> = {
    retry: RETRY_STEP_TYPES,
    failover: FAILOVER_STEP_TYPES,
    continuation: CONTINUATION_STEP_TYPES,
    approval_required: APPROVAL_STEP_TYPES,
    finish: FINISH_STEP_TYPES,
  };

  it("core sends retry, failover, continuation, approval_required and finish events, under names the console accepts", () => {
    const emitted = coreEmittedTypes();
    if (!emitted) return; // the web image build has no core sources; say nothing rather than fail
    expect(emitted.has("phase")).toBe(true); // the parse found the factory at all
    for (const [type, accepted] of Object.entries(CANONICAL)) {
      expect(emitted.has(type), `core no longer builds a "${type}" step event`).toBe(true);
      expect(accepted.has(type), `the console does not listen for "${type}"`).toBe(true);
    }
  });

  it("each event carries the fields the chips and notices read", () => {
    const fields: Record<string, string[]> = {
      retry: ["attempt", "maxAttempts", "reason"],
      failover: ["toModel", "reason"],
      continuation: ["pass", "maxPasses", "reason"],
      approval_required: ["tool", "approvalId"],
      finish: ["finishReason", "detail"],
    };
    for (const [type, expected] of Object.entries(fields)) {
      const sent = coreFieldsOf(type);
      if (!sent) return;
      expect(sent, `fields of the "${type}" event`).toEqual(expected);
    }
  });

  it("the reply-text markers remain only as the fallback for a turn without a finish event", () => {
    const cut = deriveTurnOutcome({ steps: [], content: "Partial answer [reply interrupted]" });
    expect(cut).toEqual({ kind: "truncated", source: "text" });
    expect(deriveTurnOutcome({ steps: steps({ type: "phase", phase: "model", ms: 1 }), content: "x [reply interrupted]" })).toEqual({
      kind: "truncated",
      source: "text",
    });
    expect(deriveTurnOutcome({ steps: [], content: "All good." })).toBeNull();
  });
});
