// agentkit/context + slices — what is in the window, and what goes first.
//
// The eviction ORDER is the contract, so the tests assert order, not just
// "it fits": oldest tool output before any step is folded; steps folded oldest
// first into the summary; the task, the plan and the newest pinned steps never
// touched; least-recently-used file excerpt out first. Every case also asserts
// the prompt really is within the budget — an eviction policy that is orderly
// but still overflows has failed at its only job.

import { describe, expect, it } from "vitest";

import { planContextBudget } from "./budget";
import { assembleContext, renderCall, summariseTurn } from "./context";
import { newRunState } from "./loop";
import { buildSystemPrompt, buildToolsBlock } from "./protocol";
import { describeSlice, mergeHitRanges, sliceFile } from "./slices";
import { estimateTokens } from "./tokens";
import type { AgentRunState, AgentTurn, FileSlice } from "./types";

const OPTIONS = {
  systemPrompt: buildSystemPrompt({ rootName: "repo", hasTerminal: false }),
  toolsBlock: buildToolsBlock(false),
};
const SMALL = planContextBudget({ contextWindow: 4096, overheadTokens: 64 });

function turn(step: number, observationChars: number, failed = false): AgentTurn {
  return {
    step,
    call: { name: "search_text", args: { query: `q${step}` } },
    note: "",
    observation: `result of step ${step}\n` + "x".repeat(observationChars),
    observationEvicted: false,
    failed,
  };
}

function fileSlice(path: string, touchedStep: number, chars: number): FileSlice {
  return { path, startLine: 1, endLine: 10, totalLines: 10, text: "y".repeat(chars), touchedStep };
}

function stateWith(patch: Partial<AgentRunState>): AgentRunState {
  return { ...newRunState("Make the retry delay configurable."), ...patch };
}

describe("agentkit/context assembleContext", () => {
  it("sends everything untouched when it fits, and reports no evictions", () => {
    const state = stateWith({ turns: [turn(1, 100), turn(2, 100)], plan: [{ text: "find it", status: "doing" }] });
    const out = assembleContext(state, SMALL, OPTIONS);
    expect(out.report.fits).toBe(true);
    expect(out.report.evicted).toEqual([]);
    expect(out.user).toContain("Make the retry delay configurable.");
    expect(out.user).toContain("1. [>] find it");
    expect(out.user).toContain("result of step 1");
    expect(out.nextState.turns).toHaveLength(2);
    // The report describes the prompt that was built, not a separate estimate.
    expect(out.report.usedTokens).toBe(estimateTokens(out.system) + estimateTokens(out.user));
  });

  it("evicts the OLDEST tool output first, before folding any step", () => {
    // Six steps of ~290 tokens each: over the scratchpad, but dropping the
    // oldest outputs is enough — no step should be folded yet.
    const state = stateWith({ turns: [1, 2, 3, 4, 5].map((n) => turn(n, 900)) });
    const out = assembleContext(state, SMALL, OPTIONS);
    expect(out.report.fits).toBe(true);
    const evictedOutputs = out.report.evicted.filter((e) => e.what.startsWith("output of step")).map((e) => e.what);
    expect(evictedOutputs[0]).toContain("step 1");
    // Strictly oldest-first.
    const steps = evictedOutputs.map((w) => Number(/step (\d+)/.exec(w)![1]));
    expect(steps).toEqual([...steps].sort((a, b) => a - b));
    // The evicted step is still listed — only its output went.
    expect(out.nextState.turns[0].step).toBe(1);
    expect(out.nextState.turns[0].observationEvicted).toBe(true);
    expect(out.user).toContain("output dropped to save context");
    // The newest pinned steps are verbatim.
    const last = out.nextState.turns[out.nextState.turns.length - 1];
    expect(last.observationEvicted).toBe(false);
    expect(out.user).toContain("result of step 5");
  });

  it("then folds the oldest steps into the running summary, in order, and keeps the pinned ones", () => {
    const state = stateWith({ turns: Array.from({ length: 40 }, (_, i) => turn(i + 1, 300, i === 2)) });
    const out = assembleContext(state, SMALL, OPTIONS);
    expect(out.report.fits).toBe(true);
    expect(out.nextState.summary.length + out.nextState.summaryDropped).toBeGreaterThan(0);
    // Whatever survives verbatim is a contiguous tail ending at the newest step.
    const kept = out.nextState.turns.map((t) => t.step);
    expect(kept[kept.length - 1]).toBe(40);
    expect(kept).toEqual(Array.from({ length: kept.length }, (_, i) => 40 - kept.length + 1 + i));
    expect(kept.length).toBeGreaterThanOrEqual(SMALL.pinnedTurns);
    // Summary lines are oldest-first and a failure is still visible as one.
    const summarySteps = out.nextState.summary.map((l) => Number(/^step (\d+)/.exec(l)![1]));
    expect(summarySteps).toEqual([...summarySteps].sort((a, b) => a - b));
    const folded = [...out.nextState.summary].join("\n");
    if (summarySteps.includes(3)) expect(folded).toContain("FAILED");
    // Folding is recorded only after every older output had been dropped.
    const firstFold = out.report.evicted.findIndex((e) => e.why.includes("running summary"));
    const lastOutputDrop = out.report.evicted.map((e) => e.why).lastIndexOf("oldest tool output goes first");
    expect(firstFold).toBeGreaterThan(-1);
    expect(lastOutputDrop).toBeGreaterThan(-1);
    expect(lastOutputDrop).toBeLessThan(firstFold);
  });

  it("always pins the task, the plan and the newest steps — even when one output alone is over the slice", () => {
    const state = stateWith({
      plan: [{ text: "only step", status: "doing" }],
      turns: [turn(1, 50), turn(2, 60_000)],
    });
    const out = assembleContext(state, SMALL, OPTIONS);
    expect(out.report.fits).toBe(true);
    expect(out.user).toContain("Make the retry delay configurable.");
    expect(out.user).toContain("1. [>] only step");
    // Step 2 is still there, cut — not dropped.
    expect(out.user).toContain("Step 2:");
    expect(out.user).toContain("result of step 2");
    expect(out.nextState.turns.map((t) => t.step)).toEqual([1, 2]);
  });

  it("caps the summary by dropping its OLDEST lines and carrying only the count", () => {
    const summary = Array.from({ length: 400 }, (_, i) => `step ${i + 1}: search_text {"query":"q"} → ok`);
    const out = assembleContext(stateWith({ summary }), SMALL, OPTIONS);
    expect(out.report.fits).toBe(true);
    expect(out.nextState.summaryDropped).toBeGreaterThan(0);
    expect(out.nextState.summary[out.nextState.summary.length - 1]).toContain("step 400:");
    expect(out.user).toContain(`(${out.nextState.summaryDropped} older step(s) not shown)`);
    // A second pass does not double-count.
    const again = assembleContext(out.nextState, SMALL, OPTIONS);
    expect(again.nextState.summaryDropped).toBe(out.nextState.summaryDropped);
  });

  it("evicts file excerpts least-recently-touched first", () => {
    const workingSet = [fileSlice("old.ts", 1, 1200), fileSlice("mid.ts", 5, 1200), fileSlice("new.ts", 9, 1200), fileSlice("newest.ts", 12, 1200)];
    const out = assembleContext(stateWith({ workingSet }), SMALL, OPTIONS);
    expect(out.report.fits).toBe(true);
    const keptPaths = out.nextState.workingSet.map((s) => s.path);
    expect(keptPaths).toContain("newest.ts");
    expect(keptPaths).not.toContain("old.ts");
    // Never keeps an older excerpt while dropping a newer one.
    const keptSteps = out.nextState.workingSet.map((s) => s.touchedStep);
    const droppedSteps = workingSet.filter((s) => !keptPaths.includes(s.path)).map((s) => s.touchedStep);
    expect(Math.min(...keptSteps)).toBeGreaterThan(Math.max(...droppedSteps));
    expect(out.report.filesInWindow.map((f) => f.path)).toEqual(keptPaths);
  });

  it("gives a bigger model more room with the same state", () => {
    const state = stateWith({ turns: Array.from({ length: 12 }, (_, i) => turn(i + 1, 900)) });
    const small = assembleContext(state, SMALL, OPTIONS);
    const large = assembleContext(state, planContextBudget({ contextWindow: 32768 }), OPTIONS);
    expect(large.report.evicted).toEqual([]);
    expect(small.report.evicted.length).toBeGreaterThan(0);
  });

  it("reports fits:false, rather than sending, when the irreducible parts exceed the budget", () => {
    const tiny = planContextBudget({ contextWindow: 512 });
    const out = assembleContext(stateWith({}), tiny, OPTIONS);
    expect(out.report.fits).toBe(false);
  });

  it("never mutates the state it was given", () => {
    const state = stateWith({ turns: Array.from({ length: 30 }, (_, i) => turn(i + 1, 600)) });
    const snapshot = JSON.stringify(state);
    assembleContext(state, SMALL, OPTIONS);
    expect(JSON.stringify(state)).toBe(snapshot);
  });

  it("elides long argument strings in the step log so a written file is not carried through every later step", () => {
    const rendered = renderCall({ name: "write_file", args: { path: "a.ts", content: "z".repeat(5000) } });
    expect(rendered.length).toBeLessThan(260);
    expect(rendered).toContain("(5000 chars)");
    expect(summariseTurn(turn(7, 10, true))).toMatch(/^step 7: search_text .* → FAILED/);
  });
});

describe("agentkit/slices", () => {
  const FILE = Array.from({ length: 600 }, (_, i) => `const value${i + 1} = ${i + 1};`).join("\n") + "\n";

  it("merges overlapping hit windows and clamps them to the file", () => {
    expect(mergeHitRanges([10, 14, 300], 600, 6)).toEqual([
      { start: 4, end: 20 },
      { start: 294, end: 306 },
    ]);
    expect(mergeHitRanges([1, 600, 9999, -3], 600, 6)).toEqual([
      { start: 1, end: 7 },
      { start: 594, end: 600 },
    ]);
  });

  it("returns a small file whole, numbered, and says it is complete", () => {
    const slice = sliceFile({ path: "a.ts", contents: "one\ntwo\nthree\n", capTokens: 500, step: 1 });
    expect(slice.text).toBe("1\tone\n2\ttwo\n3\tthree");
    expect([slice.startLine, slice.endLine, slice.totalLines]).toEqual([1, 3, 3]);
    expect(describeSlice(slice)).not.toContain("partial");
  });

  it("holds ANY file to the hard per-file cap, never cutting a line in half", () => {
    const slice = sliceFile({ path: "big.ts", contents: FILE, capTokens: 300, step: 1 });
    expect(estimateTokens(slice.text)).toBeLessThanOrEqual(300);
    expect(slice.startLine).toBe(1);
    expect(slice.endLine).toBeLessThan(600);
    const lastLine = slice.text.split("\n").pop()!;
    expect(lastLine).toBe(`${slice.endLine}\tconst value${slice.endLine} = ${slice.endLine};`);
    expect(describeSlice(slice)).toContain("partial");
  });

  it("opens a big file at the lines a search matched, not at line 1", () => {
    const slice = sliceFile({ path: "big.ts", contents: FILE, capTokens: 300, step: 1, hitLines: [450] });
    expect(slice.startLine).toBe(444);
    expect(slice.text).toContain("450\tconst value450 = 450;");
    expect(slice.text).not.toContain("\tconst value1 = 1;");
  });

  it("marks the gap between two separate hit ranges", () => {
    const slice = sliceFile({ path: "big.ts", contents: FILE, capTokens: 600, step: 1, hitLines: [20, 500] });
    expect(slice.text).toContain("⋮");
    expect(slice.startLine).toBe(14);
    expect(slice.endLine).toBe(506);
  });

  it("names every range really in a gapped excerpt — never one span covering lines that were not shown", () => {
    const slice = sliceFile({ path: "big.ts", contents: FILE, capTokens: 600, step: 1, hitLines: [20, 500] });
    expect(slice.ranges).toEqual([
      { start: 14, end: 26 },
      { start: 494, end: 506 },
    ]);
    const header = describeSlice(slice);
    expect(header).toContain("lines 14-26, 494-506 of 600");
    expect(header).not.toContain("14-506");
    expect(header).toContain("between the ranges are NOT shown");
    // Every line number in the text is inside a named range.
    for (const row of slice.text.split("\n")) {
      if (row.trim() === "⋮") continue;
      const n = Number(row.split("\t")[0]);
      expect(slice.ranges!.some((r) => n >= r.start && n <= r.end)).toBe(true);
    }
  });

  it("reports a range the cap cut short as what was actually emitted", () => {
    const slice = sliceFile({ path: "big.ts", contents: FILE, capTokens: 120, step: 1, hitLines: [20, 500] });
    const lastRange = slice.ranges![slice.ranges!.length - 1];
    expect(lastRange.end).toBe(slice.endLine);
    expect(slice.endLine).toBeLessThan(506);
    expect(describeSlice(slice)).toContain(`${lastRange.start}-${lastRange.end}`);
  });

  it("describes a slice saved before ranges existed by its outer bounds", () => {
    expect(describeSlice({ path: "a.ts", startLine: 5, endLine: 9, totalLines: 40, text: "", touchedStep: 1 })).toContain("lines 5-9 of 40");
  });

  it("honours an explicit range over search hits, clamped to the file", () => {
    const slice = sliceFile({ path: "big.ts", contents: FILE, capTokens: 600, step: 1, startLine: 590, endLine: 9999, hitLines: [20] });
    expect([slice.startLine, slice.endLine]).toEqual([590, 600]);
  });

  it("says so when not even one line fits", () => {
    const slice = sliceFile({ path: "min.js", contents: "x".repeat(20_000), capTokens: 50, step: 1 });
    expect(slice.startLine).toBe(0);
    expect(describeSlice(slice)).toContain("nothing fit");
  });
});
