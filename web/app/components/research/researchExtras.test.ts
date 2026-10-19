import { describe, expect, it } from "vitest";
import { BASE_POLL_MS, GIVE_UP_AFTER, HIDDEN_POLL_MS, MAX_POLL_MS, PARKED_POLL_MS, nextPollDelay, shouldGiveUp } from "./pollSchedule";
import { filterHistory, matchesFilter, parsePins, togglePin } from "./researchHistory";
import { appendTranscript } from "./useDictation";
import { setResearchPrefill, takeResearchPrefill } from "./prefill";

describe("nextPollDelay", () => {
  it("polls at the base rate, slower when parked or hidden", () => {
    expect(nextPollDelay({ failures: 0 })).toBe(BASE_POLL_MS);
    expect(nextPollDelay({ failures: 0, parked: true })).toBe(PARKED_POLL_MS);
    expect(nextPollDelay({ failures: 0, hidden: true })).toBe(HIDDEN_POLL_MS);
  });
  it("backs off exponentially on failures and is capped", () => {
    expect(nextPollDelay({ failures: 1 })).toBe(BASE_POLL_MS * 2);
    expect(nextPollDelay({ failures: 2 })).toBe(BASE_POLL_MS * 4);
    expect(nextPollDelay({ failures: 50, parked: true })).toBe(MAX_POLL_MS);
  });
  it("gives up after repeated failures", () => {
    expect(shouldGiveUp(GIVE_UP_AFTER - 1)).toBe(false);
    expect(shouldGiveUp(GIVE_UP_AFTER)).toBe(true);
  });
});

describe("history filtering", () => {
  const rows = [
    { id: "a", status: "SUCCEEDED", goalPreview: "Research question:\nTea and health\n\nDepth (pass as effort): auto" },
    { id: "b", status: "RUNNING", goalPreview: "Research question:\nCoffee prices" },
    { id: "c", status: "FAILED", goalPreview: "Research question:\nTea tariffs" },
    { id: "d", status: "AWAITING_APPROVAL", goalPreview: null },
  ];
  it("matches status buckets", () => {
    expect(matchesFilter("AWAITING_APPROVAL", "running")).toBe(true);
    expect(matchesFilter("CANCELLED", "issues")).toBe(true);
    expect(matchesFilter("SUCCEEDED", "issues")).toBe(false);
  });
  it("searches the question text, every word required, not the brief scaffolding", () => {
    expect(filterHistory(rows, { query: "tea" }).map((r) => r.id)).toEqual(["a", "c"]);
    expect(filterHistory(rows, { query: "TEA tariffs" }).map((r) => r.id)).toEqual(["c"]);
    expect(filterHistory(rows, { query: "effort" })).toEqual([]);
    expect(filterHistory(rows, { filter: "done" }).map((r) => r.id)).toEqual(["a"]);
  });
  it("floats pinned runs to the top without reordering the rest", () => {
    expect(filterHistory(rows, { pinned: new Set(["c"]) }).map((r) => r.id)).toEqual(["c", "a", "b", "d"]);
  });
  it("parses and toggles pins defensively", () => {
    expect(parsePins("not json")).toEqual([]);
    expect(parsePins('["a",1,"b"]')).toEqual(["a", "b"]);
    expect(togglePin(["a"], "b")).toEqual(["b", "a"]);
    expect(togglePin(["a", "b"], "a")).toEqual(["b"]);
  });
});

describe("dictation + prefill", () => {
  it("appends a spoken chunk with one space", () => {
    expect(appendTranscript("", " hello ")).toBe("hello");
    expect(appendTranscript("What is  ", "tea")).toBe("What is tea");
    expect(appendTranscript("kept", "   ")).toBe("kept");
  });
  it("hands a prefill over exactly once", () => {
    setResearchPrefill("my question");
    expect(takeResearchPrefill()).toBe("my question");
    expect(takeResearchPrefill()).toBe("");
  });
});
