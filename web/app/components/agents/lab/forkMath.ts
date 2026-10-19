// Agent Lab - decides whether a fork (rewind the server session to just
// before a user message, then send that message again) is safe. Pure.
//
// The rewind endpoint addresses STORED session entries by count from the
// head, and the browser never sees those entries - only its own transcript.
// So a fork is allowed only when the transcript can be mapped onto the store
// exactly: every exchange must be one whose stored-entry count is known, and
// the total must equal what the server reports. Anything else is refused;
// a rewind at a guessed index would silently cut the wrong messages.
import { listExchanges, type LabExchange } from "./labTurns";
import type { LabTurn } from "./types";

/**
 * How many entries an exchange left in the server session:
 *  - "clean":   exactly 2 (a completed server turn)
 *  - "local":   exactly 0 (answered on this device, never sent to the server)
 *  - "unknown": cannot be told from the transcript
 */
export type ExchangeKind = "clean" | "local" | "unknown";

export function classifyExchange(ex: LabExchange): ExchangeKind {
  const { user, assistant } = ex;
  if (!user || !assistant || assistant.pending) return "unknown";
  if (assistant.ref) {
    // run_finished fires only when the turn completes, which is when both
    // the user and the assistant entry are stored. A refused turn stores 0, a
    // turn that failed before its first token stores 1, and a cache hit
    // stores 2 but emits no events at all - none of them has the event, and
    // none of them can be told apart from here.
    const finished = (assistant.steps ?? []).some((step) => step.type === "run_finished" && step.origin !== "on-device");
    return finished && assistant.content !== "" ? "clean" : "unknown";
  }
  // A rescued turn reached the server first, which may have stored its half.
  return assistant.rescuedFromServer ? "unknown" : "local";
}

/** The store's trim limit (MAX_STORED_ENTRIES in SessionMemoryService). At
 * or past it the head has been trimmed, so head-relative indices shift. */
export const SERVER_MAX_STORED_ENTRIES = 400;

export type ForkPlan =
  | { ok: true; keepTurns: number; tailExchanges: number }
  | { ok: false; reason: "on-device" | "tail-unclean" | "prefix-unclean" | "mismatch"; server?: number; local?: number };

/** The part of a fork plan that needs no server answer. */
export type ForkPrecheck =
  | { ok: true; keepTurns: number; tailExchanges: number; expectedEntries: number }
  | { ok: false; reason: "on-device" | "tail-unclean" | "prefix-unclean" };

/**
 * Checks the transcript alone: can the exchange that starts at
 * `turns[userIndex]` be forked, and how many stored entries should the
 * server hold if it can? Lets the UI say "not available" before anyone types.
 */
export function precheckFork(turns: readonly LabTurn[], userIndex: number): ForkPrecheck {
  const exchanges = listExchanges(turns);
  const at = userIndex >= 0 ? exchanges.findIndex((ex) => ex.userIndex === userIndex) : -1;
  if (at < 0) return { ok: false, reason: "tail-unclean" };
  if (classifyExchange(exchanges[at]) === "local") return { ok: false, reason: "on-device" };
  // Everything that would be removed must be a completed server turn...
  const tail = exchanges.slice(at);
  if (tail.some((ex) => classifyExchange(ex) !== "clean")) return { ok: false, reason: "tail-unclean" };
  // ...and everything kept must have a known stored size (2 or 0).
  let cleanBefore = 0;
  for (const ex of exchanges.slice(0, at)) {
    const kind = classifyExchange(ex);
    if (kind === "unknown") return { ok: false, reason: "prefix-unclean" };
    if (kind === "clean") cleanBefore += 1;
  }
  return {
    ok: true,
    keepTurns: 2 * cleanBefore,
    tailExchanges: tail.length,
    expectedEntries: 2 * (cleanBefore + tail.length),
  };
}

/**
 * The full decision. `serverEntryCount` is the stored-entry count the server
 * just reported (labApi.probeSessionEntryCount). `keepTurns` is what to send
 * to the rewind endpoint; the transcript itself is cut at `userIndex`.
 */
export function planFork(turns: readonly LabTurn[], userIndex: number, serverEntryCount: number): ForkPlan {
  const pre = precheckFork(turns, userIndex);
  if (pre.ok === false) return pre;
  const local = pre.expectedEntries;
  // Voice calls, a tool approval's extra entry or an expired session all show
  // up here as a count the transcript cannot account for.
  if (serverEntryCount !== local || serverEntryCount <= 0 || serverEntryCount >= SERVER_MAX_STORED_ENTRIES) {
    return { ok: false, reason: "mismatch", server: serverEntryCount, local };
  }
  return { ok: true, keepTurns: pre.keepTurns, tailExchanges: pre.tailExchanges };
}
