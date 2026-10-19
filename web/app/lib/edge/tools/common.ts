// Budgets, the refusal class and the argument helpers every pure tool shares. Split out of ../localTools.ts.
// Pure computation: runtime imports are limited to sibling pure modules.



// ---------------------------------------------------------------------------
// Budgets
// ---------------------------------------------------------------------------

/**
 * Longest result string handed back to the model, truncation marker included.
 *
 * Derived, not picked: tier1.ts budgets a tier-1 prompt as
 * `window − TIER1_OUTPUT_RESERVE_TOKENS − ceil(window × TIER1_BUDGET_SAFETY_MARGIN)`
 * and estimates tokens at chars/3.5. On the fallback window those constants are
 * 4096, 1024 and 0.08, giving 4096 − 1024 − 328 = 2744 tokens for the WHOLE
 * prompt — system block, tool schemas, history and the current turn. 2000 chars
 * estimates to 572 tokens, ~21% of that, so a tool result costs a fifth of the
 * budget rather than evicting the conversation that produced it. Those three
 * tier1.ts constants were read on 2026-09-12; the arithmetic above is this
 * comment's, not a value imported from there, because coupling this module's
 * test to a 129KB module several sessions are editing buys flakiness rather
 * than safety. If tier1 re-budgets, this number needs re-deriving by hand.
 *
 * Truncation is ANNOUNCED in the result (see capResult): a model that is handed
 * a silently-shortened list of sorted lines will reason about it as if it were
 * complete.
 */
export const MAX_RESULT_CHARS = 2000;

/**
 * Wall-clock ceiling on one handler, after which runLocalTool answers
 * `failed` and stops waiting.
 *
 * HONEST LIMITATION: this is a Promise.race against a timer on the page's only
 * thread, so it bounds a handler that AWAITS something forever — it cannot
 * interrupt a synchronous busy loop, which would wedge the tab regardless of
 * what this number is. The real defence against a runaway is structural and
 * sits in the handlers: every input is length-capped (MAX_EXPRESSION_CHARS,
 * MAX_TEXT_CHARS) and the expression parser is depth-capped, so the work each
 * one can be asked to do is bounded before it starts. 2s is three orders of
 * magnitude above what these four actually take (the whole 37-case suite,
 * every handler included, runs in ~54ms), so it can only ever fire on a
 * genuine hang — it is a safety net for a future async handler, not a
 * performance budget, and it must not be tightened into one.
 */
export const LOCAL_TOOL_TIMEOUT_MS = 2_000;

/** Longest expression local_calculate will parse. Bounds parser work (and, with
 * MAX_PAREN_DEPTH, recursion) before it begins. */
export const MAX_EXPRESSION_CHARS = 512;

/** Nesting ceiling for the recursive-descent parser — a guard against a stack
 * overflow from "((((((…", which would surface as a browser error rather than a
 * tool refusal. */
export const MAX_PAREN_DEPTH = 64;

/** Longest text local_transform accepts. Well above MAX_RESULT_CHARS on purpose:
 * counting or sorting a document the model already holds is exactly the case
 * this tool exists for, and only the ANSWER has to fit the context budget. */
export const MAX_TEXT_CHARS = 20_000;

// ---------------------------------------------------------------------------
// Failure vocabulary
//
// Two classes, mapped onto EdgeToolExecuteResult's existing codes so the loop
// needs no new shapes: a refusal is the tool working correctly and saying no
// ("rejected"), anything else is a defect in this module ("failed"). Callers
// can act on that difference - a refusal is worth showing the model so it can
// retry with better arguments; a failure is not.
// ---------------------------------------------------------------------------

export class LocalToolRefusal extends Error {}

export function refuse(message: string): never {
  throw new LocalToolRefusal(message);
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

export function capResult(text: string): string {
  if (text.length <= MAX_RESULT_CHARS) return text;
  // 80 chars reserved for the marker; the longest marker this can produce for a
  // MAX_TEXT_CHARS-sized input is well under that, and the test asserts the
  // final length regardless.
  const keep = MAX_RESULT_CHARS - 80;
  return `${text.slice(0, keep)}\n[truncated: showing the first ${keep} of ${text.length} characters]`;
}

/** Trims float noise without lying about magnitude: 0.1+0.2 prints as 0.3, and
 * 12 significant digits is still more precision than a chat answer can use. */
export function formatNumber(value: number): string {
  if (!Number.isFinite(value)) return String(value);
  if (Number.isInteger(value) && Math.abs(value) < 1e21) return String(value);
  return String(Number.parseFloat(value.toPrecision(12)));
}

export function requireString(args: Record<string, unknown>, key: string, maxChars: number): string {
  const raw = args[key];
  if (typeof raw !== "string") {
    refuse(`"${key}" must be a string (got ${raw === null ? "null" : typeof raw}).`);
  }
  if (raw.length > maxChars) {
    refuse(`"${key}" is ${raw.length} characters; this tool accepts at most ${maxChars}.`);
  }
  return raw;
}

