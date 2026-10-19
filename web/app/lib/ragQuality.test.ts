import { describe, expect, it } from "vitest";
import {
  EVAL_PRESETS,
  MAX_EVAL_QUESTIONS,
  buildEvalRequest,
  formatCi,
  formatMs,
  formatPct,
  intervalsOverlap,
  parseEvalDataset,
  presetHintKey,
  presetLabelKey,
  verdictAgainstBaseline,
  type RagEvalConfigResult,
} from "./ragQuality";

const DOC = "123e4567-e89b-12d3-a456-426614174000";

function row(preset: string, low: number, high: number): RagEvalConfigResult {
  return {
    name: preset,
    preset,
    questions: 20,
    k: 5,
    hitRate: (low + high) / 2,
    hitRateCi: { low, high },
    recallAtK: 0.5,
    mrr: 0.5,
    avgReturnedChars: 800,
    avgLatencyMs: 120,
    failedQuestions: 0,
  };
}

describe("parseEvalDataset - line format", () => {
  it("parses a substring expectation", () => {
    const { questions, errors } = parseEvalDataset("What is the refund window? || 14 days");
    expect(errors).toEqual([]);
    expect(questions).toEqual([{ question: "What is the refund window?", expectedSubstrings: ["14 days"] }]);
  });

  it("separates several expectations and tells document ids from substrings", () => {
    const { questions } = parseEvalDataset(`Where do we ship? || ${DOC.toUpperCase()} ;; Canada ;; 5 to 7 days`);
    expect(questions[0].expectedDocumentIds).toEqual([DOC]);
    expect(questions[0].expectedSubstrings).toEqual(["Canada", "5 to 7 days"]);
  });

  it("skips blank lines and comments, and reports the line number of a bad entry", () => {
    const { questions, errors } = parseEvalDataset("# header\n\nGood question || answer\nNo expectation here\n || orphan");
    expect(questions).toHaveLength(1);
    expect(errors).toEqual([
      { line: 4, code: "missingExpected" },
      { line: 5, code: "emptyQuestion" },
    ]);
  });

  it("keeps a || inside the expected text", () => {
    const { questions } = parseEvalDataset("Q || a || b");
    expect(questions[0].expectedSubstrings).toEqual(["a || b"]);
  });

  it("returns nothing for an empty paste", () => {
    expect(parseEvalDataset("   ")).toEqual({ questions: [], errors: [] });
  });
});

describe("parseEvalDataset - JSON format", () => {
  it("parses an array and moves UUID-shaped substrings into document ids", () => {
    const text = JSON.stringify([
      { question: "q1", expectedSubstrings: ["alpha", DOC] },
      { question: "q2", expectedDocumentIds: [DOC] },
    ]);
    const { questions, errors } = parseEvalDataset(text);
    expect(errors).toEqual([]);
    expect(questions[0]).toEqual({ question: "q1", expectedDocumentIds: [DOC], expectedSubstrings: ["alpha"] });
    expect(questions[1]).toEqual({ question: "q2", expectedDocumentIds: [DOC] });
  });

  it("reports malformed JSON and entries without targets", () => {
    expect(parseEvalDataset("[ {").errors).toEqual([{ line: 0, code: "invalidJson" }]);
    expect(parseEvalDataset('[{"question":"q"}]').errors).toEqual([{ line: 1, code: "missingExpected" }]);
    expect(parseEvalDataset('[{"expectedSubstrings":["x"]}]').errors).toEqual([{ line: 1, code: "emptyQuestion" }]);
  });

  it("caps the dataset and says so", () => {
    const many = Array.from({ length: MAX_EVAL_QUESTIONS + 3 }, (_, i) => `q${i} || a${i}`).join("\n");
    const { questions, errors } = parseEvalDataset(many);
    expect(questions).toHaveLength(MAX_EVAL_QUESTIONS);
    expect(errors).toContainEqual({ line: 0, code: "tooMany" });
  });
});

describe("buildEvalRequest", () => {
  it("always includes the baseline, in canonical order, without duplicates", () => {
    const body = buildEvalRequest([{ question: "q", expectedSubstrings: ["a"] }], ["fusion+parent-child", "fusion", "fusion"], 5);
    expect(body.configs.map((c) => c.preset)).toEqual(["baseline", "fusion", "fusion+parent-child"]);
    expect(body.k).toBe(5);
  });

  it("drops unknown presets", () => {
    const body = buildEvalRequest([], ["nonsense"], 3);
    expect(body.configs.map((c) => c.preset)).toEqual(["baseline"]);
  });
});

describe("verdictAgainstBaseline", () => {
  const baseline = row("baseline", 0.5, 0.7);

  it("calls overlapping intervals noise, never a win", () => {
    expect(verdictAgainstBaseline(row("fusion", 0.6, 0.8), baseline)).toBe("noise");
  });

  it("calls a clearly higher interval better and a clearly lower one worse", () => {
    expect(verdictAgainstBaseline(row("fusion", 0.75, 0.95), baseline)).toBe("better");
    expect(verdictAgainstBaseline(row("fusion", 0.1, 0.4), baseline)).toBe("worse");
  });

  it("treats the baseline row (or a missing baseline) as the reference", () => {
    expect(verdictAgainstBaseline(baseline, baseline)).toBe("baseline");
    expect(verdictAgainstBaseline(row("fusion", 0.1, 0.2), undefined)).toBe("baseline");
  });

  it("intervalsOverlap includes touching bounds", () => {
    expect(intervalsOverlap({ low: 0.1, high: 0.5 }, { low: 0.5, high: 0.9 })).toBe(true);
    expect(intervalsOverlap({ low: 0.1, high: 0.4 }, { low: 0.5, high: 0.9 })).toBe(false);
  });
});

describe("formatting and preset keys", () => {
  it("formats percentages, intervals and latency", () => {
    expect(formatPct(0.834)).toBe("83%");
    expect(formatPct(Number.NaN)).toBe("-");
    expect(formatCi({ low: 0.617, high: 0.944 })).toBe("62-94%");
    expect(formatMs(120.4)).toBe("120 ms");
    expect(formatMs(2500)).toBe("2.5 s");
  });

  it("maps every preset to a label and hint key, and unknown ones to a fallback", () => {
    for (const p of EVAL_PRESETS) {
      expect(presetLabelKey(p)).not.toBe("presetUnknown");
      expect(presetHintKey(p)).not.toBe("presetUnknown");
    }
    expect(presetLabelKey("zzz")).toBe("presetUnknown");
  });
});
