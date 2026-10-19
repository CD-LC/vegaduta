// D-9 dynamic context budget: pure token estimation and prompt windowing
// (extracted verbatim from tier1.ts; no engine, DOM or storage access).

import type { ChatCompletionMessageParam } from "@mlc-ai/web-llm";

/** Last-resort window when neither the manifest nor the installed engine
 * record answers — matches the smallest window in the served catalog's
 * common case (MLC prebuilts ship 4096; see WEBLLM_BASELINE_CONTEXT_WINDOW
 * in capabilities.ts). */
export const WEBLLM_FALLBACK_CONTEXT_WINDOW = 4096;

/** Tokens held back for the reply. This module never sets max_tokens on its
 * chat.completions.create calls (checked 2026-08-07 — {stream, messages}
 * only), so there is no per-call generation config to read a real value
 * from; this is the documented default reserve the design calls for. */
export const TIER1_OUTPUT_RESERVE_TOKENS = 1024;

/** Fraction of the window held back to absorb estimator error and the chat
 * template's own per-message wrapping tokens. */
export const TIER1_BUDGET_SAFETY_MARGIN = 0.08;

/** HONEST LIMITATION, by design (D-9 "why this shape"): WebLLM exposes no
 * cheap tokenizer to the main thread, so tokens are ESTIMATED at chars/3.5 —
 * deliberately below the ~4 chars/token English average so the estimate
 * over-counts (safe direction), with the 8% margin absorbing the rest.
 * Non-English/CJK text tokenizes denser than this; the engine-side
 * ContextWindowSizeExceededError catch in toTier1Error is the belt-and-braces
 * for genuine estimator misses. */
export const TOKEN_ESTIMATE_CHARS_PER_TOKEN = 3.5;

export function estimateTier1Tokens(text: string): number {
  if (!text) return 0;
  return Math.ceil(text.length / TOKEN_ESTIMATE_CHARS_PER_TOKEN);
}

/** budget = window − output reserve − ceil(window × margin). Can legitimately
 * be ≤ 0 on a pathologically small window — the assembly below then reports
 * the irreducible prompt as over budget, which is the honest answer. */
export function computeTier1PromptBudget(
  contextWindowSize: number,
  reserveTokens: number = TIER1_OUTPUT_RESERVE_TOKENS
): number {
  return contextWindowSize - reserveTokens - Math.ceil(contextWindowSize * TIER1_BUDGET_SAFETY_MARGIN);
}

function messageTokens(message: ChatCompletionMessageParam): number {
  const content = (message as { content?: unknown }).content;
  return estimateTier1Tokens(typeof content === "string" ? content : content == null ? "" : JSON.stringify(content));
}

export interface Tier1PromptAssembly {
  /** False = the IRREDUCIBLE prompt (system + current turn) alone exceeds
   * the budget — the caller must NOT attempt local inference. */
  fits: boolean;
  /** The windowed prompt: system + surviving history + current turn (when
   * fits) / system + current turn only (when not — informational). */
  messages: ChatCompletionMessageParam[];
  /** How many prior-history messages were trimmed (debug/telemetry). */
  droppedHistoryMessages: number;
  /** Estimated tokens of `messages` (the irreducible set when !fits). */
  estimatedPromptTokens: number;
  budgetTokens: number;
}

/**
 * Windowed prompt assembly (D-9 priority order): the system message and the
 * CURRENT turn (the user's message plus any tool envelopes/results appended
 * this turn) are irreducible; prior history is trimmed oldest-first in WHOLE
 * turns — a turn being a user-role message plus every message up to the next
 * user-role one, so an assistant reply is never separated from its prompt
 * (and never split mid-turn). Priority 3 of the design (optional
 * memory/guardrail prelude blocks, truncated last) has no representation in
 * this module today — tier-1 prompts carry no such blocks — so there is
 * deliberately nothing here for it. Pure: never mutates its inputs.
 */
export function assembleTier1Prompt(
  system: ChatCompletionMessageParam,
  history: ChatCompletionMessageParam[],
  currentTurn: ChatCompletionMessageParam[],
  contextWindowSize: number,
  reserveTokens: number = TIER1_OUTPUT_RESERVE_TOKENS
): Tier1PromptAssembly {
  const budgetTokens = computeTier1PromptBudget(contextWindowSize, reserveTokens);
  const irreducibleTokens =
    messageTokens(system) + currentTurn.reduce((sum, m) => sum + messageTokens(m), 0);
  if (irreducibleTokens > budgetTokens) {
    return {
      fits: false,
      messages: [system, ...currentTurn],
      droppedHistoryMessages: history.length,
      estimatedPromptTokens: irreducibleTokens,
      budgetTokens,
    };
  }
  // Group prior history into whole turns: each group starts at a user-role
  // message (tool results are user-role too and belong to the turn they
  // opened — close enough for trimming granularity; the invariant that
  // matters is "never leave a dangling assistant message at the front").
  const groups: ChatCompletionMessageParam[][] = [];
  for (const message of history) {
    if (message.role === "user" || groups.length === 0) groups.push([message]);
    else groups[groups.length - 1].push(message);
  }
  // Keep the NEWEST whole turns that still fit (equivalently: trim
  // oldest-first), walking backward and stopping at the first turn that
  // would overflow — never skipping an older turn past a dropped newer one,
  // which would reorder context.
  let used = irreducibleTokens;
  let firstKeptGroup = groups.length;
  for (let i = groups.length - 1; i >= 0; i--) {
    const groupTokens = groups[i].reduce((sum, m) => sum + messageTokens(m), 0);
    if (used + groupTokens > budgetTokens) break;
    used += groupTokens;
    firstKeptGroup = i;
  }
  const kept = groups.slice(firstKeptGroup).flat();
  return {
    fits: true,
    messages: [system, ...kept, ...currentTurn],
    droppedHistoryMessages: history.length - kept.length,
    estimatedPromptTokens: used,
    budgetTokens,
  };
}

/**
 * Tool-relief assembly (D-9 follow-up, 2026-08-08): the tool protocol block
 * (toolProtocolInstructions — full JSON schema per tool) lives inside the
 * system message, making it IRREDUCIBLE while tools are active. On agents
 * with several tools that block alone can exceed the whole budget, so every
 * turn overflowed and fell through to hosted — and "Clear context" couldn't
 * help, because history was never the part over budget. When the with-tools
 * prompt overflows but the plain-system prompt fits, prefer answering
 * on-device WITHOUT tools this turn over not answering on-device at all
 * (the last tool round already runs tool-less by design, so this is an
 * existing, known-good degradation — not a new mode). When neither fits,
 * the with-tools assembly is returned so the caller's overflow telemetry
 * reports the full, honest ask.
 */
export function assembleTier1PromptWithToolRelief(
  systemWithTools: ChatCompletionMessageParam,
  plainSystem: ChatCompletionMessageParam,
  history: ChatCompletionMessageParam[],
  currentTurn: ChatCompletionMessageParam[],
  contextWindowSize: number,
  reserveTokens: number = TIER1_OUTPUT_RESERVE_TOKENS
): { assembly: Tier1PromptAssembly; toolsDropped: boolean } {
  const withTools = assembleTier1Prompt(
    systemWithTools,
    history,
    currentTurn,
    contextWindowSize,
    reserveTokens
  );
  if (withTools.fits) return { assembly: withTools, toolsDropped: false };
  const plain = assembleTier1Prompt(plainSystem, history, currentTurn, contextWindowSize, reserveTokens);
  if (plain.fits) return { assembly: plain, toolsDropped: true };
  return { assembly: withTools, toolsDropped: false };
}
