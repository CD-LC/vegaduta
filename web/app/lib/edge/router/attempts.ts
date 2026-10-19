// One attempt per local tier (tier 0 / 1 / 2): typed failures in, TierAttempt
// out, never a raw throw. Split out of ../tierRouter.ts.

import {
  beginEdgeActivity,
  endEdgeActivity,
  type EdgeCapabilities,
  type TurnComplexitySignal,
} from "../capabilities";
import { createLeakHoldback, replyLeaksSystemPrompt } from "../promptLeakGuard";
import { createTier0Session, markTier0EngineStub, Tier0Error, type Tier0Session } from "../tier0";
import { createTier1Session, Tier1Error, withReasoningNudge, type Tier1Session } from "../tier1";
import { createTier2Session, Tier2Error } from "../tier2";
import { notifyEdgeRecovery } from "./circuit";
import {
  dropTier0Session,
  dropTier1Session,
  dropTier2Session,
  tier0Sessions,
  tier1Sessions,
  tier2Sessions,
} from "./sessions";
import { DEFAULT_SESSION_KEY, TIER0_ACTIVITY_MODEL_ID, type RouteTurnOptions } from "./shared";

export interface TierAttempt {
  ok: boolean;
  aborted: boolean;
  text: string;
  detail: string;
  /** D-9: the failure was tier1's typed "context-window-exceeded" — an
   * input-shaped limit the router must map to its own reason of the same
   * name, never count against the circuit breaker, and never retry on
   * tier 0 (see the reason's doc in HostedFallthroughReason). */
  contextExceeded?: boolean;
}

/**
 * Quality pass (2026-08-06): a bounded, honest self-correction attempt
 * before falling through to hosted — previously an empty/degenerate reply
 * fell straight through with zero retry, even though small local models are
 * stochastic enough that a second sampling pass alone often succeeds where
 * the first produced nothing. Only fires when the FIRST attempt came back
 * genuinely empty: by construction (see runTurn's/streamPrompt's
 * accumulation) an empty final string means nothing was ever flushed to
 * onToken, so retrying here can't duplicate or corrupt anything the caller
 * already rendered — a partial emission still fails through untouched,
 * exactly per this file's header contract.
 */
async function promptWithOneRetryOnEmpty(
  session: Tier0Session | Tier1Session,
  input: string,
  options: RouteTurnOptions,
  tier: 0 | 1
): Promise<string> {
  const first = await session.prompt(input, options.onToken, options.signal, options.onToolCall);
  if (first.trim() || options.signal?.aborted) return first;
  notifyEdgeRecovery({ recovering: true, tier });
  try {
    return await session.prompt(input, options.onToken, options.signal, options.onToolCall);
  } finally {
    notifyEdgeRecovery({ recovering: false, tier });
  }
}

/** Live incident (2026-08-10): tier 0 (Chrome's built-in Prompt API, see
 * tryTier0 below) never offers or executes tools — unlike tier 1's real
 * tool-calling loop (fetchEdgeTools/parseToolCall in tier1.ts), this tier
 * just sends raw text and gets raw text back, always. A small on-device
 * model asked something that would need a tool (read this file, search this
 * repo, browse this URL) has no way to know it lacks that capability and
 * confidently fabricates a plausible-sounding, wrong answer instead — a live
 * report showed a detailed, invented description of a specific source file
 * with 0 tool calls made. This is unconditional (not gated on this turn's
 * own toolsActive signal) because tier 0 NEVER has tool access regardless of
 * what a given turn asks for — resolveTier/EDGE-C's "best-effort beats
 * refusing under EDGE_ONLY" policy (tierRouter.ts's own comment above)
 * means tier 0 can still be this turn's only local option, so the honest
 * fix is telling it what it can't do, not blocking the turn. */
const TIER0_NO_TOOLS_DIRECTIVE =
  "\n\nYou are running on-device with no ability to browse the web, read files, run code, " +
  "search a codebase, or call any other tool right now — you can only use what you already " +
  "know plus what's in this conversation. If asked to inspect, analyze, or read a specific " +
  "file, repository, URL, or other live data you have not been given directly here, say " +
  "plainly that you cannot access it in this mode instead of guessing or inventing " +
  "plausible-sounding details.";

/** TierAttempt.detail for a reply discarded by the prompt-disclosure guard. */
export const SYSTEM_PROMPT_ECHO_DETAIL = "on-device reply repeated the system prompt";

export async function tryTier0(input: string, options: RouteTurnOptions): Promise<TierAttempt> {
  const key = options.sessionKey || DEFAULT_SESSION_KEY;
  try {
    const composedPrompt = withReasoningNudge(options.systemPrompt) + TIER0_NO_TOOLS_DIRECTIVE;
    let session = tier0Sessions.get(key);
    if (!session) {
      session = await createTier0Session(composedPrompt);
      tier0Sessions.set(key, session);
    }
    // Prompt-disclosure guard (prod pass 2026-10-05, bug 2): tokens reach the
    // caller through a holdback that withholds any run of the hidden system
    // prompt, so an engine that echoes its input never paints the agent's
    // instructions - see promptLeakGuard.ts.
    const holdback = createLeakHoldback(composedPrompt, options.onToken);
    const guardedOptions: RouteTurnOptions = { ...options, onToken: (delta) => holdback.push(delta) };
    try {
      // "Running on-device" indicator — tier 1 fires this inside its own
      // session (it knows the model id); tier 0 is fired here so tier0.ts
      // stays untouched.
      beginEdgeActivity(0, TIER0_ACTIVITY_MODEL_ID);
      let text: string;
      try {
        text = await promptWithOneRetryOnEmpty(session, input, guardedOptions, 0);
      } finally {
        endEdgeActivity();
      }
      // A reply that reproduces the system prompt is not an answer: discard
      // it, stop trusting this engine for the session, and let the router
      // fall through (tier 2 / the server model).
      if (holdback.leaked || replyLeaksSystemPrompt(text, composedPrompt)) {
        markTier0EngineStub();
        dropTier0Session(key);
        return { ok: false, aborted: false, text: "", detail: SYSTEM_PROMPT_ECHO_DETAIL };
      }
      holdback.flush();
      // An empty reply is a failure, not an answer — fall through rather
      // than render a blank assistant bubble.
      if (!text.trim()) {
        return { ok: false, aborted: false, text: "", detail: "empty tier-0 reply" };
      }
      return { ok: true, aborted: false, text, detail: "" };
    } catch (err) {
      // A failed turn may leave the append-only Prompt API session in a bad
      // state — drop it so the next turn starts clean.
      dropTier0Session(key);
      throw err;
    }
  } catch (err) {
    const aborted = err instanceof Tier0Error && err.code === "aborted";
    const detail =
      err instanceof Tier0Error ? err.detail || err.message : err instanceof Error ? err.message : String(err);
    return { ok: false, aborted, text: "", detail };
  }
}

/** Warm-up fix (2026-08-10 edge/WebLLM audit, finding #5): when
 * isTier1EngineWarm already shows the engine cold for this turn's model,
 * cap the inline acquire wait far below ENGINE_ACQUIRE_TIMEOUT_MS (60s) — a
 * genuinely stalled load then fails over to tier 0/hosted for just this
 * turn instead of blocking the user for a minute on a load that was never
 * warm to begin with. Background prewarmTier1Engine is untouched (it always
 * passes the full ceiling), so this only shortens the user-facing wait, not
 * the actual warm-up attempt racing in the background. */
const COLD_ENGINE_ACQUIRE_TIMEOUT_MS = 8_000;

export async function tryTier1(
  caps: EdgeCapabilities,
  input: string,
  options: RouteTurnOptions,
  modelWarm?: boolean,
  // EDGE-C completion (2026-09-12): the same TurnComplexitySignal routeTurn
  // built for this turn and already handed to getTier1RunModel above.
  // createTier1Session re-runs that selection to decide which model the
  // session actually binds to, and until now it ran with no signal at all —
  // so on a device with more than one cached model the model this router
  // reported could differ from the model that served the turn, and every
  // signal-dependent branch in selectBestModel was unreachable from the path
  // that matters. Only the FIRST turn of a session pays it: tier1Sessions is
  // keyed per conversation and a cached session keeps its bound model (the
  // deliberate "don't re-init a warm engine" contract, unchanged here).
  turnComplexity?: TurnComplexitySignal
): Promise<TierAttempt> {
  const key = options.sessionKey || DEFAULT_SESSION_KEY;
  try {
    let session = tier1Sessions.get(key);
    if (!session) {
      session = await createTier1Session(caps, {
        sessionKey: options.sessionKey,
        systemPrompt: options.systemPrompt,
        engineAcquireTimeoutMs: modelWarm === false ? COLD_ENGINE_ACQUIRE_TIMEOUT_MS : undefined,
        turnComplexity,
      });
      tier1Sessions.set(key, session);
    }
    try {
      const text = await promptWithOneRetryOnEmpty(session, input, options, 1);
      if (!text.trim()) {
        return { ok: false, aborted: false, text: "", detail: "empty tier-1 reply" };
      }
      // Same prompt-disclosure backstop as tier 0, on the finished reply.
      if (replyLeaksSystemPrompt(text, options.systemPrompt)) {
        dropTier1Session(key);
        return { ok: false, aborted: false, text: "", detail: SYSTEM_PROMPT_ECHO_DETAIL };
      }
      return { ok: true, aborted: false, text, detail: "" };
    } catch (err) {
      // A failed turn may leave the session history inconsistent with what
      // the user saw — drop it so the next turn starts clean.
      dropTier1Session(key);
      throw err;
    }
  } catch (err) {
    const aborted = err instanceof Tier1Error && err.code === "aborted";
    const contextExceeded = err instanceof Tier1Error && err.code === "context-window-exceeded";
    const detail =
      err instanceof Tier1Error ? err.detail || err.message : err instanceof Error ? err.message : String(err);
    return { ok: false, aborted, text: "", detail, contextExceeded };
  }
}

/**
 * Tier 2 (tier2.ts): a small instruct model on @huggingface/transformers'
 * WebAssembly backend, in a Web Worker. Same shape as tryTier0/tryTier1 —
 * typed failures in, TierAttempt out, never a raw throw.
 *
 * Two deliberate differences from the tiers above, both about CPU cost:
 *   1. NO retry-on-empty. promptWithOneRetryOnEmpty exists because a second
 *      sampling pass on a GPU/built-in model is cheap relative to the value
 *      of not falling through. On this tier a second pass costs roughly what
 *      the first one did, on the CPU, in front of a waiting person — so an
 *      empty reply falls through immediately instead. (The router treats an
 *      empty reply as a failure either way; this only decides whether a
 *      second generation is paid for first.) That is also why
 *      EdgeRecoveryEvent's `tier` stays 0 | 1.
 *   2. NO beginEdgeActivity/endEdgeActivity. capabilities.ts types that
 *      signal's `tier` as `0 | 1` and capabilities.ts is outside this wave's
 *      scope. Calling it with 1 would make the chip label a CPU turn as
 *      WebLLM, which is exactly the kind of small lie this codebase keeps
 *      paying for — so nothing is emitted, and the "running on-device"
 *      indicator stays dark during a tier-2 turn. That is a real UI gap on
 *      the slowest tier (where the indicator matters most); the one-line
 *      widening is in this wave's cross-file notes.
 *
 * Like tier 0, this tier has NO tool access at all — createTier2Session
 * appends its own no-tools directive (TIER2_NO_TOOLS_DIRECTIVE in tier2.ts)
 * for the same reason tryTier0 does: a small model with no way to know it
 * lacks a capability invents a plausible answer instead of saying so.
 */
export async function tryTier2(input: string, options: RouteTurnOptions): Promise<TierAttempt> {
  const key = options.sessionKey || DEFAULT_SESSION_KEY;
  try {
    let session = tier2Sessions.get(key);
    if (!session) {
      session = await createTier2Session({
        sessionKey: options.sessionKey,
        systemPrompt: withReasoningNudge(options.systemPrompt),
      });
      tier2Sessions.set(key, session);
    }
    try {
      const text = await session.prompt(input, options.onToken, options.signal);
      if (!text.trim()) {
        return { ok: false, aborted: false, text: "", detail: "empty tier-2 reply" };
      }
      // Same prompt-disclosure backstop as tier 0, on the finished reply.
      if (replyLeaksSystemPrompt(text, options.systemPrompt)) {
        dropTier2Session(key);
        return { ok: false, aborted: false, text: "", detail: SYSTEM_PROMPT_ECHO_DETAIL };
      }
      return { ok: true, aborted: false, text, detail: "" };
    } catch (err) {
      // A failed turn may leave the session history inconsistent with what
      // the user saw — drop it so the next turn starts clean.
      dropTier2Session(key);
      throw err;
    }
  } catch (err) {
    const aborted = err instanceof Tier2Error && err.code === "aborted";
    const detail =
      err instanceof Tier2Error ? err.detail || err.message : err instanceof Error ? err.message : String(err);
    return { ok: false, aborted, text: "", detail };
  }
}
