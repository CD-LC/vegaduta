import { describe, expect, it } from "vitest";
import { buildChain } from "./chainTestUtil";
import {
  NO_FILTER,
  buildTimeline,
  categoryCounts,
  filterTimeline,
  formatCostMinor,
  formatMs,
  formatStamp,
  timelineStats,
  type TimelineCategory,
} from "./timelineModel";

const FULL = [
  { type: "INTAKE", summary: "messageChars=12 attachments=0 tools=2" },
  { type: "MODEL_CALL", summary: "provider=openai model=gpt-x route=primary" },
  { type: "TOOL_CALL", summary: "tool=lookup status=ok resultSha=abc ms=120" },
  { type: "TOOL_CALL", summary: "tool=send_email status=parked resultSha=def ms=3" },
  { type: "APPROVAL", summary: "tool=send_email decision=pending request=aaaa" },
  { type: "APPROVAL", summary: "tool=send_email decision=decided:completed request=aaaa" },
  { type: "GUARD", summary: "guard=continuation outcome=pass-1" },
  { type: "GUARD", summary: "guard=finish-tool-call-retry outcome=pass-1" },
  { type: "MODEL_CALL", summary: "provider=anthropic model=claude route=fallback" },
  { type: "MODEL_CALL", summary: "provider=openai model=gpt-x route=retry-tools-disabled" },
  { type: "GUARD", summary: "guard=outbound-enforced outcome=withheld" },
  { type: "GUARD", summary: "guard=outbound-audit outcome=ran" },
  { type: "GUARD", summary: "guard=response-cache outcome=hit" },
  { type: "VERIFY", summary: "check=reflexion passed=false" },
  { type: "TOOL_CALL", summary: "tool=boom status=error resultSha=x ms=7" },
  { type: "COMMIT", summary: "status=error:Timeout answerChars=0 tokensIn=100 tokensOut=40 costMinor=3" },
];

describe("timelineModel", () => {
  it("classifies every event the recorder writes", async () => {
    const entries = buildTimeline(await buildChain(FULL));
    const cats = entries.map((e) => e.category);
    expect(cats).toEqual([
      "intake", "model", "tool", "tool", "approval", "approval", "continuation", "retry",
      "retry", "retry", "guardrail", "guardrail", "cache", "verify", "tool", "finish",
    ]);
  });

  it("marks parked, blocked-style and failed events by severity and gives retries a reason", async () => {
    const entries = buildTimeline(await buildChain(FULL));
    expect(entries[2].severity).toBe("ok");
    expect(entries[3].severity).toBe("warn");
    expect(entries[3].reasonKey).toBe("reasonToolParked");
    expect(entries[4].titleKey).toBe("tlApprovalRequested");
    expect(entries[5].titleKey).toBe("tlApprovalDecided");
    expect(entries[5].titleValues.decision).toBe("completed");
    expect(entries[8].titleKey).toBe("tlFailover");
    expect(entries[8].reasonKey).toBe("reasonFallbackProvider");
    expect(entries[9].reasonKey).toBe("reasonToolsDisabledRetry");
    expect(entries[10].severity).toBe("warn");
    expect(entries[11].severity).toBe("ok");
    expect(entries[13].severity).toBe("warn");
    expect(entries[14].severity).toBe("error");
    expect(entries[15].severity).toBe("error");
  });

  it("computes offsets, gaps and tool durations", async () => {
    const entries = buildTimeline(await buildChain(FULL));
    expect(entries[0].offsetMs).toBe(0);
    expect(entries[0].gapMs).toBeNull();
    expect(entries[1].gapMs).toBe(250);
    expect(entries[3].offsetMs).toBe(750);
    expect(entries[2].durationMs).toBe(120);
    expect(entries[1].durationMs).toBeNull();
  });

  it("totals tokens and cost only from events that carry them", async () => {
    const stats = timelineStats(buildTimeline(await buildChain(FULL)));
    expect(stats.tokensIn).toBe(100);
    expect(stats.tokensOut).toBe(40);
    expect(stats.costMinor).toBe(3);
    expect(stats.retries).toBe(3);
    expect(stats.approvals).toBe(2);
    expect(stats.toolCalls).toBe(3);
    expect(stats.toolMs).toBe(130);
    expect(stats.finishStatus).toBe("error:Timeout");
  });

  it("reports tokens and cost as null, not zero, when nothing recorded them", async () => {
    const stats = timelineStats(buildTimeline(await buildChain(FULL.slice(0, 3))));
    expect(stats.tokensIn).toBeNull();
    expect(stats.costMinor).toBeNull();
    expect(stats.finishStatus).toBeNull();
  });

  it("filters by category, by problems and by text", async () => {
    const entries = buildTimeline(await buildChain(FULL));
    const only = (c: TimelineCategory) => filterTimeline(entries, { ...NO_FILTER, categories: new Set([c]) });
    expect(only("tool")).toHaveLength(3);
    expect(only("approval")).toHaveLength(2);
    expect(filterTimeline(entries, { ...NO_FILTER, problemsOnly: true }).every((e) => e.severity === "warn" || e.severity === "error")).toBe(true);
    expect(filterTimeline(entries, { ...NO_FILTER, text: "SEND_EMAIL" })).toHaveLength(3);
    expect(filterTimeline(entries, NO_FILTER)).toHaveLength(FULL.length);
    expect(categoryCounts(entries).retry).toBe(3);
  });

  it("formats durations", () => {
    expect(formatMs(850)).toBe("850 ms");
    expect(formatMs(2400)).toBe("2.4 s");
    expect(formatMs(65_000)).toBe("1 m 5 s");
  });

  it("marks every approval outcome other than a completed one as needing attention", async () => {
    const outcomes = ["pending", "decided:completed", "decided:denied", "decided:failed", "decided:expired", "decided:unknown"];
    const entries = buildTimeline(
      await buildChain(outcomes.map((d) => ({ type: "APPROVAL", summary: `tool=send_email decision=${d} request=aaaa` })))
    );
    expect(entries.map((e) => e.severity)).toEqual(["warn", "ok", "warn", "warn", "warn", "warn"]);
    expect(entries[0].titleKey).toBe("tlApprovalRequested");
    expect(entries[2].titleKey).toBe("tlApprovalDecided");
  });

  it("shows a read-only call the platform let through under the Supervised tier as information, not as a decision to review", async () => {
    const [entry] = buildTimeline(
      await buildChain([
        { type: "APPROVAL", summary: "tool=web_search decision=auto-allowed reason=read-only-under-SUPERVISED" },
      ])
    );
    expect(entry.category).toBe("approval");
    expect(entry.severity).toBe("info");
    expect(entry.titleKey).toBe("tlApprovalAutoAllowed");
    expect(entry.titleValues).toEqual({ tool: "web_search" });
    expect(entry.fields.reason).toBe("read-only-under-SUPERVISED");
  });

  it("formats a timestamp in the given locale with its zone named, and falls back to the raw value", () => {
    const out = formatStamp("2026-10-15T10:00:00.000Z", "en");
    expect(out).toMatch(/2026/);
    expect(out).toMatch(/Oct/);
    expect(out).toMatch(/[A-Z]{2,5}|GMT[+-]?\d*/);
    expect(out).not.toContain("T10:00");
    expect(formatStamp("not a date", "en")).toBe("not a date");
  });

  it("formats cost as money from minor units, in the summary's currency when it names one", () => {
    expect(formatCostMinor(250, "en")).toBe("$2.50");
    expect(formatCostMinor(250, "en", "EUR")).toBe("\u20ac2.50");
    expect(formatCostMinor(3, "en", "not-a-currency")).toBe("0.03 not-a-currency");
  });
});
