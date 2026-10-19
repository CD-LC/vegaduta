// agentkit — the plan → act → observe loop.
//
// One iteration: assemble the window (context.ts) → one stateless model call →
// read the reply (protocol.ts) → run at most ONE tool (tools.ts) → record the
// step → save. It ends when the model answers in prose, when a budget runs out,
// when loop detection trips, or when the person stops it.
//
// Why bounded (same reasoning as clients/shared/src/agent/loop.ts): an
// unbounded local loop with a write tool is how an agent quietly rewrites a
// folder while nobody is watching. On-device tokens cost no money, but every
// step is seconds of someone's GPU and battery — so there is a step budget AND
// a token budget, and both are safety properties, not tuning knobs.
//
// RESUMABLE BY CONSTRUCTION. Everything the loop knows lives in AgentRunState,
// a plain JSON value; there is no hidden conversation inside the model adapter
// (AgentModel is stateless). So "continue after a reload" is just "call
// runAgent again with the saved state": the window is rebuilt from it exactly
// as it would have been. What is deliberately NOT saved: a pending approval
// (the step is simply taken again, and asks again) and the per-run "allow all"
// switches (a resumed run has not been told yes).
//
// Never throws. Every exit is a RunStatus with a plain-language `answer`.

import { planContextBudget, type ContextBudget } from "./budget";
import { assembleContext } from "./context";
import { confinePath } from "./paths";
import { AGENT_CALL_TAG, READ_FILE, buildSystemPrompt, buildToolsBlock, malformedFeedback, parseAgentReply, toolNames } from "./protocol";
import { executeTool, type RunApprovals } from "./tools";
import { estimateTokens } from "./tokens";
import type {
  AgentEvent,
  AgentHost,
  AgentModel,
  AgentRunState,
  AgentToolCall,
  AgentTurn,
  ApprovalDecision,
  ApprovalRequest,
  RunStatus,
} from "./types";

/** Steps per invocation. Lower than the native clients' 60 on purpose: each
 *  step here is a multi-second on-device generation, and a resumed run gets a
 *  fresh allowance, so a low ceiling costs one click, not the task. */
export const DEFAULT_MAX_STEPS = 24;
/** Estimated prompt+reply tokens per invocation. */
export const DEFAULT_MAX_TOKENS = 120_000;
/** Consecutive unusable replies before the run is called stalled. */
export const MALFORMED_LIMIT = 3;
/** Refused repeats in a row before the run is called stalled. */
export const REPEAT_LIMIT = 2;
const FINGERPRINT_WINDOW = 8;
const MAX_NOTE_CHARS = 280;
const MAX_TASK_CHARS = 8000;

/** The edge session appends a short "answer directly and completely" nudge to
 *  every system prompt (lib/edge/tier1.ts REASONING_NUDGE, ~150 characters).
 *  This module cannot see it, so it is reserved for. */
export const TRANSPORT_OVERHEAD_TOKENS = 64;

export function newRunState(task: string): AgentRunState {
  return {
    version: 1,
    task: (task || "").trim().slice(0, MAX_TASK_CHARS),
    plan: [],
    turns: [],
    summary: [],
    summaryDropped: 0,
    workingSet: [],
    recentHits: {},
    stepsUsed: 0,
    tokensUsed: 0,
    mutationEpoch: 0,
    recentFingerprints: [],
    repeatCount: 0,
    malformedStreak: 0,
    status: "idle",
    answer: "",
    secretReadsAllowed: [],
  };
}

/** A run that stopped for a reason "continue" can do something about. A
 *  finished or stalled run is not resumed — a stalled one would stall again. */
export function isResumable(state: AgentRunState | null | undefined): boolean {
  if (!state || !state.task) return false;
  return (
    state.status === "running" ||
    state.status === "awaiting-approval" ||
    state.status === "stopped" ||
    state.status === "step-budget" ||
    state.status === "token-budget" ||
    state.status === "failed"
  );
}

/** Key order must not change the fingerprint: {"a":1,"b":2} and {"b":2,"a":1}
 *  are the same call. */
export function stableStringify(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "null";
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  const record = value as Record<string, unknown>;
  return `{${Object.keys(record)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${stableStringify(record[key])}`)
    .join(",")}}`;
}

/** The mutation epoch is part of the identity of a call: reading a file, editing
 *  it and reading it AGAIN is progress, not a loop. */
export function callFingerprint(call: AgentToolCall, mutationEpoch: number): string {
  return `${call.name}:${stableStringify(call.args)}@${mutationEpoch}`;
}

export type RepeatVerdict = "fresh" | "repeat";

/** First words of the turn the loop records when it refuses a repeat. */
export const REFUSAL_PREFIX = "Not run:";

/**
 * Stall detection. A call is a repeat when it is identical to the one just
 * before it (same tool, same arguments, nothing written in between), or when
 * it has already appeared twice in the recent window — the second rule catches
 * the A, B, A, B ping-pong a "same as last time" check never sees.
 *
 * This is the fingerprint half only. The loop additionally requires that the
 * earlier result is still in the model's window (resultStillVisible) before it
 * refuses anything.
 */
export function judgeRepeat(recent: string[], fingerprint: string): RepeatVerdict {
  if (recent.length > 0 && recent[recent.length - 1] === fingerprint) return "repeat";
  return recent.filter((f) => f === fingerprint).length >= 2 ? "repeat" : "fresh";
}

/**
 * Is the result of an earlier identical call still in front of the model?
 *
 * Repeat detection only means something when it is. The context manager tells
 * the model to "repeat the call if you need it again" when it drops an output,
 * and calls evicting a file excerpt "cheap to undo: the model re-reads" — on a
 * 4k window a read-only task over two files that cannot both fit HAS to
 * alternate between them. Refusing that as a loop would end the run for doing
 * what it was told (review finding, 2026-09-19).
 *
 * `state` must be the state AFTER assembleContext(), i.e. what was really sent:
 *   - a successful read_file lives in the working set, not in its turn (whose
 *     text is only "Opened — see FILE EXCERPTS"), so it is visible while its
 *     path is still held there;
 *   - anything else, and any FAILED call, is visible while an identical turn
 *     is still verbatim with its output not dropped.
 * The newest turns are pinned and never dropped, so an immediate repeat is
 * always still visible and always still refused. What stays bounded when this
 * returns false is the step budget, which pauses the run.
 */
export function resultStillVisible(call: AgentToolCall, state: AgentRunState): boolean {
  const identity = `${call.name}:${stableStringify(call.args)}`;
  const same = state.turns.filter((turn) => turn.call !== null && `${turn.call.name}:${stableStringify(turn.call.args)}` === identity);
  // The loop's OWN refusals ("Not run: ...") are not results. Counting them made
  // one earlier refusal block a legitimate re-read of an evicted excerpt, with
  // the false text "its result is still shown above" (verifier, 2026-09-19).
  const executed = same.filter((turn) => !turn.observation.startsWith(REFUSAL_PREFIX));
  if (executed.some((turn) => turn.failed && !turn.observationEvicted)) return true;
  if (call.name === READ_FILE) {
    const confined = confinePath(call.args.path);
    if (confined.ok !== true) return true; // refused paths never produce an excerpt; the failed turn above is the record
    return state.workingSet.some((slice) => slice.path === confined.path);
  }
  return executed.some((turn) => !turn.observationEvicted);
}

export interface RunOptions {
  host: AgentHost;
  model: AgentModel;
  state: AgentRunState;
  approve: (request: ApprovalRequest) => Promise<ApprovalDecision>;
  maxSteps?: number;
  maxTokens?: number;
  signal?: AbortSignal;
  onEvent?: (event: AgentEvent) => void;
  /** Called with the state after every step. Best-effort; may throw freely. */
  persist?: (state: AgentRunState) => void;
}

export function budgetForModel(model: AgentModel): ContextBudget {
  return planContextBudget({
    contextWindow: model.contextWindow,
    promptBudget: model.promptBudget,
    overheadTokens: TRANSPORT_OVERHEAD_TOKENS,
  });
}

export async function runAgent(options: RunOptions): Promise<AgentRunState> {
  const { host, model, signal } = options;
  const maxSteps = Math.max(1, options.maxSteps ?? DEFAULT_MAX_STEPS);
  const maxTokens = Math.max(1, options.maxTokens ?? DEFAULT_MAX_TOKENS);
  const approvals: RunApprovals = { edit: false, command: false };
  const callTag = model.callTag || AGENT_CALL_TAG;
  const hasTerminal = !!host.terminal;
  const known = toolNames(hasTerminal);
  // run_command stays PARSEABLE even where it is not offered, so a model that
  // emits it anyway gets the honest "not run here" result instead of having
  // its envelope shown to the person as a final answer.
  const parseable = known.includes("run_command") ? known : [...known, "run_command"];

  let state: AgentRunState = { ...options.state, status: "running", answer: "" };
  let stepsThisRun = 0;
  const tokensAtStart = state.tokensUsed;

  const emit = (event: AgentEvent): void => {
    try {
      options.onEvent?.(event);
    } catch {
      // A listener must never be able to kill a run.
    }
  };
  const save = (): void => {
    try {
      options.persist?.(state);
    } catch {
      // Losing resumability costs a restart, never correctness.
    }
  };
  const end = (status: RunStatus, answer: string): AgentRunState => {
    state = { ...state, status, answer };
    emit({ type: "state", state });
    save();
    return state;
  };

  try {
    if (!state.task) return end("failed", "There is no task to work on.");
    if (!host.fs) return end("failed", "This host cannot read files, so there is nothing the agent can do here.");

    const budget = budgetForModel(model);
    if (!budget.viable) {
      return end(
        "window-too-small",
        `This on-device model's context window (${model.contextWindow} tokens) leaves about ${budget.promptBudget} for the prompt — too little to hold the instructions, a plan and a file at once. Download a larger model in VegaDūta Edge (/edge) and try again.`
      );
    }
    const systemPrompt = buildSystemPrompt({ rootName: host.rootName, hasTerminal });
    const toolsBlock = buildToolsBlock(hasTerminal, callTag);
    emit({ type: "state", state });

    for (;;) {
      if (signal?.aborted) return end("stopped", "Stopped. Nothing further was changed.");
      if (stepsThisRun >= maxSteps) {
        return end("step-budget", `Paused after ${maxSteps} steps so it cannot run unattended forever. Continue to give it ${maxSteps} more.`);
      }
      if (state.tokensUsed - tokensAtStart >= maxTokens) {
        return end("token-budget", "Paused: this run has used its token budget. Continue to give it another.");
      }

      const assembled = assembleContext(state, budget, { systemPrompt, toolsBlock });
      state = assembled.nextState;
      emit({ type: "context", report: assembled.report });
      if (!assembled.report.fits) {
        return end(
          "window-too-small",
          "The task and plan alone no longer fit this model's context window. Shorten the task, or download a larger model in VegaDūta Edge (/edge)."
        );
      }

      const step = state.stepsUsed + 1;
      emit({ type: "model-start", step });
      const reply = await model.complete(assembled.system, assembled.user, signal);
      if (reply.ok !== true) {
        const failure = reply as { reason: string; detail: string };
        if (failure.reason === "aborted" || signal?.aborted) return end("stopped", "Stopped. Nothing further was changed.");
        return end("failed", failure.detail || "The on-device model could not produce a reply.");
      }
      stepsThisRun += 1;
      state = {
        ...state,
        stepsUsed: step,
        tokensUsed: state.tokensUsed + assembled.report.usedTokens + estimateTokens(reply.text),
      };

      const parsed = parseAgentReply(reply.text, parseable);

      if (parsed.kind === "text") {
        if (!parsed.text.trim()) return end("failed", "The on-device model returned an empty reply. Try again, or use a larger model.");
        return end("finished", parsed.text.trim());
      }

      if (parsed.kind === "malformed") {
        const streak = state.malformedStreak + 1;
        const turn: AgentTurn = {
          step,
          call: null,
          note: reply.text.trim().slice(0, MAX_NOTE_CHARS),
          observation: malformedFeedback(parsed.reason, callTag),
          observationEvicted: false,
          failed: true,
        };
        state = { ...state, malformedStreak: streak, turns: [...state.turns, turn] };
        emit({ type: "state", state });
        save();
        if (streak >= MALFORMED_LIMIT) {
          return end(
            "stalled",
            `Stopped: the model produced ${streak} replies in a row that could not be read as a tool call. Smaller models struggle with this — a larger on-device model usually fixes it.`
          );
        }
        continue;
      }

      // ---- a tool call ---------------------------------------------------
      const call = parsed.call;
      const fingerprint = callFingerprint(call, state.mutationEpoch);
      // A repeat only when the earlier result is still in the window — see
      // resultStillVisible(). `state` here is assembleContext's nextState, so
      // it describes exactly what the model was shown this step.
      const verdict: RepeatVerdict =
        judgeRepeat(state.recentFingerprints, fingerprint) === "repeat" && resultStillVisible(call, state) ? "repeat" : "fresh";
      const recentFingerprints = [...state.recentFingerprints, fingerprint].slice(-FINGERPRINT_WINDOW);

      if (verdict === "repeat") {
        const repeatCount = state.repeatCount + 1;
        const turn: AgentTurn = {
          step,
          call,
          note: parsed.note.slice(0, MAX_NOTE_CHARS),
          // NOT executed. Running it again would return the same thing and
          // spend the window on a second copy of it.
          observation:
            REFUSAL_PREFIX + " you already made this exact call, its result is still shown above, and nothing has changed since — so the result would be identical. Try a different tool or different arguments, or give your final answer.",
          observationEvicted: false,
          failed: true,
        };
        state = { ...state, recentFingerprints, repeatCount, malformedStreak: 0, turns: [...state.turns, turn] };
        emit({ type: "state", state });
        save();
        if (repeatCount >= REPEAT_LIMIT) {
          return end("stalled", `Stopped: the model kept repeating the same ${call.name} call without making progress.`);
        }
        continue;
      }

      state = { ...state, status: "running", recentFingerprints, repeatCount: 0, malformedStreak: 0 };
      emit({ type: "tool-start", step, call });
      const result = await executeTool(call, {
        host,
        state,
        budget,
        step,
        approvals,
        signal,
        approve: async (request) => {
          state = { ...state, status: "awaiting-approval" };
          emit({ type: "state", state });
          try {
            return await options.approve(request);
          } finally {
            state = { ...state, status: "running" };
          }
        },
      });
      emit({ type: "tool-end", step, call, failed: result.failed });

      const turn: AgentTurn = {
        step,
        call,
        note: parsed.note.slice(0, MAX_NOTE_CHARS),
        observation: result.observation,
        observationEvicted: false,
        failed: result.failed,
      };
      // result.state was derived from the state handed to the tool; carry over
      // the counters this loop owns.
      state = { ...result.state, status: "running", turns: [...result.state.turns, turn] };
      emit({ type: "state", state });
      save();
      if (result.stop) return end("stopped", "Stopped at your request. Changes you had already approved stay applied.");
    }
  } catch (error) {
    // Belt-and-braces: nothing above is expected to throw.
    return end("failed", error instanceof Error && error.message ? error.message : "The agent run failed unexpectedly.");
  }
}
