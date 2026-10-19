import { describe, expect, it } from "vitest";
import sample from "./fixtures/brief.sample.json";
import { coachCards, bindingEntityId, MAX_CARDS } from "./coach";
import { describePatch } from "./briefDiff";
import { ledgerView, runVerdict, runViews, sparklinePoints, thumbsView } from "./ledger";
import { demoteView, eventViews, ladderSteps, proposalView, trustEventEvidence } from "./trust";
import type { Brief, BriefBinding, BriefLedger, LedgerRun, TrustProposal } from "./types";

const brief = sample as unknown as Brief;

const ledger = (over: Partial<BriefLedger> = {}): BriefLedger => ({
  state: "READY",
  baseline: { score: 0.7 },
  noiseFloor: 0.04,
  runs: [],
  thumbs: null,
  experiments: [],
  trustEvents: [],
  ...over,
});

const bindings: BriefBinding[] = [
  { ref: "a1", kind: "AGENT", entityId: "ag-1" },
  { ref: "a2", kind: "AGENT", entityId: "ag-2" },
];

describe("coachCards", () => {
  it("returns nothing for a missing ledger or any state without numbers", () => {
    expect(coachCards(brief, null)).toEqual([]);
    for (const state of ["LOCKED", "NOT_APPLIED", "NO_EVAL_SET"] as const) {
      expect(coachCards(brief, ledger({ state, baseline: null, noiseFloor: null }), bindings)).toEqual([]);
    }
  });

  it("orders weak dimensions weakest first and maps each to its control (breakdown, if core ever serves one)", () => {
    const cards = coachCards(
      brief,
      ledger({
        breakdown: [
          { agentRef: "a2", dimension: "length", score: 0.7 },
          { agentRef: "a1", dimension: "tone", score: 0.5 },
          { agentRef: "a1", dimension: "safety", score: 0.95 },
        ],
      }),
      bindings
    );
    expect(cards[0]).toMatchObject({ agentRef: "a1", target: "prompt", dimension: "tone", score: 0.5 });
    expect(cards[0].href).toBe("/agents?agentId=ag-1&tour=field:workbench-system-prompt");
    expect(cards[1]).toMatchObject({ agentRef: "a2", target: "maxTokens" });
    expect(cards[1].href).toContain("workbench-max-tokens");
    // the strong safety score produced no card of its own
    expect(cards.some((c) => c.dimension === "safety")).toBe(false);
  });

  it("points weak accuracy with nothing uploaded at the upload, not the workbench", () => {
    const [card] = coachCards(brief, ledger({ breakdown: [{ agentRef: "a1", dimension: "accuracy", score: 0.6 }] }), bindings);
    expect(card.knowledgeEmpty).toBe(true);
    expect(card.href).toBe("/knowledge?tour=field:knowledge-toggle");
  });

  it("has no link until the envoy is applied", () => {
    const [card] = coachCards(brief, ledger({ breakdown: [{ agentRef: "a1", dimension: "tone", score: 0.4 }] }));
    expect(card.href).toBeNull();
  });

  it("with no breakdown (what core serves today) gives only the planner's own notes", () => {
    const cards = coachCards(brief, ledger({ state: "COLLECTING" }), bindings);
    expect(cards.length).toBeGreaterThan(0);
    for (const c of cards) expect(c).toMatchObject({ dimension: null, score: null });
    expect(cards[0].hint).toBe(brief.narrative.perNode[cards[0].agentRef].tune);
  });

  it("never exceeds five and never invents a card without evidence", () => {
    const many = ledger({
      breakdown: ["tone", "accuracy", "length", "relevance", "helpfulness", "safety"].flatMap((d) =>
        ["a1", "a2"].map((a) => ({ agentRef: a, dimension: d as any, score: 0.3 }))
      ),
    });
    expect(coachCards(brief, many).length).toBeLessThanOrEqual(MAX_CARDS);
    const bare: Brief = { ...brief, narrative: { whyAgentic: "", perNode: {} } };
    expect(coachCards(bare, ledger())).toEqual([]);
  });

  it("reads bindings as core serves them (a list)", () => {
    expect(bindingEntityId([{ ref: "a1", kind: "AGENT", entityId: "x" }], "a1")).toBe("x");
    expect(bindingEntityId([{ ref: "a1", kind: "AGENT", entityId: "x" }], "a2")).toBeNull();
    expect(bindingEntityId(undefined, "a1")).toBeNull();
  });
});

describe("ledger significance", () => {
  const run = (over: Partial<LedgerRun>): LedgerRun => ({ version: 2, score: 0.8, noiseFloor: 0.04, deltaVsPrev: 0.08, significant: true, ...over });

  it("shows a change only when significant and outside the band", () => {
    expect(runVerdict(run({}), 1)).toBe("improved");
    expect(runVerdict(run({ deltaVsPrev: -0.09 }), 1)).toBe("regressed");
  });

  it("calls a non-significant delta no measurable change and hides the number", () => {
    const [v] = runViews([run({ significant: false, deltaVsPrev: 0.02 })]);
    expect(v.verdict).toBe("noChange");
    expect(v.delta).toBeNull();
    expect(v.band).toBe(0.04);
  });

  it("does not overclaim when core says significant but the delta sits inside the band, or no floor was measured", () => {
    expect(runVerdict(run({ significant: true, deltaVsPrev: 0.03 }), 1)).toBe("noChange");
    expect(runVerdict(run({ significant: true, noiseFloor: null }), 1)).toBe("noChange");
  });

  it("marks the first run and a run with nothing to compare to", () => {
    expect(runVerdict(run({ deltaVsPrev: null }), 0)).toBe("first");
    expect(runVerdict(run({ deltaVsPrev: null }), 1)).toBe("unmeasured");
  });

  it("a state without numbers yields the reason and no runs, even if numbers arrive", () => {
    const v = ledgerView(ledger({ state: "LOCKED", reason: " Needs the Premium plan. ", runs: [run({})] }));
    expect(v).toEqual({ kind: "locked", state: "LOCKED", reason: "Needs the Premium plan." });
    expect(ledgerView(ledger({ state: "NOT_APPLIED", reason: "Apply it first.", runs: [run({})] })).kind).toBe("locked");
    expect(ledgerView(ledger({ state: "NO_EVAL_SET", reason: "No cases.", runs: [] })).kind).toBe("locked");
  });

  it("is empty (with core's reason) until a run finished, then ready with the baseline score", () => {
    expect(ledgerView(ledger({ state: "COLLECTING", reason: "Waiting for the first run.", runs: [] }))).toEqual({ kind: "empty", reason: "Waiting for the first run." });
    expect(ledgerView(ledger({ runs: [run({})] }))).toMatchObject({ kind: "ready", baseline: 0.7 });
  });

  it("quotes a thumbs ratio only over enough ratings", () => {
    expect(thumbsView(null).kind).toBe("none");
    expect(thumbsView({ up: 0, down: 0, rated: 0, ratio: null }).kind).toBe("none");
    expect(thumbsView({ up: 5, down: 1, rated: 6, ratio: 5 / 6 })).toEqual({ kind: "few", total: 6 });
    expect(thumbsView({ up: 8, down: 2, rated: 10, ratio: 0.8 })).toMatchObject({ kind: "ratio", ratio: 0.8 });
  });

  it("keeps sparkline points inside the box and skips unscored runs", () => {
    const { coords, points } = sparklinePoints([0.7, null, 0.8, 0.9], 100, 20);
    expect(coords.length).toBe(3);
    for (const c of coords) {
      expect(c.x).toBeGreaterThanOrEqual(0);
      expect(c.x).toBeLessThanOrEqual(100);
      expect(c.y).toBeGreaterThanOrEqual(0);
      expect(c.y).toBeLessThanOrEqual(20);
    }
    expect(points.split(" ").length).toBe(3);
    expect(sparklinePoints([null], 100, 20).points).toBe("");
  });
});

describe("trust view-model", () => {
  const proposal = (over: Partial<TrustProposal> = {}): TrustProposal => ({
    eligible: true,
    currentRung: "UNDER_WATCH",
    targetRung: "TRUSTED",
    evidence: [
      { key: "a", label: "a", met: true },
      { key: "b", label: "b", met: true },
    ],
    diff: [{ op: "replace", path: "/trust/rung", value: "TRUSTED" }],
    effects: ["Agents move to the middle autonomy tier."],
    ...over,
  });

  it("is ready only when eligible, every line holds and the target is the next rung", () => {
    expect(proposalView(proposal(), "UNDER_WATCH", true)).toMatchObject({ state: "ready", gate: null, metCount: 2, effects: ["Agents move to the middle autonomy tier."] });
  });

  it("stays pending when any evidence line is false, even if core said eligible", () => {
    const v = proposalView(proposal({ evidence: [{ key: "a", label: "a", met: true }, { key: "b", label: "b", met: false }] }), "UNDER_WATCH", true);
    expect(v).toMatchObject({ state: "pending", gate: "notEligible", metCount: 1 });
  });

  it("does not offer a jump of two rungs", () => {
    expect(proposalView(proposal({ targetRung: "AUTONOMOUS" }), "UNDER_WATCH", true).gate).toBe("wrongTarget");
  });

  it("explains rather than hides when a non-admin could otherwise promote", () => {
    expect(proposalView(proposal(), "UNDER_WATCH", false)).toMatchObject({ state: "ready", gate: "needsAdmin" });
  });

  it("has no proposal state when core names no target, and keeps its evidence lines", () => {
    const lines = [{ key: "plan", label: "Your plan runs evaluations", met: false, detail: "Not on this plan." }];
    expect(proposalView({ eligible: false, targetRung: null, evidence: lines, diff: [], effects: [] }, "UNDER_WATCH", true)).toMatchObject({ state: "none", evidence: lines });
    expect(proposalView(null, "TRUSTED", true).state).toBe("none");
  });

  it("demotion is always one rung down, gated only by role and the bottom rung", () => {
    expect(demoteView("AUTONOMOUS", true)).toEqual({ target: "TRUSTED", gate: null });
    expect(demoteView("TRUSTED", false)).toEqual({ target: "UNDER_WATCH", gate: "needsAdmin" });
    expect(demoteView("UNDER_WATCH", true)).toEqual({ target: null, gate: "atBottom" });
  });

  it("fills the ladder up to the current rung", () => {
    expect(ladderSteps("TRUSTED").map((s) => s.state)).toEqual(["past", "current", "future"]);
  });

  it("reads core's evidence object ({lines, note}) and ignores malformed input", () => {
    expect(trustEventEvidence({ lines: [{ key: "k", label: "L", met: true, detail: "d" }, { nope: 1 }], note: " n " })).toEqual({
      lines: [{ key: "k", label: "L", met: true, detail: "d" }],
      note: "n",
    });
    expect(trustEventEvidence(null)).toEqual({ lines: [], note: null });
    expect(trustEventEvidence({ lines: "x" })).toEqual({ lines: [], note: null });
  });

  it("lists events newest first and flags automatic (system) demotions with core's trigger sentence", () => {
    const evs = eventViews([
      { kind: "PROMOTED", fromRung: "UNDER_WATCH", toRung: "TRUSTED", evidence: { lines: [] }, at: "2026-09-20T00:00:00Z", actor: "maintainer", version: 2 },
      {
        kind: "DEMOTED",
        fromRung: "TRUSTED",
        toRung: "UNDER_WATCH",
        evidence: { lines: [{ key: "trigger", label: "Automatic demotion", met: false, detail: " Guardrail strike. " }] },
        at: "2026-09-25T00:00:00Z",
        actor: "system",
      },
    ]);
    expect(evs.map((e) => e.kind)).toEqual(["DEMOTED", "PROMOTED"]);
    expect(evs[0]).toMatchObject({ auto: true, reason: "Guardrail strike." });
    expect(evs[1].auto).toBe(false);
    expect(new Set(evs.map((e) => e.id)).size).toBe(2);
  });
});

describe("describePatch", () => {
  it("names the agent and the field for a replaced value", () => {
    const [l] = describePatch([{ op: "replace", path: "/agents/0/reasoningDepth", value: "RIGOROUS" }], brief);
    expect(l).toMatchObject({ op: "changed", scope: "agent", subject: "Collections follow-up", field: "reasoningDepth", from: "CAREFUL", to: "RIGOROUS" });
  });

  it("reads nested generation settings", () => {
    const [l] = describePatch([{ op: "replace", path: "/agents/1/generation/maxTokens", value: 600 }], brief);
    expect(l).toMatchObject({ scope: "agent", subject: "Order-status desk", field: "maxTokens", from: "300", to: "600" });
  });

  it("describes list additions and removals as items", () => {
    const [add, del] = describePatch(
      [
        { op: "add", path: "/agents/0/toolAllowList/-", value: "t1" },
        { op: "remove", path: "/agents/1/toolAllowList/0" },
      ],
      brief
    );
    expect(add).toMatchObject({ op: "added", field: "toolAllowList", to: "t1" });
    expect(del).toMatchObject({ op: "removed", field: "toolAllowList", to: "mcp:m1/*" });
  });

  it("keeps the full text of a long prompt change", () => {
    const [l] = describePatch([{ op: "replace", path: "/agents/0/systemPrompt", value: "x".repeat(200) }], brief);
    expect(l.field).toBe("systemPrompt");
    expect(l.to!.length).toBeLessThan(200);
    expect(l.full?.to.length).toBe(200);
  });

  it("names a workflow step and falls back to the raw path for unknown fields", () => {
    const [step, odd] = describePatch(
      [
        { op: "replace", path: "/workflows/0/graph/nodes/0/data/label", value: "Renamed" },
        { op: "replace", path: "/mystery/field", value: 1 },
      ],
      brief
    );
    expect(step).toMatchObject({ scope: "step", field: "label", to: "Renamed" });
    expect(odd).toMatchObject({ field: null, path: "/mystery/field" });
  });
});
