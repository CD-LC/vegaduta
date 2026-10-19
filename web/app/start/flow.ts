// The /start state machine: pure reducer + storage (de)serialisation. No React
// and no I/O in the reducer, so every transition is unit-tested without a DOM
// (flow.test.ts); the component only wires clicks to actions and persists.
//
// Three steps, matching the page's stepper:
//   goal   - "What do you want to do?"  (six goal cards)
//   create - the one-click step: create the agent / open the page
//   try    - the playground with a ready prompt; first completed turn = done
//
// Telemetry rides the same transitions. Each transition that is worth counting
// appends the event NAME to `fired`; the component flushes names it has not
// beaconed yet (events.ts flushOnboardingEvents). Keeping the dedupe in the
// state - and persisting it - is what stops a reload or a revisit from
// counting the same person's start twice, which would flatter or sink every
// rate computed from these counters.

import { findGoal, isGoalId, type GoalId } from "./goals";
import {
  ONBOARDING_STEPS,
  elapsedBucket,
  elapsedSeconds,
  firstRunBucketEvent,
  goalSelectedEvent,
  skippedEvent,
  type OnboardingStep,
} from "./events";

export const FLOW_STORAGE_KEY = "vd_start_flow_v1";

/** An unfinished flow older than this is dropped on load: it is no longer "resuming", and its elapsed time would be meaningless. */
export const FLOW_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export interface FlowState {
  readonly step: OnboardingStep;
  readonly goal: GoalId | null;
  /** The AGENT_TEMPLATES key resolved for the chosen goal; null for a route goal. */
  readonly templateKey: string | null;
  readonly agentId: string | null;
  /** Epoch ms of the first /start visit of this journey. */
  readonly startedAt: number | null;
  readonly prefilled: boolean;
  /** Epoch ms of the first completed turn with the created agent. */
  readonly firstRunAt: number | null;
  /** Seconds from startedAt to firstRunAt, kept so the celebration can say it after a reload. */
  readonly firstRunSeconds: number | null;
  /** Event names already counted for this browser - see the header. */
  readonly fired: readonly string[];
}

export function initialFlow(): FlowState {
  return {
    step: "goal",
    goal: null,
    templateKey: null,
    agentId: null,
    startedAt: null,
    prefilled: false,
    firstRunAt: null,
    firstRunSeconds: null,
    fired: [],
  };
}

export type FlowAction =
  | { type: "hydrate"; state: FlowState }
  | { type: "visit"; now: number }
  | { type: "select-goal"; goal: GoalId; templateKey: string | null }
  | { type: "back" }
  | { type: "agent-created"; agentId: string; templateKey: string | null }
  | { type: "agent-missing" }
  | { type: "prefilled" }
  | { type: "first-run"; now: number }
  | { type: "skip"; step: OnboardingStep }
  | { type: "restart"; now: number };

function fire(state: FlowState, ...events: string[]): readonly string[] {
  const next = [...state.fired];
  for (const event of events) if (!next.includes(event)) next.push(event);
  return next.length === state.fired.length ? state.fired : next;
}

export function isFirstRunDone(state: FlowState): boolean {
  return state.firstRunAt !== null;
}

export function flowReducer(state: FlowState, action: FlowAction): FlowState {
  switch (action.type) {
    case "hydrate":
      return action.state;

    case "visit": {
      // The clock starts at the FIRST visit of a journey and never restarts on
      // a revisit or reload - "time to first agent" includes the time away.
      const startedAt = state.startedAt ?? action.now;
      return { ...state, startedAt, fired: fire(state, "onboarding_start") };
    }

    case "select-goal": {
      const goal = findGoal(action.goal);
      if (!goal) return state;
      return {
        ...state,
        step: "create",
        goal: goal.id,
        templateKey: action.templateKey,
        agentId: null,
        prefilled: false,
        fired: fire(state, goalSelectedEvent(goal.id)),
      };
    }

    case "back":
      // Only the create step has a "back": once an agent exists, going "back"
      // would orphan it, so the try step offers "start over" instead.
      if (state.step !== "create") return state;
      return { ...state, step: "goal", goal: null, templateKey: null };

    case "agent-created":
      if (!action.agentId) return state;
      return {
        ...state,
        step: "try",
        agentId: action.agentId,
        templateKey: action.templateKey ?? state.templateKey,
        prefilled: false,
        fired: fire(state, "onboarding_agent_created"),
      };

    case "agent-missing":
      // A resumed flow whose agent was deleted meanwhile: back to the create
      // step for the same goal rather than a chat box wired to nothing.
      if (state.step !== "try") return state;
      return { ...state, step: state.goal ? "create" : "goal", agentId: null, prefilled: false };

    case "prefilled":
      if (state.step !== "try" || state.prefilled) return state;
      return { ...state, prefilled: true, fired: fire(state, "onboarding_prompt_prefilled") };

    case "first-run": {
      if (state.step !== "try" || state.firstRunAt !== null) return state;
      const seconds = elapsedSeconds(state.startedAt, action.now);
      const bucket = elapsedBucket(seconds);
      const events = ["onboarding_first_run_completed"];
      if (bucket) events.push(firstRunBucketEvent(bucket));
      return { ...state, firstRunAt: action.now, firstRunSeconds: seconds, fired: fire(state, ...events) };
    }

    case "skip":
      // Skipping never changes the step: the person leaves and can resume. It
      // only counts, once per step.
      return { ...state, fired: fire(state, skippedEvent(action.step)) };

    case "restart": {
      if (isFirstRunDone(state)) {
        // A second journey. `fired` is kept on purpose: start and first-run
        // are counted once per browser, so a person who replays the flow does
        // not count as a second "start", and a replayed first run is not
        // counted as a first run.
        return { ...initialFlow(), fired: state.fired, startedAt: action.now };
      }
      return { ...state, step: "goal", goal: null, templateKey: null, agentId: null, prefilled: false };
    }

    default:
      return state;
  }
}

/** True when there is something to resume: the person got past the goal step and has not finished. */
export function isResumable(state: FlowState): boolean {
  return state.step !== "goal" && state.goal !== null && !isFirstRunDone(state);
}

// ---------------------------------------------------------------- storage

function finiteOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/**
 * Parses what localStorage returned into a valid state. Anything malformed,
 * stale or inconsistent degrades to a fresh flow (or a repaired step) - the
 * stored text is untrusted input, because anything on the origin can write it.
 */
export function parseFlowState(raw: string | null | undefined, now: number): FlowState {
  if (!raw) return initialFlow();
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return initialFlow();
  }
  if (!data || typeof data !== "object") return initialFlow();
  const d = data as Record<string, unknown>;

  const startedAt = finiteOrNull(d.startedAt);
  const firstRunAt = finiteOrNull(d.firstRunAt);
  const fired = Array.isArray(d.fired)
    ? d.fired.filter((e): e is string => typeof e === "string" && e.length <= 64).slice(0, 64)
    : [];

  // Stale unfinished journey: start clean, but keep what was already counted.
  if (firstRunAt === null && startedAt !== null && now - startedAt > FLOW_TTL_MS) {
    return { ...initialFlow(), fired };
  }

  let step: OnboardingStep = ONBOARDING_STEPS.includes(d.step as OnboardingStep) ? (d.step as OnboardingStep) : "goal";
  const goal = isGoalId(d.goal) ? d.goal : null;
  const templateKey = typeof d.templateKey === "string" && d.templateKey.length <= 80 ? d.templateKey : null;
  const agentId = typeof d.agentId === "string" && d.agentId.length > 0 && d.agentId.length <= 80 ? d.agentId : null;

  // Repair inconsistent combinations rather than trusting them.
  if (step !== "goal" && !goal) step = "goal";
  if (step === "try" && !agentId) step = goal ? "create" : "goal";

  return {
    step,
    goal: step === "goal" ? null : goal,
    templateKey: step === "goal" ? null : templateKey,
    agentId: step === "try" ? agentId : null,
    startedAt,
    prefilled: step === "try" && d.prefilled === true,
    firstRunAt,
    firstRunSeconds: finiteOrNull(d.firstRunSeconds),
    fired,
  };
}

export function serializeFlowState(state: FlowState): string {
  return JSON.stringify(state);
}

type StorageLike = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function defaultStorage(): StorageLike | null {
  try {
    return typeof window !== "undefined" ? window.localStorage : null;
  } catch {
    return null;
  }
}

/** Never throws: private windows, blocked storage and quota errors all read as "nothing saved". */
export function loadFlow(now: number = Date.now(), storage: StorageLike | null = defaultStorage()): FlowState {
  if (!storage) return initialFlow();
  try {
    return parseFlowState(storage.getItem(FLOW_STORAGE_KEY), now);
  } catch {
    return initialFlow();
  }
}

/** Never throws; losing the write only costs resumability. */
export function saveFlow(state: FlowState, storage: StorageLike | null = defaultStorage()): void {
  if (!storage) return;
  try {
    storage.setItem(FLOW_STORAGE_KEY, serializeFlowState(state));
  } catch {
    /* storage unavailable or full - the flow still works, it just won't resume */
  }
}

// ---------------------------------------------------------------- dismissal

export const START_HERE_DISMISS_KEY = "vd_start_here_dismissed_v1";

export function isStartHereDismissed(storage: StorageLike | null = defaultStorage()): boolean {
  if (!storage) return false;
  try {
    return storage.getItem(START_HERE_DISMISS_KEY) === "1";
  } catch {
    return false;
  }
}

export function dismissStartHere(storage: StorageLike | null = defaultStorage()): void {
  if (!storage) return;
  try {
    storage.setItem(START_HERE_DISMISS_KEY, "1");
  } catch {
    /* the card will simply show again next visit */
  }
}
