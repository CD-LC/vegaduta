// agentkit — token estimation.
//
// HONEST LIMITATION: neither WebLLM nor Chrome's Prompt API hands the main
// thread a cheap tokenizer, so by default tokens are ESTIMATED from length.
//
// Why 3.5 chars/token and not the usual 4 (2026-09-19): the on-device engine
// has its own gate. lib/edge/tier1.ts estimates every prompt at chars/3.5 and
// REFUSES to run one it thinks is over budget (Tier1Error
// "context-window-exceeded"). An estimator here that counted at 4 would
// routinely build prompts this module believes fit and that gate then rejects —
// the step fails after the context manager already did its work. Counting the
// same way the gate counts is the only choice that makes "fits" mean the same
// thing on both sides. 3.5 also errs in the safe direction for code, which
// tokenizes denser than English prose.
//
// A real tokenizer can be plugged in with setTokenizer() — the budget, the
// eviction order and the meter all read through estimateTokens(), so nothing
// else changes. None is wired today.

export const CHARS_PER_TOKEN = 3.5;

export type Tokenizer = (text: string) => number;

let activeTokenizer: Tokenizer | null = null;

/** Install a real tokenizer (or null to return to the estimate). A tokenizer
 *  that throws or returns nonsense falls back to the estimate per call — a
 *  broken plug-in must not be able to zero the budget. */
export function setTokenizer(tokenizer: Tokenizer | null): void {
  activeTokenizer = tokenizer;
}

export function estimateTokensByLength(text: string): number {
  if (!text) return 0;
  return Math.ceil(text.length / CHARS_PER_TOKEN);
}

export function estimateTokens(text: string): number {
  if (!text) return 0;
  if (activeTokenizer) {
    try {
      const n = activeTokenizer(text);
      if (typeof n === "number" && Number.isFinite(n) && n >= 0) return Math.ceil(n);
    } catch {
      // Fall through to the estimate.
    }
  }
  return estimateTokensByLength(text);
}

/**
 * Cut `text` to at most `maxTokens`, keeping the HEAD or the TAIL.
 *
 * Tail exists because compilers and test runners put the part that matters at
 * the end (same reasoning as clients/vscode workspaceTools' command output);
 * head is right for everything else. The marker is part of the result and is
 * counted, so the return value really is within the cap.
 */
export function truncateToTokens(text: string, maxTokens: number, keep: "head" | "tail" = "head"): string {
  if (!text) return "";
  if (maxTokens <= 0) return "";
  if (estimateTokens(text) <= maxTokens) return text;
  const marker = keep === "head" ? "\n… (cut to fit the model's context window)" : "… (earlier output cut to fit)\n";
  const markerTokens = estimateTokens(marker);
  const room = Math.max(0, maxTokens - markerTokens);
  if (room === 0) return "";
  // Length-proportional first guess, then shrink until the active estimator
  // (which may be a real tokenizer, not length-based) agrees.
  let chars = Math.min(text.length, Math.floor(room * CHARS_PER_TOKEN));
  for (let guard = 0; guard < 24 && chars > 0; guard += 1) {
    const piece = keep === "head" ? text.slice(0, chars) : text.slice(text.length - chars);
    if (estimateTokens(piece) <= room) {
      return keep === "head" ? piece + marker : marker + piece;
    }
    chars = Math.floor(chars * 0.85);
  }
  return "";
}
