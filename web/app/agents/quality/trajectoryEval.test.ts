// Trajectory evals - the pure half: the steps-text parser the expected-trajectory editor uses, the form/spec
// round trip, and the shaping of the stored report for the expected-vs-actual diff.

import { describe, expect, it } from "vitest";
import {
  buildSpec,
  EMPTY_FORM,
  formFromSpec,
  isFormDirty,
  metricEntries,
  normalizeReport,
  normalizeTrajectoryConfig,
  parseStepsText,
  pctText,
  statusTone,
  stepsTextFromActual,
  stepsToText,
  type EditorForm,
} from "./trajectoryEval";

describe("normalizeTrajectoryConfig", () => {
  it("reads a config body", () => {
    expect(
      normalizeTrajectoryConfig({ enabled: true, modes: ["exact", "f1"], maxSteps: 40, maxArgsPerStep: 10, maxActualSteps: 100 })
    ).toEqual({ enabled: true, modes: ["exact", "f1"], maxSteps: 40, maxArgsPerStep: 10, maxActualSteps: 100 });
  });

  it("falls back to defaults for missing numbers and an empty modes list", () => {
    const c = normalizeTrajectoryConfig({ enabled: false, modes: [] });
    expect(c).not.toBeNull();
    expect(c!.enabled).toBe(false);
    expect(c!.modes).toEqual(["exact", "in_order", "any_order", "f1"]);
    expect(c!.maxSteps).toBe(50);
  });

  it("returns null for anything that is not a config (an absent enabled flag is not 'on')", () => {
    expect(normalizeTrajectoryConfig(null)).toBeNull();
    expect(normalizeTrajectoryConfig("x")).toBeNull();
    expect(normalizeTrajectoryConfig({ modes: [] })).toBeNull();
  });
});

describe("parseStepsText", () => {
  it("parses one step per line, with optional argument matchers", () => {
    const r = parseStepsText(
      'search_knowledge_base {"query":{"regex":"refund"},"limit":5}\n\n# a comment\nsend_email\n  mcp__crm__lookup   {"opts.retries":3}  ',
      50,
      20
    );
    expect(r.error).toBeNull();
    expect(r.steps).toEqual([
      { tool: "search_knowledge_base", args: { query: { regex: "refund" }, limit: 5 } },
      { tool: "send_email" },
      { tool: "mcp__crm__lookup", args: { "opts.retries": 3 } },
    ]);
  });

  it("treats an empty argument object as no matchers and an empty text as an empty trajectory", () => {
    expect(parseStepsText("a {}", 50, 20).steps).toEqual([{ tool: "a" }]);
    expect(parseStepsText("", 50, 20)).toEqual({ steps: [], error: null, line: 0 });
    expect(parseStepsText("\n \n", 50, 20).steps).toEqual([]);
  });

  it("handles CRLF line endings", () => {
    expect(parseStepsText("a\r\nb\r\n", 50, 20).steps.map((s) => s.tool)).toEqual(["a", "b"]);
  });

  it("reports the line and kind of each error", () => {
    expect(parseStepsText("ok\nbad! name", 50, 20)).toMatchObject({ error: "toolName", line: 2 });
    expect(parseStepsText("a {not json}", 50, 20)).toMatchObject({ error: "argsJson", line: 1 });
    expect(parseStepsText("a [1]", 50, 20)).toMatchObject({ error: "argsObject", line: 1 });
    expect(parseStepsText("a 5", 50, 20)).toMatchObject({ error: "argsObject", line: 1 });
    expect(parseStepsText('a {"bad key!":1}', 50, 20)).toMatchObject({ error: "argKey", line: 1 });
    expect(parseStepsText('a {"x..y":1}', 50, 20)).toMatchObject({ error: "argKey", line: 1 });
    expect(parseStepsText('a {"p":1,"q":2}', 50, 1)).toMatchObject({ error: "tooManyArgs", line: 1 });
    expect(parseStepsText("a\nb\nc", 2, 20)).toMatchObject({ error: "tooManySteps", line: 3 });
  });

  it("round-trips through stepsToText", () => {
    const steps = [{ tool: "a", args: { q: { regex: "x" } } }, { tool: "b" }];
    expect(parseStepsText(stepsToText(steps), 50, 20).steps).toEqual(steps);
    expect(stepsToText([])).toBe("");
  });
});

describe("form <-> spec", () => {
  const form = (over: Partial<EditorForm> = {}): EditorForm => ({ ...EMPTY_FORM, stepsText: "a\nb", ...over });

  it("builds the spec the PUT sends and omits blank optionals", () => {
    const r = buildSpec(form(), { maxSteps: 50, maxArgsPerStep: 20 });
    expect(r.error).toBeNull();
    expect(r.spec).toEqual({ type: "trajectory", mode: "in_order", minScore: 1, steps: [{ tool: "a" }, { tool: "b" }] });
  });

  it("includes the optional caps when given", () => {
    const r = buildSpec(form({ mode: "any_order", minScore: "0.5", maxExtraCalls: "2", minEfficiency: "0.25" }));
    expect(r.spec).toEqual({
      type: "trajectory",
      mode: "any_order",
      minScore: 0.5,
      maxExtraCalls: 2,
      minEfficiency: 0.25,
      steps: [{ tool: "a" }, { tool: "b" }],
    });
  });

  it("allows an empty trajectory (the agent should call no tools)", () => {
    const r = buildSpec(form({ stepsText: "", mode: "exact" }));
    expect(r.error).toBeNull();
    expect(r.spec!.steps).toEqual([]);
  });

  it("rejects bad numbers with the field they belong to", () => {
    expect(buildSpec(form({ minScore: "" })).error).toBe("minScore");
    expect(buildSpec(form({ minScore: "1.5" })).error).toBe("minScore");
    expect(buildSpec(form({ minScore: "-1" })).error).toBe("minScore");
    expect(buildSpec(form({ minScore: "abc" })).error).toBe("minScore");
    expect(buildSpec(form({ maxExtraCalls: "1.5" })).error).toBe("maxExtraCalls");
    expect(buildSpec(form({ maxExtraCalls: "-1" })).error).toBe("maxExtraCalls");
    expect(buildSpec(form({ maxExtraCalls: "1001" })).error).toBe("maxExtraCalls");
    expect(buildSpec(form({ minEfficiency: "2" })).error).toBe("minEfficiency");
  });

  it("surfaces a steps error with its line", () => {
    const r = buildSpec(form({ stepsText: "a\nbad! name" }));
    expect(r.spec).toBeNull();
    expect(r.error).toBe("toolName");
    expect(r.line).toBe(2);
  });

  it("fills the form from a stored spec and from nothing", () => {
    expect(formFromSpec(null)).toEqual(EMPTY_FORM);
    expect(formFromSpec("nonsense")).toEqual(EMPTY_FORM);
    const f = formFromSpec({
      type: "trajectory",
      mode: "f1",
      minScore: 0.8,
      maxExtraCalls: 3,
      minEfficiency: 0.5,
      steps: [{ tool: "a", args: { q: 1 } }, { tool: "b" }, { notATool: true }, 7],
    });
    expect(f).toEqual({ mode: "f1", minScore: "0.8", maxExtraCalls: "3", minEfficiency: "0.5", stepsText: 'a {"q":1}\nb' });
    // An unknown mode falls back rather than leaving the select blank.
    expect(formFromSpec({ mode: "sideways", steps: [] }).mode).toBe("in_order");
  });

  it("knows when the form differs from what is stored", () => {
    const stored = { type: "trajectory", mode: "in_order", minScore: 1, steps: [{ tool: "a" }] };
    const same = formFromSpec(stored);
    expect(isFormDirty(same, stored)).toBe(false);
    expect(isFormDirty({ ...same, stepsText: same.stepsText + "\n" }, stored)).toBe(false);
    expect(isFormDirty({ ...same, mode: "exact" }, stored)).toBe(true);
    expect(isFormDirty({ ...same, stepsText: "a\nb" }, stored)).toBe(true);
    expect(isFormDirty({ ...EMPTY_FORM, stepsText: "a" }, null)).toBe(true);
    expect(isFormDirty(EMPTY_FORM, null)).toBe(false);
  });

  it("suggests steps from a run's actual tool names", () => {
    expect(
      stepsTextFromActual([
        { tool: "search", args: { q: "x" }, ok: true },
        { tool: "bad name!", args: null, ok: true },
        { tool: "send", args: {}, ok: false },
      ])
    ).toBe("search\nsend");
    expect(stepsTextFromActual([], 5)).toBe("");
    expect(stepsTextFromActual([{ tool: "a", args: null, ok: true }, { tool: "b", args: null, ok: true }], 1)).toBe("a");
  });
});

describe("normalizeReport", () => {
  const raw = {
    mode: "in_order",
    score: 0.5,
    minScore: 1,
    passed: false,
    metrics: {
      exactMatch: false,
      inOrderMatch: false,
      anyOrderMatch: true,
      toolPrecision: 1,
      toolRecall: 0.5,
      toolF1: 0.6666,
      argMatchRate: null as number | null,
      extraCalls: 1,
      extraCallPenalty: 0.25,
      efficiency: 0.75,
      failedCalls: 0,
      expectedSteps: 2,
      actualSteps: 3,
    },
    expected: [
      { index: 0, tool: "a", status: "match", actualIndex: 0, detail: null as string | null },
      { index: 1, tool: "b", status: "missing", actualIndex: null, detail: null as string | null },
    ],
    actual: [
      { index: 0, tool: "a", ok: true, status: "match", expectedIndex: 0, args: '{"q":1}' },
      { index: 1, tool: "x", ok: false, status: "extra", expectedIndex: null, args: null },
    ],
    actualTruncated: false,
  };

  it("reads a stored report", () => {
    const r = normalizeReport(raw);
    expect(r).not.toBeNull();
    expect(r!.passed).toBe(false);
    expect(r!.metrics.argMatchRate).toBeNull();
    expect(r!.expected[1]).toEqual({ index: 1, tool: "b", status: "missing", actualIndex: null, detail: null as string | null });
    expect(r!.actual[1].ok).toBe(false);
    expect(r!.actual[0].args).toBe('{"q":1}');
  });

  it("returns null for anything that is not a report", () => {
    expect(normalizeReport(null)).toBeNull();
    expect(normalizeReport({})).toBeNull();
    expect(normalizeReport({ metrics: {}, expected: "no", actual: [] })).toBeNull();
  });

  it("skips malformed rows instead of throwing", () => {
    const r = normalizeReport({ ...raw, expected: [null, { tool: 7 }, raw.expected[0]], actual: [] });
    expect(r!.expected).toHaveLength(1);
    expect(r!.actual).toEqual([]);
  });

  it("orders the metrics for display and tags each with how to format it", () => {
    const entries = metricEntries(normalizeReport(raw)!.metrics);
    expect(entries.map((e) => e.key)).toEqual([
      "exactMatch",
      "inOrderMatch",
      "anyOrderMatch",
      "toolPrecision",
      "toolRecall",
      "toolF1",
      "argMatchRate",
      "extraCalls",
      "extraCallPenalty",
      "efficiency",
      "failedCalls",
    ]);
    expect(entries.find((e) => e.key === "anyOrderMatch")).toEqual({ key: "anyOrderMatch", kind: "bool", value: true });
    expect(entries.find((e) => e.key === "extraCalls")).toEqual({ key: "extraCalls", kind: "count", value: 1 });
    expect(entries.find((e) => e.key === "argMatchRate")!.value).toBeNull();
  });
});

describe("statusTone / pctText", () => {
  it("highlights matched, imperfect, missing and extra steps differently", () => {
    expect(statusTone("match").tone).toBe("success");
    expect(statusTone("out_of_order").tone).toBe("warning");
    expect(statusTone("args_mismatch").tone).toBe("warning");
    expect(statusTone("missing").tone).toBe("error");
    expect(statusTone("extra").tone).toBe("idle");
    expect(statusTone("something-new").tone).toBe("idle");
    // Colours come from the design tokens, never a literal.
    for (const s of ["match", "out_of_order", "missing", "extra"]) {
      expect(statusTone(s).cssVar).toMatch(/^var\(--state-[a-z]+\)$/);
    }
  });

  it("formats percentages and absence", () => {
    expect(pctText(1)).toBe("100%");
    expect(pctText(0.6666)).toBe("67%");
    expect(pctText(0)).toBe("0%");
    expect(pctText(null)).toBe("—");
    expect(pctText(undefined)).toBe("—");
    expect(pctText(Number.NaN)).toBe("—");
  });
});
