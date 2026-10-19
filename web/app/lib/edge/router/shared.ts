// Contract types + shared constants for the tier router. Split out of
// ../tierRouter.ts, which re-exports every public name.

import type { Tier1ToolCallEvent } from "../tier1";
import { TIER0_ASSET_ID } from "../tier0";


/** The tier-0 "model id" for the EdgeActivity signal — tier0.ts's shared
 * asset id, the same value the chip/console compare inUse.modelId against
 * (the browser's built-in model has no WebLLM id). NOTE: the SERVER manifest
 * seeds its tier-0 entry as "chrome-builtin" (no second hyphen) — the UI
 * keys off this constant, not the manifest, so the asset id stays canonical. */
export const TIER0_ACTIVITY_MODEL_ID = TIER0_ASSET_ID;

export const DEFAULT_SESSION_KEY = "__default__";

export interface RouteTurnOptions {
  /** Conversation identity (e.g. the agent id) — each local tier keeps a
   * per-key session so multi-turn context survives across routeTurn calls.
   * Reset it with resetEdgeSession() when the caller starts a fresh
   * conversation. Tier 1 also uses it as the agentId/sessionId on edge tool
   * calls (toolBridge). */
  sessionKey?: string;
  /** System prompt for a NEWLY created local session. Ignored when a session
   * for sessionKey already exists (local sessions are append-only). */
  systemPrompt?: string;
  onToken?: (delta: string) => void;
  signal?: AbortSignal;
  /** Wave 2 (EDGE-B): the agent whose server policy/guardrails govern this
   * turn. Optional for compatibility — when omitted, sessionKey is used IF it
   * looks like an agent UUID (every current chat surface passes agent.id as
   * sessionKey); a non-UUID sessionKey (e.g. a helper's ad-hoc key) simply
   * gets no per-agent policy, the pre-Wave-2 behavior. */
  agentId?: string;
  /** Wave 2 (EDGE-C): complexity signals the CALLER has on hand — the router
   * always contributes messageLength itself from `input`. See
   * TurnComplexitySignal's docs in capabilities.ts. */
  toolsActive?: boolean;
  requestSmarterAnswer?: boolean;
  /** AG-UI-inspired visibility (2026-09-09): fires while a tier-1 tool round
   * is in flight — "calling" the moment the local model proposes a tool,
   * then "done"/"error" once the server-side execute resolves. Never fires
   * for tier 0 (no tool access) or a turn with no tool call. Purely additive
   * — see Tier1ToolCallEvent's doc comment in tier1.ts for the full
   * rationale (this is the same pattern LangGraph/CrewAI/Google ADK
   * standardized in 2026 as the AG-UI protocol). */
  onToolCall?: (event: Tier1ToolCallEvent) => void;
}

export type HostedFallthroughReason =
  | "edge-disabled" // flag off / server-side render
  | "user-hosted-override" // "Always use hosted" / hosted-only preference
  | "awaiting-user-choice" // "ask" mode (or explicit opt-out) — nothing routes locally until the person enables it
  | "no-local-tier" // device resolves straight to tier 3
  | "aborted" // caller's AbortSignal fired mid-turn
  | "all-tiers-failed" // every available local tier errored
  | "offline" // device has no network AND no local tier could serve either
  | "circuit-open" // a tier's circuit breaker is open from recent consecutive failures — skipped without attempting
  // --- Wave 2 additions (EDGE-A/B/C) ---
  | "policy-hosted" // server policy HOSTED_ONLY — never route locally for this agent
  | "complexity-hosted" // EDGE-C escalation: turn looks complex, no "capable"-tier model cached
  | "guardrails-hosted" // agent has guardrails the pattern tier can't represent — hosted governs the turn
  // D-9 (EDGE-CONTEXT-BUDGET-DESIGN.md): the irreducible prompt (system +
  // tools + current message) exceeds the on-device model's context budget —
  // detected BEFORE local inference (tier1's budgeter) or via the engine's
  // own ContextWindowSizeExceededError. Input-shaped, not model-health:
  // deliberately never trips the tier-1 circuit breaker, and skips tier 0
  // (whose window is smaller and opaque — a prompt too big for tier 1
  // cannot fit it).
  | "context-window-exceeded"
  // NOT a hosted fallthrough despite living in this vocabulary: the caller
  // must SURFACE this as an error ("this agent is configured to stay
  // on-device"), never re-send the turn hosted — see routeTurn's header.
  | "edge-only-refused";

export type RouteTurnResult =
  | {
      handled: true;
      tier: 0 | 1 | 2;
      text: string;
      /** Set when a HIGHER tier was attempted first and failed/was skipped
       * before this lower tier answered (e.g. the tier-1 "most capable"
       * model failed and tier 0's Chrome built-in model answered instead).
       * `tier` widened from `1` to `0 | 1 | 2` in Wave 2b, because tier 2
       * joined the ladder in BOTH directions: it can answer after tier 1 or
       * tier 0 failed, and tier 0 can answer after tier 2 failed (see the
       * ordering note in this file's header). Type-safe for existing
       * consumers — AgentPlayground.tsx and CallPanel.tsx both read only
       * `.detail`. CallPanel's own rendered copy for this case still says
       * "Chrome built-in" unconditionally, which is now only true when
       * `tier === 1`; that file is outside this wave's scope and the fix is
       * reported rather than silently left to read wrong.
       * Distinct from the full local->hosted fallback (that returns
       * handled:false) - this is a silent IN-TIER downgrade that previously
       * had no way to reach the UI at all: `tier` alone can't tell a caller
       * "this is what you expected" from "this is a downgrade you weren't
       * told about". Undefined when the tier that answered was the one
       * originally selected (the common case). */
      downgradedFrom?: { tier: 0 | 1 | 2; detail: string };
    }
  | {
      handled: false;
      reason: HostedFallthroughReason;
      detail?: string;
      /** Wave 2 (EDGE-A, finding E-2): a PII-scrubbed copy of the input that
       * callers SHOULD send to the hosted path instead of the raw text.
       * Present when EITHER the agent has guardrails enabled (the tenant
       * asked for content protection — full scrub, as since Wave 2) OR the
       * device's redaction preference masked something (E-3, default
       * "secrets": card numbers and government ids only, precisely so that
       * scrubbing "email me at x@y.com" for an agent with no PII posture
       * does NOT silently break legitimate hosted turns). Absent when
       * nothing was masked, and never present on "edge-only-refused"
       * (nothing may be sent hosted at all). Under redaction mode "model"
       * this value has additionally been through the on-device entity pass —
       * see redactor.ts. */
      scrubbedInput?: string;
    };

