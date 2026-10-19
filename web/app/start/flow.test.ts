import { describe, expect, it } from "vitest";
import {
  FLOW_STORAGE_KEY,
  FLOW_TTL_MS,
  START_HERE_DISMISS_KEY,
  dismissStartHere,
  flowReducer,
  initialFlow,
  isFirstRunDone,
  isResumable,
  isStartHereDismissed,
  loadFlow,
  parseFlowState,
  saveFlow,
  serializeFlowState,
  type FlowAction,
  type FlowState,
} from "./flow";

const T0 = 1_700_000_000_000;

function run(actions: FlowAction[], from: FlowState = initialFlow()): FlowState {
  return actions.reduce(flowReducer, from);
}

function memoryStorage(seed: Record<string, string> = {}) {
  const data = new Map(Object.entries(seed));
  return {
    getItem: (k: string) => (data.has(k) ? (data.get(k) as string) : null),
    setItem: (k: string, v: string): void => { data.set(k, v); },
    removeItem: (k: string): void => { data.delete(k); },
    data,
  };
}

describe("flowReducer: the happy path", () => {
  it("walks goal -> create -> try -> first run, counting each step once", () => {
    const state = run([
      { type: "visit", now: T0 },
      { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
      { type: "agent-created", agentId: "a1", templateKey: "client-call-to-actions" },
      { type: "prefilled" },
      { type: "first-run", now: T0 + 130_000 },
    ]);
    expect(state.step).toBe("try");
    expect(state.agentId).toBe("a1");
    expect(state.templateKey).toBe("client-call-to-actions");
    expect(state.startedAt).toBe(T0);
    expect(state.firstRunAt).toBe(T0 + 130_000);
    expect(state.firstRunSeconds).toBe(130);
    expect(isFirstRunDone(state)).toBe(true);
    expect(state.fired).toEqual([
      "onboarding_start",
      "onboarding_goal_selected_draft",
      "onboarding_agent_created",
      "onboarding_prompt_prefilled",
      "onboarding_first_run_completed",
      "onboarding_first_run_lt5m",
    ]);
  });

  it("puts a slow first run in the right bucket", () => {
    const state = run([
      { type: "visit", now: T0 },
      { type: "select-goal", goal: "analyze", templateKey: "data-analyst" },
      { type: "agent-created", agentId: "a2", templateKey: "data-analyst" },
      { type: "first-run", now: T0 + 12 * 60_000 },
    ]);
    expect(state.fired).toContain("onboarding_first_run_gte10m");
    expect(state.fired).not.toContain("onboarding_first_run_lt5m");
  });

  it("keeps the clock at the FIRST visit - a revisit never restarts it", () => {
    const state = run([
      { type: "visit", now: T0 },
      { type: "visit", now: T0 + 999_000 },
    ]);
    expect(state.startedAt).toBe(T0);
    expect(state.fired).toEqual(["onboarding_start"]);
  });

  it("counts a first run only once, even if a second turn completes", () => {
    const base = run([
      { type: "visit", now: T0 },
      { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
      { type: "agent-created", agentId: "a1", templateKey: "client-call-to-actions" },
      { type: "first-run", now: T0 + 20_000 },
    ]);
    const again = flowReducer(base, { type: "first-run", now: T0 + 90_000 });
    expect(again).toBe(base);
  });

  it("ignores a first run that arrives outside the try step", () => {
    const state = run([{ type: "visit", now: T0 }, { type: "first-run", now: T0 + 5_000 }]);
    expect(state.firstRunAt).toBeNull();
    expect(state.fired).toEqual(["onboarding_start"]);
  });
});

describe("flowReducer: choices, back, skip, restart", () => {
  it("selecting a route goal records the goal with no template", () => {
    const state = run([{ type: "select-goal", goal: "build", templateKey: null }]);
    expect(state.step).toBe("create");
    expect(state.goal).toBe("build");
    expect(state.templateKey).toBeNull();
    expect(state.fired).toEqual(["onboarding_goal_selected_build"]);
  });

  it("rejects an unknown goal id", () => {
    const state = flowReducer(initialFlow(), { type: "select-goal", goal: "nope" as never, templateKey: null });
    expect(state).toEqual(initialFlow());
  });

  it("back from create returns to the goal step; there is no back from try", () => {
    const create = run([{ type: "select-goal", goal: "research", templateKey: "research-assistant" }]);
    const back = flowReducer(create, { type: "back" });
    expect(back.step).toBe("goal");
    expect(back.goal).toBeNull();
    const tryStep = run([{ type: "agent-created", agentId: "a1", templateKey: "research-assistant" }], create);
    expect(flowReducer(tryStep, { type: "back" })).toBe(tryStep);
  });

  it("changing goals counts each distinct goal once", () => {
    const state = run([
      { type: "select-goal", goal: "research", templateKey: "research-assistant" },
      { type: "back" },
      { type: "select-goal", goal: "research", templateKey: "research-assistant" },
      { type: "back" },
      { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
    ]);
    expect(state.fired).toEqual(["onboarding_goal_selected_research", "onboarding_goal_selected_draft"]);
  });

  it("skip is counted per step and never moves the person (so it stays resumable)", () => {
    const create = run([{ type: "select-goal", goal: "analyze", templateKey: "data-analyst" }]);
    const skipped = flowReducer(create, { type: "skip", step: "create" });
    expect(skipped.step).toBe("create");
    expect(skipped.goal).toBe("analyze");
    expect(skipped.fired).toContain("onboarding_skipped_create");
    expect(flowReducer(skipped, { type: "skip", step: "create" }).fired).toEqual(skipped.fired);
    expect(isResumable(skipped)).toBe(true);
  });

  it("a deleted agent sends a resumed try step back to create for the same goal", () => {
    const tryStep = run([
      { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
      { type: "agent-created", agentId: "gone", templateKey: "client-call-to-actions" },
    ]);
    const repaired = flowReducer(tryStep, { type: "agent-missing" });
    expect(repaired.step).toBe("create");
    expect(repaired.agentId).toBeNull();
    expect(repaired.goal).toBe("draft");
  });

  it("restart before finishing keeps the clock and the counted events", () => {
    const state = run([
      { type: "visit", now: T0 },
      { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
      { type: "agent-created", agentId: "a1", templateKey: "client-call-to-actions" },
      { type: "restart", now: T0 + 50_000 },
    ]);
    expect(state.step).toBe("goal");
    expect(state.agentId).toBeNull();
    expect(state.startedAt).toBe(T0);
    expect(state.fired).toContain("onboarding_agent_created");
  });

  it("restart after a finished run begins a new journey but never recounts start or first run", () => {
    const done = run([
      { type: "visit", now: T0 },
      { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
      { type: "agent-created", agentId: "a1", templateKey: "client-call-to-actions" },
      { type: "first-run", now: T0 + 30_000 },
    ]);
    const again = flowReducer(done, { type: "restart", now: T0 + 500_000 });
    expect(again.step).toBe("goal");
    expect(again.firstRunAt).toBeNull();
    expect(again.startedAt).toBe(T0 + 500_000);
    expect(again.fired).toEqual(done.fired);
    // a second visit and second first run add nothing new
    const second = run(
      [
        { type: "visit", now: T0 + 500_000 },
        { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
        { type: "agent-created", agentId: "a2", templateKey: "client-call-to-actions" },
        { type: "first-run", now: T0 + 520_000 },
      ],
      again
    );
    expect(second.fired).toEqual(done.fired);
  });
});

describe("resumability", () => {
  it("is resumable only past the goal step and before a finished run", () => {
    expect(isResumable(initialFlow())).toBe(false);
    const create = run([{ type: "select-goal", goal: "build", templateKey: null }]);
    expect(isResumable(create)).toBe(true);
    const done = run(
      [
        { type: "visit", now: T0 },
        { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
        { type: "agent-created", agentId: "a1", templateKey: "client-call-to-actions" },
        { type: "first-run", now: T0 + 1_000 },
      ],
      initialFlow()
    );
    expect(isResumable(done)).toBe(false);
  });
});

describe("parseFlowState: stored text is untrusted", () => {
  const good = (): FlowState =>
    run([
      { type: "visit", now: T0 },
      { type: "select-goal", goal: "draft", templateKey: "client-call-to-actions" },
      { type: "agent-created", agentId: "a1", templateKey: "client-call-to-actions" },
      { type: "prefilled" },
    ]);

  it("round-trips a valid state", () => {
    const state = good();
    expect(parseFlowState(serializeFlowState(state), T0 + 1_000)).toEqual(state);
  });

  it("degrades malformed input to a fresh flow", () => {
    for (const raw of [null, undefined, "", "not json", "[]", "null", "42", '"x"']) {
      expect(parseFlowState(raw as string | null, T0)).toEqual(initialFlow());
    }
  });

  it("drops an unfinished journey older than the TTL but keeps what was already counted", () => {
    const stale = good();
    const parsed = parseFlowState(serializeFlowState(stale), T0 + FLOW_TTL_MS + 1);
    expect(parsed.step).toBe("goal");
    expect(parsed.agentId).toBeNull();
    expect(parsed.startedAt).toBeNull();
    expect(parsed.fired).toEqual(stale.fired);
  });

  it("keeps a FINISHED journey past the TTL (the celebration state is still true)", () => {
    const finished = run(
      [{ type: "first-run", now: T0 + 40_000 }],
      good()
    );
    const parsed = parseFlowState(serializeFlowState(finished), T0 + FLOW_TTL_MS * 3);
    expect(parsed.firstRunAt).toBe(T0 + 40_000);
    expect(parsed.step).toBe("try");
  });

  it("repairs inconsistent combinations instead of trusting them", () => {
    const tryNoAgent = JSON.stringify({ step: "try", goal: "draft", startedAt: T0, fired: [] });
    expect(parseFlowState(tryNoAgent, T0).step).toBe("create");
    const createNoGoal = JSON.stringify({ step: "create", goal: "bogus", startedAt: T0, fired: [] });
    const repaired = parseFlowState(createNoGoal, T0);
    expect(repaired.step).toBe("goal");
    expect(repaired.goal).toBeNull();
  });

  it("filters junk out of the fired list and caps its size", () => {
    const junk = JSON.stringify({
      step: "goal",
      fired: ["onboarding_start", 7, null, { a: 1 }, "x".repeat(200), ...Array.from({ length: 200 }, (_, i) => `e${i}`)],
    });
    const parsed = parseFlowState(junk, T0);
    expect(parsed.fired.every((e) => typeof e === "string" && e.length <= 64)).toBe(true);
    expect(parsed.fired.length).toBeLessThanOrEqual(64);
    expect(parsed.fired).toContain("onboarding_start");
  });
});

describe("storage access never throws", () => {
  it("loads and saves through a working storage", () => {
    const storage = memoryStorage();
    const state = run([{ type: "select-goal", goal: "build", templateKey: null }]);
    saveFlow(state, storage);
    expect(storage.data.has(FLOW_STORAGE_KEY)).toBe(true);
    expect(loadFlow(T0, storage).goal).toBe("build");
  });

  it("treats unavailable or throwing storage as 'nothing saved'", () => {
    const throwing = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("quota");
      },
      removeItem: () => {
        throw new Error("blocked");
      },
    };
    expect(loadFlow(T0, throwing)).toEqual(initialFlow());
    expect(() => saveFlow(initialFlow(), throwing)).not.toThrow();
    expect(loadFlow(T0, null)).toEqual(initialFlow());
    expect(() => saveFlow(initialFlow(), null)).not.toThrow();
    expect(isStartHereDismissed(throwing)).toBe(false);
    expect(() => dismissStartHere(throwing)).not.toThrow();
  });

  it("remembers the dashboard card dismissal", () => {
    const storage = memoryStorage();
    expect(isStartHereDismissed(storage)).toBe(false);
    dismissStartHere(storage);
    expect(storage.data.get(START_HERE_DISMISS_KEY)).toBe("1");
    expect(isStartHereDismissed(storage)).toBe(true);
  });
});
