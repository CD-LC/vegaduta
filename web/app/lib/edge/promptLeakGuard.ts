// Prompt-disclosure guard for on-device replies (prod signed-in pass
// 2026-10-05, bug 2).
//
// What happened: in a Chromium build that ships the Prompt API surface
// without a model, the "built-in model" is a stub that answers every prompt
// with "...this API is just echoing back the input:" followed by the whole
// input - including the agent's hidden system prompt. The tier router took
// that as a successful tier-0 reply and the Playground rendered the agent's
// instructions as the answer.
//
// Two independent defences live here, both pure so they are unit-testable
// without a browser:
//   1. replyLeaksSystemPrompt - a buffered check on a FINISHED reply. A reply
//      that reproduces a long verbatim run of the system prompt is not an
//      answer; the router discards it and falls through to the server model.
//   2. createLeakHoldback - a streaming gate in front of onToken, so the run
//      is never painted mid-stream in the first place. Text is withheld only
//      while its tail is still a candidate run of the prompt; ordinary
//      replies stream untouched.
//
// Threshold: a verbatim run of >= 80 characters, or >= 40% of the prompt when
// the prompt is short (floored at 24 characters so a reply that happens to
// share a few words with a one-line prompt is not thrown away).

/** A verbatim run this long is a leak whatever the prompt's size. */
export const LEAK_MIN_RUN_CHARS = 80;
/** ...or this share of a prompt too short to ever produce an 80-char run. */
export const LEAK_PROMPT_FRACTION = 0.4;
/** Never call fewer than this many shared characters a leak. */
export const LEAK_FLOOR_CHARS = 24;
/** The streaming gate starts withholding once the tail matches this much. */
const HOLD_MIN_CHARS = 16;

/** Whitespace-insensitive comparison: a stub (or a model) that re-wraps the
 * prompt must not slip under a byte-exact check. */
function normalize(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/** How many consecutive prompt characters a reply must reproduce to count as
 * a leak, for a prompt of `promptLength` normalized characters. */
export function leakThreshold(promptLength: number): number {
  return Math.min(LEAK_MIN_RUN_CHARS, Math.max(LEAK_FLOOR_CHARS, Math.ceil(promptLength * LEAK_PROMPT_FRACTION)));
}

/**
 * True when `reply` contains a verbatim run of `systemPrompt` at least
 * leakThreshold() long. A missing/blank prompt, or one shorter than the
 * floor, can never leak by this definition.
 */
export function replyLeaksSystemPrompt(reply: string, systemPrompt: string | null | undefined): boolean {
  const prompt = normalize(systemPrompt || "");
  if (prompt.length < LEAK_FLOOR_CHARS) return false;
  const text = normalize(reply || "");
  const run = leakThreshold(prompt.length);
  if (text.length < run) return false;
  // Every `run`-gram of the prompt, then one pass over the reply: O(n + m)
  // lookups instead of a quadratic longest-common-substring table.
  const grams = new Set<string>();
  for (let i = 0; i + run <= prompt.length; i++) grams.add(prompt.slice(i, i + run));
  for (let i = 0; i + run <= text.length; i++) {
    if (grams.has(text.slice(i, i + run))) return true;
  }
  return false;
}

export interface LeakHoldback {
  /** Feed one streamed delta. Forwards whatever is safe to show. */
  push(delta: string): void;
  /** Stream finished cleanly - release anything still withheld. */
  flush(): void;
  /** True once a leak run was seen; nothing is forwarded after that. */
  readonly leaked: boolean;
}

/**
 * Streaming gate in front of a caller's onToken. Deltas pass straight through
 * until the accumulated text's tail starts matching the prompt; from there the
 * matching tail is withheld until either the match breaks (released, the
 * reply merely quoted a few words) or it reaches the leak threshold (latched:
 * nothing further is forwarded, and the caller discards the turn).
 *
 * Compared on raw text - a streamed echo is byte-identical, and normalizing a
 * growing buffer per token would make the offsets meaningless. The buffered
 * replyLeaksSystemPrompt check on the finished reply is the whitespace-
 * tolerant backstop.
 */
export function createLeakHoldback(
  systemPrompt: string | null | undefined,
  forward: ((delta: string) => void) | undefined
): LeakHoldback {
  const prompt = systemPrompt || "";
  const run = leakThreshold(normalize(prompt).length);
  const active = normalize(prompt).length >= LEAK_FLOOR_CHARS;
  let full = "";
  let flushed = 0;
  let leaked = false;
  let gramSet: Set<string> | null = null;
  const grams = (): Set<string> => {
    if (!gramSet) {
      gramSet = new Set<string>();
      for (let i = 0; i + run <= prompt.length; i++) gramSet.add(prompt.slice(i, i + run));
    }
    return gramSet;
  };

  const emit =(upTo: number) => {
    if (upTo <= flushed) return;
    const out = full.slice(flushed, upTo);
    flushed = upTo;
    if (out && forward) forward(out);
  };

  /** Length of the longest tail of `full` (up to `run`) that occurs in the
   * prompt. Containment is monotonic in the tail length, so walk down. */
  const matchingTail = (): number => {
    const max = Math.min(run - 1, full.length);
    for (let k = max; k >= HOLD_MIN_CHARS; k--) {
      if (prompt.includes(full.slice(full.length - k))) return k;
    }
    return 0;
  };

  return {
    push(delta: string) {
      if (leaked || !delta) return;
      if (!active) {
        if (forward) forward(delta);
        return;
      }
      const scanFrom = Math.max(0, full.length - run + 1);
      full += delta;
      // A run can sit in the MIDDLE of one large chunk (a stub hands back the
      // whole echo at once, with the user's message after the prompt), so
      // every new window is checked, not just the tail.
      for (let i = scanFrom; i + run <= full.length; i++) {
        if (grams().has(full.slice(i, i + run))) {
          leaked = true;
          return;
        }
      }
      emit(full.length - matchingTail());
    },
    flush() {
      if (leaked || !active) return;
      emit(full.length);
    },
    get leaked() {
      return leaked;
    },
  };
}
