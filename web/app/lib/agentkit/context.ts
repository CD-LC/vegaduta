// agentkit — the context manager: what goes into the window THIS step, what
// was pushed out to make room, and why. Pure; no I/O; never mutates its input.
//
// The loop never sends a transcript. Before every model call it hands the whole
// run state to assembleContext(), which rebuilds the prompt from scratch inside
// the budget (budget.ts) and returns three things: the prompt, a report of
// every slice's use and every eviction (the /code meter renders exactly this —
// it is not a separate estimate), and the COMPACTED state to carry forward, so
// what was folded away stays folded and the state saved for resume stays small.
//
// Eviction policy, in the order it is applied:
//   1. Never touched: the system prompt, the tool list, the task, the plan, and
//      the newest `pinnedTurns` steps. The task and the plan are what keep a
//      small model on course over many steps; losing either is how an agent
//      forgets what it was doing, which is worse than losing any file.
//   2. Oldest tool OUTPUT first. The step stays ("step 3: search_text …"), its
//      output is replaced by a one-line stub. Output is the bulk of the bytes
//      and the least valuable part once the model has acted on it.
//   3. Then the oldest whole steps are folded into a running SUMMARY, one line
//      each. Deterministic, not model-written: a summarising model call would
//      cost a full generation on a device where each one takes seconds, and a
//      1-3B model's summary of its own work is exactly where invented progress
//      creeps in. The line records what was called and whether it failed.
//   4. The summary itself is capped — oldest lines drop, with a count left
//      behind so the model knows there was more.
//   5. File excerpts are evicted least-recently-touched first. Evicting one is
//      cheap to undo: the model re-reads the range it needs.
//
// "Repeat the call if you need it again" (the stub an evicted output leaves) is
// a promise the LOOP has to keep: loop.ts resultStillVisible() exempts a call
// whose earlier result is no longer in the window from repeat detection, so
// following that advice is never what ends a run as "going in circles".

import type { ContextBudget, SliceName } from "./budget";
import { renderPlan } from "./protocol";
import { describeRanges, describeSlice } from "./slices";
import { estimateTokens, truncateToTokens } from "./tokens";
import type { AgentRunState, AgentToolCall, AgentTurn, FileSlice, PlanItem } from "./types";

export interface SliceUse {
  name: SliceName;
  label: string;
  used: number;
  /** The cap that applied THIS step. Working set and scratchpad share whatever
   *  the fixed parts left over, so their caps move from step to step. */
  cap: number;
}

export interface Eviction {
  what: string;
  why: string;
}

export interface ContextReport {
  contextWindow: number;
  promptBudget: number;
  responseReserve: number;
  usedTokens: number;
  fits: boolean;
  slices: SliceUse[];
  /** Evictions performed by THIS assembly. The UI accumulates them. */
  evicted: Eviction[];
  /** `lines` is the text of the ranges really held ("40-52, 120-133"); an
   *  excerpt built around search hits has gaps, and startLine/endLine alone
   *  would claim the lines in between. */
  filesInWindow: Array<{ path: string; startLine: number; endLine: number; totalLines: number; lines: string }>;
  turnsVerbatim: number;
  turnsSummarised: number;
}

export interface AssembledContext {
  system: string;
  user: string;
  report: ContextReport;
  nextState: AgentRunState;
}

const SLICE_LABELS: Record<SliceName, string> = {
  system: "Instructions",
  tools: "Tool list",
  task: "Your task",
  plan: "Plan",
  summary: "Summary of earlier steps",
  workingSet: "File excerpts",
  scratchpad: "Recent steps",
};

const MAX_ARG_STRING_CHARS = 120;

/** Compact, bounded rendering of a call for the step log. Long string values
 *  (write_file's content, edit_file's snippets) are elided: the model already
 *  wrote them, and carrying a 200-line "content" through every later step would
 *  spend the scratchpad on text nobody needs to see again. */
export function renderCall(call: AgentToolCall): string {
  const shown: Record<string, unknown> = {};
  for (const key of Object.keys(call.args || {})) {
    const value = call.args[key];
    if (typeof value === "string" && value.length > MAX_ARG_STRING_CHARS) {
      shown[key] = `${value.slice(0, 100)}… (${value.length} chars)`;
    } else {
      shown[key] = value;
    }
  }
  let json: string;
  try {
    json = JSON.stringify(shown);
  } catch {
    json = "{}";
  }
  return `${call.name} ${json}`;
}

/** The one line a folded step leaves behind. */
export function summariseTurn(turn: AgentTurn): string {
  const what = turn.call ? renderCall(turn.call) : "replied without a tool call";
  const firstLine = (turn.observation || "").split("\n")[0].slice(0, 90);
  const result = turn.failed ? `FAILED${firstLine ? `: ${firstLine}` : ""}` : "ok";
  return `step ${turn.step}: ${what.slice(0, 160)} → ${result}`;
}

function renderTurn(turn: AgentTurn, observationCapTokens: number | null): string {
  const head = `Step ${turn.step}: ${turn.call ? renderCall(turn.call) : "(no tool call)"}`;
  const note = turn.note ? `\nYou said: ${turn.note}` : "";
  let observation: string;
  if (turn.observationEvicted) {
    observation = `(output dropped to save context — it ${turn.failed ? "FAILED" : "succeeded"}; repeat the call if you need it again)`;
  } else if (observationCapTokens !== null) {
    // Command output keeps its tail (errors are at the end); everything else
    // keeps its head.
    const keep = turn.call && turn.call.name === "run_command" ? "tail" : "head";
    observation = truncateToTokens(turn.observation, observationCapTokens, keep);
  } else {
    observation = turn.observation;
  }
  return `${head}${note}\nResult: ${observation || "(empty)"}`;
}

/** Plan text within its cap. Done items collapse first ("3 steps done") — what
 *  is LEFT to do is the part that steers the next step. */
function fitPlan(plan: PlanItem[], capTokens: number, evicted: Eviction[]): string {
  const full = renderPlan(plan);
  if (estimateTokens(full) <= capTokens) return full;
  const open = plan.filter((item) => item.status !== "done");
  const doneCount = plan.length - open.length;
  const collapsed = (doneCount > 0 ? `(${doneCount} earlier plan step(s) done)\n` : "") + renderPlan(open);
  if (doneCount > 0) evicted.push({ what: `${doneCount} completed plan step(s)`, why: "plan over its slice — finished steps collapsed" });
  if (estimateTokens(collapsed) <= capTokens) return collapsed;
  evicted.push({ what: "end of the plan text", why: "plan still over its slice after collapsing finished steps" });
  return truncateToTokens(collapsed, capTokens, "head");
}

const TASK_HEADER = "TASK\n";
const PLAN_HEADER = "\n\nPLAN (keep it current with update_plan)\n";
const SUMMARY_HEADER = "\n\nEARLIER STEPS (summarised)\n";
const FILES_HEADER = "\n\nFILE EXCERPTS YOU HAVE OPEN\n";
const STEPS_HEADER = "\n\nRECENT STEPS\n";
const CLOSING = "\n\nWhat is your next step? Reply with one tool call, or with your final answer in plain text.";

export interface AssembleOptions {
  systemPrompt: string;
  toolsBlock: string;
}

export function assembleContext(state: AgentRunState, budget: ContextBudget, options: AssembleOptions): AssembledContext {
  const evicted: Eviction[] = [];
  const system = `${options.systemPrompt}\n\n${options.toolsBlock}`;
  const systemTokens = estimateTokens(options.systemPrompt + "\n\n");
  const toolsTokens = estimateTokens(options.toolsBlock);

  // ---- pinned: task + plan ---------------------------------------------
  const taskCap = budget.slices.task;
  let taskText = state.task;
  if (estimateTokens(taskText) > taskCap) {
    taskText = truncateToTokens(taskText, taskCap, "head");
    evicted.push({ what: "end of the task text", why: "task longer than its slice of the window" });
  }
  const taskSection = TASK_HEADER + taskText;
  const planSection = PLAN_HEADER + fitPlan(state.plan, budget.slices.plan, evicted);
  const taskTokens = estimateTokens(taskSection);
  const planTokens = estimateTokens(planSection);
  const closingTokens = estimateTokens(CLOSING);

  // ---- what is left is shared by summary, files and recent steps ---------
  const fixed = systemTokens + toolsTokens + taskTokens + planTokens + closingTokens;
  const summaryCap = Math.min(budget.slices.summary, Math.max(0, budget.promptBudget - fixed));
  // Sections are measured one by one but SENT as one string. With the length
  // estimate the parts can only over-count the whole (each rounds up), but a
  // plugged-in real tokenizer may count a few tokens differently across the
  // joins. A small allowance keeps a full window from tipping over on that.
  const ROUNDING_ALLOWANCE = 8;
  const flexible = Math.max(0, budget.promptBudget - fixed - summaryCap - ROUNDING_ALLOWANCE);
  const shareTotal = budget.slices.workingSet + budget.slices.scratchpad;
  const workingSetCap = shareTotal > 0 ? Math.floor((flexible * budget.slices.workingSet) / shareTotal) : 0;
  const scratchpadCap = flexible - workingSetCap;

  // ---- scratchpad: recent steps ----------------------------------------
  let turns: AgentTurn[] = state.turns.map((turn) => ({ ...turn }));
  const summary: string[] = [...state.summary];
  const pinnedCount = Math.min(budget.pinnedTurns, turns.length);
  const headerTokens = estimateTokens(STEPS_HEADER);

  const renderAll = (list: AgentTurn[], pinnedObservationCap: number | null): string => {
    const firstPinned = list.length - Math.min(budget.pinnedTurns, list.length);
    return list.map((turn, index) => renderTurn(turn, index >= firstPinned ? pinnedObservationCap : null)).join("\n\n");
  };

  let stepsText = renderAll(turns, null);
  let guard = 0;
  while (turns.length > 0 && headerTokens + estimateTokens(stepsText) > scratchpadCap && guard < 10_000) {
    guard += 1;
    const firstPinned = turns.length - pinnedCount;
    const victim = turns.findIndex((turn, index) => index < firstPinned && !turn.observationEvicted && turn.observation !== "");
    if (victim !== -1) {
      const turn = turns[victim];
      evicted.push({ what: `output of step ${turn.step} (${turn.call ? turn.call.name : "reply"})`, why: "oldest tool output goes first" });
      turns[victim] = { ...turn, observation: turn.observation.split("\n")[0].slice(0, 90), observationEvicted: true };
    } else if (firstPinned > 0) {
      const folded = turns[0];
      summary.push(summariseTurn(folded));
      evicted.push({ what: `step ${folded.step}`, why: "folded into the running summary" });
      turns = turns.slice(1);
    } else {
      break; // only pinned turns are left — they are truncated below, never dropped
    }
    stepsText = renderAll(turns, null);
  }
  if (turns.length > 0 && headerTokens + estimateTokens(stepsText) > scratchpadCap) {
    // Pinned steps alone are over the slice. Keep every one of them and cut
    // their outputs to an equal share — dropping the step the model JUST took
    // is how it ends up taking it again.
    const perTurn = Math.max(24, Math.floor((scratchpadCap - headerTokens) / turns.length) - 40);
    stepsText = renderAll(turns, perTurn);
    evicted.push({ what: "part of the newest tool output", why: "recent steps alone exceed their slice — outputs cut, steps kept" });
  }
  const stepsSection = turns.length > 0 ? STEPS_HEADER + stepsText : "";

  // ---- summary ----------------------------------------------------------
  let keptSummary = summary;
  let droppedNow = 0;
  const summaryText = (lines: string[], dropped: number): string =>
    (dropped > 0 ? `(${dropped} older step(s) not shown)\n` : "") + lines.join("\n");
  while (
    keptSummary.length > 0 &&
    estimateTokens(SUMMARY_HEADER + summaryText(keptSummary, state.summaryDropped + droppedNow)) > summaryCap
  ) {
    keptSummary = keptSummary.slice(1);
    droppedNow += 1;
  }
  if (droppedNow > 0) {
    evicted.push({ what: `${droppedNow} oldest summary line(s)`, why: "summary over its slice" });
  }
  const droppedSummary = state.summaryDropped + droppedNow;
  const summarySection = keptSummary.length > 0 ? SUMMARY_HEADER + summaryText(keptSummary, droppedSummary) : "";

  // ---- working set: file excerpts, least recently touched out first ------
  const byRecency = [...state.workingSet].sort((a, b) => b.touchedStep - a.touchedStep);
  const keptFiles: FileSlice[] = [];
  let filesTokens = byRecency.length > 0 ? estimateTokens(FILES_HEADER) : 0;
  // STOP at the first excerpt that does not fit rather than skipping past it to
  // squeeze in a smaller, older one (same walk as lib/localcode/ask.ts
  // fitChunks): "the most recently used excerpts that fit" is a rule a person
  // can check against the meter; "some of them, chosen partly by size" is not.
  let full = false;
  for (const slice of byRecency) {
    const block = `--- ${describeSlice(slice)} ---\n${slice.text}\n`;
    const cost = estimateTokens(block);
    if (!full && filesTokens + cost <= workingSetCap) {
      keptFiles.push(slice);
      filesTokens += cost;
    } else {
      full = true;
      evicted.push({
        what: `${slice.path} lines ${describeRanges(slice)}`,
        why: keptFiles.length === 0 ? "excerpt larger than the file slice of this model's window" : "least recently used file excerpt",
      });
    }
  }
  const filesSection =
    keptFiles.length > 0
      ? FILES_HEADER + keptFiles.map((slice) => `--- ${describeSlice(slice)} ---\n${slice.text}\n`).join("\n")
      : "";

  const user = taskSection + planSection + summarySection + filesSection + stepsSection + CLOSING;
  const usedTokens = estimateTokens(system) + estimateTokens(user);

  const report: ContextReport = {
    contextWindow: budget.contextWindow,
    promptBudget: budget.promptBudget,
    responseReserve: budget.responseReserve,
    usedTokens,
    fits: usedTokens <= budget.promptBudget,
    slices: [
      { name: "system", label: SLICE_LABELS.system, used: systemTokens, cap: budget.slices.system },
      { name: "tools", label: SLICE_LABELS.tools, used: toolsTokens, cap: budget.slices.tools },
      { name: "task", label: SLICE_LABELS.task, used: taskTokens, cap: budget.slices.task },
      { name: "plan", label: SLICE_LABELS.plan, used: planTokens, cap: budget.slices.plan },
      { name: "summary", label: SLICE_LABELS.summary, used: estimateTokens(summarySection), cap: summaryCap },
      { name: "workingSet", label: SLICE_LABELS.workingSet, used: keptFiles.length > 0 ? filesTokens : 0, cap: workingSetCap },
      { name: "scratchpad", label: SLICE_LABELS.scratchpad, used: estimateTokens(stepsSection), cap: scratchpadCap },
    ],
    evicted,
    filesInWindow: keptFiles.map((s) => ({
      path: s.path,
      startLine: s.startLine,
      endLine: s.endLine,
      totalLines: s.totalLines,
      lines: describeRanges(s),
    })),
    turnsVerbatim: turns.length,
    turnsSummarised: keptSummary.length + droppedSummary,
  };

  const nextState: AgentRunState = {
    ...state,
    turns,
    // Dropped summary lines stay dropped — only their COUNT is carried, so the
    // state saved for resume cannot grow without bound on a long run.
    summary: keptSummary,
    summaryDropped: droppedSummary,
    workingSet: keptFiles,
  };

  return { system, user, report, nextState };
}
