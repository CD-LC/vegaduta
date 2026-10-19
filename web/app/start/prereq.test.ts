import { existsSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { GOALS } from "./goals";
import { MISSING_SETUP_HREF, UNKNOWN_SIGNALS, evaluateGoal, type WorkspaceSignals } from "./prereq";

function sig(over: Partial<WorkspaceSignals> = {}): WorkspaceSignals {
  return {
    loaded: true,
    plan: "PREMIUM",
    planFailed: false,
    canCreateAgents: true,
    tutorOn: true,
    deepResearch: false,
    mcpEnabled: [],
    e2bReady: true,
    ...over,
  };
}

describe("evaluateGoal: agent goals", () => {
  it("is ready when the template needs nothing", () => {
    const state = evaluateGoal("draft", sig());
    expect(state.kind).toBe("ready");
    expect(state.missing).toEqual([]);
    expect(state.action).toEqual({ type: "agent", templateKey: "client-call-to-actions" });
  });

  it("research needs web search: missing connector and no deep research is 'reduced', and says what is missing", () => {
    const state = evaluateGoal("research", sig());
    expect(state.kind).toBe("reduced");
    expect(state.missing).toEqual(["web-search"]);
    expect(state.action).toEqual({ type: "agent", templateKey: "research-assistant" });
  });

  it("research is ready once the connector is enabled", () => {
    expect(evaluateGoal("research", sig({ mcpEnabled: ["brave-search"] })).kind).toBe("ready");
  });

  it("research is ready when the deployment offers built-in deep research (the connector is relaxed to optional)", () => {
    const state = evaluateGoal("research", sig({ deepResearch: true }));
    expect(state.kind).toBe("ready");
    expect(state.missing).toEqual([]);
  });

  it("never calls something missing that it could not check", () => {
    // deep research unanswered: the connector need might be relaxed, so it is 'unchecked', not 'missing'
    const unknownResearch = evaluateGoal("research", sig({ deepResearch: null }));
    expect(unknownResearch.missing).toEqual([]);
    expect(unknownResearch.unchecked).toEqual(["web-search"]);
    expect(unknownResearch.kind).toBe("ready");
    // connector list unreadable (a non-admin 403)
    const unreadable = evaluateGoal("research", sig({ mcpEnabled: null }));
    expect(unreadable.missing).toEqual([]);
    expect(unreadable.unchecked).toEqual(["web-search"]);
    // credential status unreadable
    const e2bUnknown = evaluateGoal("analyze", sig({ e2bReady: null }));
    expect(e2bUnknown.missing).toEqual([]);
    expect(e2bUnknown.unchecked).toEqual(["code-execution"]);
  });

  it("analyze needs Code execution", () => {
    const missing = evaluateGoal("analyze", sig({ e2bReady: false }));
    expect(missing.kind).toBe("reduced");
    expect(missing.missing).toEqual(["code-execution"]);
    expect(evaluateGoal("analyze", sig({ e2bReady: true })).kind).toBe("ready");
  });

  it("is blocked, with the provider named, when no model can run an agent", () => {
    const state = evaluateGoal("draft", sig({ canCreateAgents: false }));
    expect(state.kind).toBe("blocked");
    expect(state.missing).toEqual(["provider"]);
  });

  it("keeps the paid gallery locked on FREE, and fails closed when the plan could not be read", () => {
    expect(evaluateGoal("research", sig({ plan: "FREE" })).kind).toBe("locked");
    expect(evaluateGoal("research", sig({ plan: null, planFailed: true })).kind).toBe("locked");
    // open templates stay available on FREE
    expect(evaluateGoal("draft", sig({ plan: "FREE" })).kind).toBe("ready");
    expect(evaluateGoal("analyze", sig({ plan: "FREE" })).kind).toBe("ready");
    // a paid plan unlocks the template
    expect(evaluateGoal("research", sig({ plan: "PREMIUM" })).kind).not.toBe("locked");
  });
});

describe("evaluateGoal: route goals", () => {
  it("opens the tutor when it is on", () => {
    const state = evaluateGoal("learn", sig({ tutorOn: true }));
    expect(state.kind).toBe("ready");
    expect(state.action).toEqual({ type: "route", href: "/learn/tutor" });
    expect(state.fellBack).toBe(false);
  });

  it("falls back to the one-conversation tutor agent only once the tutor is known to be off", () => {
    const off = evaluateGoal("learn", sig({ tutorOn: false }));
    expect(off.fellBack).toBe(true);
    expect(off.action).toEqual({ type: "agent", templateKey: "explain-it-simply-tutor" });
    expect(off.kind).toBe("ready");
    // unanswered probe: offer the route, the page explains itself
    const unknown = evaluateGoal("learn", sig({ tutorOn: null }));
    expect(unknown.action).toEqual({ type: "route", href: "/learn/tutor" });
  });

  it("the tutor fallback is not blocked by the paid-gallery lock", () => {
    expect(evaluateGoal("learn", sig({ tutorOn: false, plan: "FREE" })).kind).toBe("ready");
  });

  it("automate and build open their pages and never claim a prerequisite", () => {
    for (const id of ["automate", "build"] as const) {
      const state = evaluateGoal(id, sig({ canCreateAgents: false, plan: "FREE" }));
      expect(state.kind).toBe("ready");
      expect(state.missing).toEqual([]);
      expect(state.action.type).toBe("route");
    }
    expect(evaluateGoal("automate", sig()).action).toEqual({ type: "route", href: "/workflows" });
    expect(evaluateGoal("build", sig()).action).toEqual({ type: "route", href: "/build" });
  });
});

describe("before the workspace has answered", () => {
  it("reads 'checking' for goals that depend on a probe, and never 'ready' by guess", () => {
    expect(evaluateGoal("research", UNKNOWN_SIGNALS).kind).toBe("checking");
    expect(evaluateGoal("analyze", UNKNOWN_SIGNALS).kind).toBe("checking");
    expect(evaluateGoal("draft", UNKNOWN_SIGNALS).kind).toBe("checking");
    expect(evaluateGoal("learn", UNKNOWN_SIGNALS).kind).toBe("checking");
  });

  it("every goal evaluates to something sensible with no signals at all", () => {
    for (const goal of GOALS) {
      const state = evaluateGoal(goal, UNKNOWN_SIGNALS);
      expect(["checking", "ready"]).toContain(state.kind);
      expect(state.action.type).not.toBe("none");
    }
  });

  it("an unknown goal id is blocked with no action", () => {
    expect(evaluateGoal("nope", sig())).toMatchObject({ kind: "blocked", action: { type: "none" } });
  });
});

describe("setup links", () => {
  it("point at pages that exist", () => {
    const appDir = path.resolve(__dirname, "..");
    for (const [item, href] of Object.entries(MISSING_SETUP_HREF)) {
      const page = path.join(appDir, ...href.split("/").filter(Boolean), "page.tsx");
      expect(existsSync(page), `${item} -> ${href}`).toBe(true);
    }
  });
});
