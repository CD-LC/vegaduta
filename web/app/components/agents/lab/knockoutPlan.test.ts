// Prompt Knockout's run plan: the request a knockout sends (cell order, keys,
// which prompt variant each cell runs on) and the way a run's cells are read
// back into the score table. The two must agree, or a score would be
// credited to the wrong section.

import { describe, expect, it } from "vitest";
import { LAB_MAX_EXPECTED_CHARS, plannedCalls, type LabCellResult, type LabRun } from "./labApi";
import { FIXTURE_AGENT } from "./labFixtures";
import {
  baselineKey,
  buildKnockoutRequest,
  readKnockoutScores,
  sectionKey,
  type KnockoutContext,
  type KnockoutItem,
} from "./knockoutPlan";
import { promptWithout, splitPromptSections } from "./promptSections";

const PROMPT = FIXTURE_AGENT.systemPrompt as string;
const SECTIONS = splitPromptSections(PROMPT).sections; // s1 Role, s2 Tone, s3 Escalation

function contextOf(overrides: Partial<KnockoutContext> = {}): KnockoutContext {
  return {
    sections: SECTIONS,
    testedSectionIds: ["s1", "s3"],
    items: [
      { message: "Where is my order?", expected: "It shipped yesterday." },
      { message: "Can I get a refund?", expected: null },
    ],
    repeats: 2,
    prompt: PROMPT,
    ...overrides,
  };
}

function cellOf(key: string, extra: Partial<LabCellResult> = {}): LabCellResult {
  return {
    key, status: "DONE", answer: `answer to ${key}`, answerTruncated: false,
    score: 0.5, scoreStatus: "SCORED", scoreFeedback: null, errorMessage: null, ms: 100,
    ...extra,
  };
}

function runOf(cells: LabCellResult[]): LabRun {
  return {
    runId: "run-1", agentId: FIXTURE_AGENT.id, kind: "KNOCKOUT", status: "SUCCEEDED",
    plannedCalls: cells.length * 2, completedCalls: cells.length * 2, cancelRequested: false,
    errorMessage: null, createdAtMs: 1, updatedAtMs: 2, finishedAtMs: 2,
    cells, similarity: null,
  };
}

describe("buildKnockoutRequest", () => {
  it("is a judged knockout run with no similarity pass", () => {
    const request = buildKnockoutRequest(contextOf());
    expect(request).toMatchObject({ kind: "KNOCKOUT", scoring: "JUDGE", similarity: "NONE" });
  });

  it("sends the baseline prompt first, then one variant per tested section", () => {
    const request = buildKnockoutRequest(contextOf());
    expect(request.systemPrompts).toHaveLength(3);
    expect(request.systemPrompts[0]).toBe(promptWithout(SECTIONS, []));
    expect(request.systemPrompts[1]).toBe(promptWithout(SECTIONS, ["s1"]));
    expect(request.systemPrompts[2]).toBe(promptWithout(SECTIONS, ["s3"]));
    expect(request.systemPrompts[0]).toContain("# Role");
    expect(request.systemPrompts[1]).not.toContain("# Role");
    expect(request.systemPrompts[1]).toContain("# Tone");
    expect(request.systemPrompts[2]).not.toContain("# Escalation");
  });

  it("orders cells item by item: that item's baseline repeats, then its sections", () => {
    const request = buildKnockoutRequest(contextOf());
    expect(request.cells.map((cell) => cell.key)).toEqual([
      "b0:i0", "b1:i0", "s1:i0", "s3:i0",
      "b0:i1", "b1:i1", "s1:i1", "s3:i1",
    ]);
    expect(request.cells.map((cell) => cell.promptIndex)).toEqual([0, 0, 1, 2, 0, 0, 1, 2]);
    expect(request.cells.slice(0, 4).every((cell) => cell.message === "Where is my order?")).toBe(true);
    expect(request.cells.slice(4).every((cell) => cell.message === "Can I get a refund?")).toBe(true);
    expect(new Set(request.cells.map((cell) => cell.key)).size).toBe(request.cells.length);
    expect(baselineKey(1, 0)).toBe("b1:i0");
    expect(sectionKey("s3", 1)).toBe("s3:i1");
  });

  it("carries each item's expected answer on every one of its cells, clamped to the server limit", () => {
    const long = "e".repeat(LAB_MAX_EXPECTED_CHARS + 500);
    const request = buildKnockoutRequest(
      contextOf({ items: [{ message: "q1", expected: long }, { message: "q2", expected: null }, { message: "q3", expected: "" }] })
    );
    const first = request.cells.filter((cell) => cell.key.endsWith(":i0"));
    expect(first).toHaveLength(4);
    for (const cell of first) {
      expect(cell.expected).toHaveLength(LAB_MAX_EXPECTED_CHARS);
      expect((cell.expected as string).endsWith("…")).toBe(true);
    }
    expect(request.cells.filter((cell) => !cell.key.endsWith(":i0")).every((cell) => cell.expected === null)).toBe(true);
    expect(request.cells.every((cell) => cell.history === undefined)).toBe(true);
  });

  it("plans two model calls per cell", () => {
    // 2 items x (2 repeats + 2 sections) = 8 cells, each answered and judged.
    expect(plannedCalls(buildKnockoutRequest(contextOf()))).toBe(16);
    // 5 items x (3 repeats + 3 sections) = 30 cells.
    const five = contextOf({
      testedSectionIds: ["s1", "s2", "s3"],
      items: Array.from({ length: 5 }, (_, i): KnockoutItem => ({ message: `q${i}`, expected: null })),
      repeats: 3,
    });
    expect(buildKnockoutRequest(five).cells).toHaveLength(30);
    expect(plannedCalls(buildKnockoutRequest(five))).toBe(60);
  });

  it("with nothing to test, plans nothing for no items and only baselines for no sections", () => {
    expect(buildKnockoutRequest(contextOf({ items: [] })).cells).toEqual([]);
    const baselinesOnly = buildKnockoutRequest(contextOf({ testedSectionIds: [] }));
    expect(baselinesOnly.systemPrompts).toHaveLength(1);
    expect(baselinesOnly.cells.map((cell) => cell.key)).toEqual(["b0:i0", "b1:i0", "b0:i1", "b1:i1"]);
  });
});

describe("readKnockoutScores", () => {
  it("round-trips: every cell of the request lands in its own slot", () => {
    const ctx = contextOf();
    const request = buildKnockoutRequest(ctx);
    // Give each cell a distinct score derived from its position.
    const run = runOf(request.cells.map((cell, n) => cellOf(cell.key, { score: (n + 1) / 10 })));
    expect(readKnockoutScores(ctx, run)).toEqual({
      baseline: [
        [0.1, 0.2],
        [0.5, 0.6],
      ],
      sections: { s1: [0.3, 0.7], s3: [0.4, 0.8] },
    });
  });

  it("does not depend on the order the server returns cells in", () => {
    const ctx = contextOf();
    const cells = buildKnockoutRequest(ctx).cells.map((cell, n) => cellOf(cell.key, { score: (n + 1) / 10 }));
    const forward = readKnockoutScores(ctx, runOf(cells));
    expect(readKnockoutScores(ctx, runOf([...cells].reverse()))).toEqual(forward);
  });

  it("reads a missing, pending, failed or unscored cell as null", () => {
    const ctx = contextOf();
    const run = runOf([
      cellOf("b0:i0", { score: 0.9 }),
      cellOf("b1:i0", { status: "PENDING", answer: null, score: null, scoreStatus: "PENDING" }),
      cellOf("s1:i0", { status: "ERROR", answer: null, score: null, scoreStatus: "NONE", errorMessage: "The model call failed." }),
      cellOf("s3:i0", { score: null, scoreStatus: "UNAVAILABLE" }),
      // A score without the SCORED status is not trusted.
      cellOf("b0:i1", { score: 0.4, scoreStatus: "PENDING" }),
      cellOf("s1:i1", { score: 0.2 }),
      // b1:i1 and s3:i1 never came back at all.
    ]);
    expect(readKnockoutScores(ctx, run)).toEqual({
      baseline: [
        [0.9, null],
        [null, null],
      ],
      sections: { s1: [null, 0.2], s3: [null, null] },
    });
  });

  it("always returns a full table, even for a run with no cells", () => {
    const ctx = contextOf({ repeats: 3 });
    expect(readKnockoutScores(ctx, runOf([]))).toEqual({
      baseline: [
        [null, null, null],
        [null, null, null],
      ],
      sections: { s1: [null, null], s3: [null, null] },
    });
  });
});
