import { describe, it, expect, vi } from "vitest";

// faqEval.ts statically imports routeTurn/resetEdgeSession from tierRouter,
// which pulls the whole tier0/1/2 chain (incl. WebLLM) — not jsdom-safe.
// Every test below injects `route`, so the mock's routeTurn is a dead stub.
vi.mock("./tierRouter", () => ({
  routeTurn: async () => ({ handled: false as const, reason: "edge-disabled" as const }),
  resetEdgeSession: () => {},
}));

import { runFaqEval, detectAbstention, type FaqEvalCase, type FaqRouteFn } from "./faqEval";
import type { RouteTurnResult, HostedFallthroughReason } from "./tierRouter";

const CASES: FaqEvalCase[] = [
  {
    id: "c1",
    persona: "p",
    category: "faq-traffic",
    systemPrompt: "sys",
    userMessage: "q1",
    rubric: "r1",
  },
  {
    id: "c2",
    persona: "p",
    category: "faq-traffic",
    systemPrompt: "sys",
    userMessage: "q2",
    rubric: "r2",
    feasibility: "UNDER_SPECIFIED",
  },
  {
    id: "c3",
    persona: "p",
    category: "faq-traffic",
    systemPrompt: "sys",
    userMessage: "q3",
    rubric: "r3",
  },
];

const handled = (text: string): RouteTurnResult => ({ handled: true, tier: 1, text });
const fallback = (reason: HostedFallthroughReason): RouteTurnResult => ({ handled: false, reason });

describe("detectAbstention", () => {
  it("flags hedged/abstaining answers", () => {
    expect(detectAbstention("I don't know, let me connect you to a human.")).toBe(true);
    expect(detectAbstention("That is not listed on our menu.")).toBe(true);
  });
  it("does not flag a direct answer", () => {
    expect(detectAbstention("The facial costs Rs.900.")).toBe(false);
  });
});

describe("runFaqEval", () => {
  it("records handled cases with tier, text, and latency", async () => {
    const route: FaqRouteFn = async () => handled("Rs.900");
    const report = await runFaqEval(CASES, { route });
    expect(report.status).toBe("completed");
    expect(report.results[0].outcome).toBe("handled");
    expect(report.results[0].tier).toBe(1);
    expect(report.results[0].text).toBe("Rs.900");
    expect(report.results[0].latencyMs).toBeGreaterThanOrEqual(0);
    expect(report.summary.handled).toBe(3);
  });

  it("records hosted fallthrough as fallback, never as an edge pass", async () => {
    const route: FaqRouteFn = async () => fallback("all-tiers-failed");
    const report = await runFaqEval(CASES, { route });
    expect(report.status).toBe("completed"); // edge was attempted and failed
    expect(report.summary.handled).toBe(0);
    expect(report.summary.fallback).toBe(3);
    expect(report.results[0].reason).toBe("all-tiers-failed");
  });

  it("reports edge-unavailable when every case falls through for availability reasons", async () => {
    const route: FaqRouteFn = async () => fallback("edge-disabled");
    const report = await runFaqEval(CASES, { route });
    expect(report.status).toBe("edge-unavailable");
    expect(report.summary.handled).toBe(0);
  });

  it("does not claim edge-unavailable when at least one case is handled", async () => {
    let n = 0;
    const route: FaqRouteFn = async () => (n++ === 0 ? handled("ok") : fallback("edge-disabled"));
    const report = await runFaqEval(CASES, { route });
    expect(report.status).toBe("completed");
  });

  it("records a thrown route error without aborting the suite", async () => {
    const route: FaqRouteFn = async (input) => {
      if (input === "q2") throw new Error("engine exploded");
      return handled("fine");
    };
    const report = await runFaqEval(CASES, { route });
    expect(report.results[1].outcome).toBe("error");
    expect(report.results[1].error).toBe("engine exploded");
    expect(report.summary.errors).toBe(1);
    expect(report.summary.handled).toBe(2);
  });

  it("detects abstention on UNDER_SPECIFIED cases and counts misses", async () => {
    const route: FaqRouteFn = async (input) =>
      input === "q2" ? handled("Yes, our rice is organic certified!") : handled("answer");
    const report = await runFaqEval(CASES, { route });
    const abstentionCase = report.results[1];
    expect(abstentionCase.abstentionExpected).toBe(true);
    expect(abstentionCase.heuristicAbstention).toBe(false);
    expect(report.summary.abstentionMissed).toBe(1);
  });

  it("gives each case its own session key", async () => {
    const keys: string[] = [];
    const route: FaqRouteFn = async (_input, options) => {
      keys.push(options.sessionKey ?? "");
      return handled("x");
    };
    await runFaqEval(CASES, { route });
    expect(keys).toEqual(["faq-eval:c1", "faq-eval:c2", "faq-eval:c3"]);
  });
});
