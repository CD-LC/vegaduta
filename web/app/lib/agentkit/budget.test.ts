// agentkit/budget + tokens — the arithmetic every other guarantee rests on.
//
// These pin NUMBERS on purpose. The budget is only useful if "fits" here means
// "fits" to the engine's own gate (lib/edge/tier1.ts computeTier1PromptBudget:
// window − 1024 reply reserve − ceil(window × 8%)), so the 4096 case is asserted
// against that formula's documented result rather than against this module's
// own constants — a test that re-derives its expectation from the code under
// test cannot catch the two drifting apart.

import { afterEach, describe, expect, it } from "vitest";

import {
  DEFAULT_RESPONSE_RESERVE_TOKENS,
  DEFAULT_SAFETY_MARGIN,
  MIN_VIABLE_PROMPT_BUDGET,
  PER_FILE_HARD_CAP_TOKENS,
  SLICE_SHARES,
  pinnedTurnsForWindow,
  planContextBudget,
} from "./budget";
import { CHARS_PER_TOKEN, estimateTokens, setTokenizer, truncateToTokens } from "./tokens";

describe("agentkit/tokens", () => {
  afterEach(() => setTokenizer(null));

  it("counts at the same 3.5 chars/token the tier-1 gate uses, rounding up", () => {
    expect(CHARS_PER_TOKEN).toBe(3.5);
    expect(estimateTokens("")).toBe(0);
    expect(estimateTokens("a")).toBe(1);
    expect(estimateTokens("a".repeat(7))).toBe(2);
    expect(estimateTokens("a".repeat(8))).toBe(3);
  });

  it("uses a plugged-in tokenizer, and falls back when it throws or returns nonsense", () => {
    setTokenizer((text) => text.split(/\s+/).length);
    expect(estimateTokens("one two three")).toBe(3);
    setTokenizer(() => {
      throw new Error("broken");
    });
    expect(estimateTokens("a".repeat(7))).toBe(2);
    setTokenizer(() => Number.NaN);
    expect(estimateTokens("a".repeat(7))).toBe(2);
  });

  it("truncates to a cap INCLUDING its own marker, head or tail", () => {
    const text = Array.from({ length: 400 }, (_, i) => `line ${i}`).join("\n");
    const head = truncateToTokens(text, 50, "head");
    expect(estimateTokens(head)).toBeLessThanOrEqual(50);
    expect(head.startsWith("line 0")).toBe(true);
    const tail = truncateToTokens(text, 50, "tail");
    expect(estimateTokens(tail)).toBeLessThanOrEqual(50);
    expect(tail.endsWith("line 399")).toBe(true);
    expect(truncateToTokens("short", 50)).toBe("short");
    expect(truncateToTokens(text, 0)).toBe("");
  });
});

describe("agentkit/budget", () => {
  it("mirrors the tier-1 gate on a 4096 window: 4096 − 1024 − ceil(327.68) = 2744", () => {
    expect(DEFAULT_RESPONSE_RESERVE_TOKENS).toBe(1024);
    expect(DEFAULT_SAFETY_MARGIN).toBe(0.08);
    const budget = planContextBudget({ contextWindow: 4096 });
    expect(budget.promptBudget).toBe(2744);
    expect(budget.viable).toBe(true);
  });

  it("never hands out more than the prompt budget across slices, and loses no tokens to rounding", () => {
    for (const window of [4096, 8192, 32768, 131072]) {
      const budget = planContextBudget({ contextWindow: window, overheadTokens: 64 });
      const total = Object.values(budget.slices).reduce((a, b) => a + b, 0);
      expect(total).toBe(budget.promptBudget);
    }
    expect(Object.values(SLICE_SHARES).reduce((a, b) => a + b, 0)).toBeCloseTo(1, 10);
  });

  it("lets the engine's own ceiling win when it is the smaller number, then subtracts transport overhead", () => {
    const budget = planContextBudget({ contextWindow: 8192, promptBudget: 3000, overheadTokens: 100 });
    expect(budget.promptBudget).toBe(2900);
    const roomy = planContextBudget({ contextWindow: 4096, promptBudget: 999_999 });
    expect(roomy.promptBudget).toBe(2744);
  });

  it("refuses a 2048-window model instead of thrashing in it", () => {
    const budget = planContextBudget({ contextWindow: 2048 });
    expect(budget.promptBudget).toBeLessThan(MIN_VIABLE_PROMPT_BUDGET);
    expect(budget.viable).toBe(false);
  });

  it("adapts to the active model: more pinned turns and bigger per-file excerpts as the window grows", () => {
    const small = planContextBudget({ contextWindow: 4096 });
    const mid = planContextBudget({ contextWindow: 8192 });
    const large = planContextBudget({ contextWindow: 131072 });
    expect(pinnedTurnsForWindow(4096)).toBe(2);
    expect(pinnedTurnsForWindow(8192)).toBe(4);
    expect(pinnedTurnsForWindow(65536)).toBe(6);
    expect(mid.perFileCapTokens).toBeGreaterThan(small.perFileCapTokens);
    // The hard per-file cap stops one file swallowing a huge window.
    expect(large.perFileCapTokens).toBe(PER_FILE_HARD_CAP_TOKENS);
    // No single file can take the whole working-set slice.
    expect(small.perFileCapTokens).toBeLessThan(small.slices.workingSet);
    // Pinned turns' outputs fit the scratchpad together.
    expect(small.perObservationCapTokens * small.pinnedTurns).toBeLessThanOrEqual(small.slices.scratchpad);
  });

  it("survives garbage input without producing a negative or NaN budget", () => {
    for (const bad of [0, -5, Number.NaN, Number.POSITIVE_INFINITY]) {
      const budget = planContextBudget({ contextWindow: bad as number });
      expect(budget.promptBudget).toBe(0);
      expect(budget.viable).toBe(false);
    }
  });
});
