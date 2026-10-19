// agentkit — the context budget: how one model's window is divided.
//
// A multi-step agent on a small on-device model lives or dies on this file. The
// tier-1 catalog's models ship a 4096-token window (lib/edge/tier1.ts —
// WEBLLM_FALLBACK_CONTEXT_WINDOW and every fallback-manifest entry; an admin
// can raise it per model via the manifest's contextWindowSize). Of those 4096,
// the engine holds back 1024 for the reply and 8% for estimator error, leaving
// about 2.7k tokens for EVERYTHING the agent wants the model to know: its
// instructions, the tool list, the task, the plan, what it has read and what
// happened so far. A loop that just appends to a transcript is over that by
// its third file read, and the engine then refuses the step.
//
// So the window is split into named slices, up front, and each part of the
// prompt is held to its slice (context.ts does the holding). The split adapts
// to the ACTIVE model: the same code that gives a 4k model ~50 lines per file
// gives a 32k model several hundred, with nothing to configure.
//
// Pure arithmetic, no imports from lib/edge — the adapter (edgeModel.ts) reads
// the real window from the engine and passes it in.

/** Mirrors lib/edge/tier1.ts TIER1_OUTPUT_RESERVE_TOKENS / _SAFETY_MARGIN. They
 *  are restated rather than imported so this module stays pure (tier1.ts pulls
 *  in the whole engine); budget.test.ts pins the two against each other's
 *  documented values, and the adapter passes tier 1's OWN computed budget as
 *  `promptBudget`, which wins over this arithmetic whenever it is smaller. */
export const DEFAULT_RESPONSE_RESERVE_TOKENS = 1024;
export const DEFAULT_SAFETY_MARGIN = 0.08;

/** Below this many prompt tokens a plan → act → observe loop cannot hold its
 *  own instructions, a plan and one file excerpt at once. The run refuses to
 *  start and says so, instead of thrashing. A chosen floor, not a measurement:
 *  it is roughly the fixed cost of system + tools + task + plan with a few
 *  hundred tokens left for a file, and a 2048-window model lands under it. */
export const MIN_VIABLE_PROMPT_BUDGET = 1500;

/** Share of the prompt budget each slice may use. Sums to 1. The working set
 *  (file excerpts) gets the most because reading code is what the agent is
 *  for; the scratchpad (recent steps and their tool output) is next. */
export const SLICE_SHARES = {
  system: 0.12,
  tools: 0.1,
  task: 0.08,
  plan: 0.08,
  summary: 0.08,
  workingSet: 0.32,
  scratchpad: 0.22,
} as const;

export type SliceName = keyof typeof SLICE_SHARES;

/** No single file may take more than this share of the working-set slice, so
 *  one big file can never evict every other excerpt. */
const PER_FILE_SHARE_OF_WORKING_SET = 0.6;
/** Absolute per-file ceiling on large-window models. A chosen limit: past a
 *  few thousand tokens of one file, a targeted re-read of the right range beats
 *  carrying the whole thing through every step. */
export const PER_FILE_HARD_CAP_TOKENS = 3000;

export interface BudgetInput {
  contextWindow: number;
  /** The engine's own prompt ceiling, when the adapter knows it. */
  promptBudget?: number;
  responseReserve?: number;
  safetyMargin?: number;
  /** Tokens the transport adds that this module cannot see (the edge session
   *  appends a short reasoning nudge to every system prompt). */
  overheadTokens?: number;
}

export interface ContextBudget {
  contextWindow: number;
  responseReserve: number;
  safetyTokens: number;
  overheadTokens: number;
  /** What context.ts may spend, in total. */
  promptBudget: number;
  slices: Record<SliceName, number>;
  /** The newest N turns are never evicted or summarised. */
  pinnedTurns: number;
  perFileCapTokens: number;
  /** Cap on ONE tool observation at the moment it is recorded — sized so the
   *  pinned turns always fit the scratchpad slice together. */
  perObservationCapTokens: number;
  /** False = refuse to start (see MIN_VIABLE_PROMPT_BUDGET). */
  viable: boolean;
}

function positiveInt(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.trunc(value) : fallback;
}

/** How many recent turns stay verbatim. Small windows keep two — enough for
 *  "what I just did and what it said" — because every pinned turn is scratchpad
 *  the working set does not get. */
export function pinnedTurnsForWindow(contextWindow: number): number {
  if (contextWindow < 8192) return 2;
  if (contextWindow < 32768) return 4;
  return 6;
}

export function planContextBudget(input: BudgetInput): ContextBudget {
  const contextWindow = positiveInt(input.contextWindow, 0);
  // A window under 4096 cannot afford a 1024-token reply reserve; a quarter of
  // it is the most that leaves anything to prompt with. (The tier-1 gate still
  // applies its own fixed reserve — the adapter's promptBudget reflects that,
  // and wins below.)
  const defaultReserve =
    contextWindow >= 4096 ? DEFAULT_RESPONSE_RESERVE_TOKENS : Math.floor(contextWindow / 4);
  const responseReserve = Math.min(positiveInt(input.responseReserve, defaultReserve), contextWindow);
  const margin =
    typeof input.safetyMargin === "number" && input.safetyMargin >= 0 && input.safetyMargin < 1
      ? input.safetyMargin
      : DEFAULT_SAFETY_MARGIN;
  const safetyTokens = Math.ceil(contextWindow * margin);
  const overheadTokens = Math.max(0, positiveInt(input.overheadTokens, 0));

  let promptBudget = contextWindow - responseReserve - safetyTokens;
  const engineCeiling = positiveInt(input.promptBudget, 0);
  if (engineCeiling > 0) promptBudget = Math.min(promptBudget, engineCeiling);
  promptBudget = Math.max(0, promptBudget - overheadTokens);

  const slices = {} as Record<SliceName, number>;
  let assigned = 0;
  for (const name of Object.keys(SLICE_SHARES) as SliceName[]) {
    slices[name] = Math.floor(promptBudget * SLICE_SHARES[name]);
    assigned += slices[name];
  }
  // Rounding crumbs go to the scratchpad rather than being lost.
  slices.scratchpad += Math.max(0, promptBudget - assigned);

  const pinnedTurns = pinnedTurnsForWindow(contextWindow);
  return {
    contextWindow,
    responseReserve,
    safetyTokens,
    overheadTokens,
    promptBudget,
    slices,
    pinnedTurns,
    perFileCapTokens: Math.min(
      PER_FILE_HARD_CAP_TOKENS,
      Math.floor(slices.workingSet * PER_FILE_SHARE_OF_WORKING_SET)
    ),
    perObservationCapTokens: Math.floor(slices.scratchpad / Math.max(1, pinnedTurns)),
    viable: promptBudget >= MIN_VIABLE_PROMPT_BUDGET,
  };
}
