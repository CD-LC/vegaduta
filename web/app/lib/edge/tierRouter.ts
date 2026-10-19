// The single "which brain answers this turn" routing seam.
//
// Graceful-degradation contract (binding): a failure at any tier
// falls through to the next, ending at the EXISTING hosted pool. The router
// itself never touches the server for the answer — "hosted" is a
// `{ handled: false }` sentinel and the CALLER keeps its current server send
// path unchanged for it. routeTurn NEVER throws to the UI.
//
// Caller contract for streaming: onToken may already have delivered tokens by
// the time a tier fails. On any `{ handled: false }` result the caller MUST
// discard tokens it rendered from this call and run its normal hosted path —
// the AgentPlayground integration does exactly that (withdraws the optimistic
// turn, then re-sends through api.streamChat).
//
// Wave 2: tier 1 (WebLLM/WebGPU, tier1.ts) joins the ladder. resolveTier
// prefers tier 1 whenever a fitting model's weights are ALREADY downloaded
// (best-model rule); a tier-1 failure falls through to tier 0 — but only if
// the failed attempt emitted no tokens yet, because an emitted partial can't
// be un-rendered and mixing two tiers' streams would corrupt the transcript.
// A post-emission failure returns { handled: false } instead, and the
// caller's existing discard-and-go-hosted path covers it.
//
// Edge Wave 2 governance pass (EDGE-A/B/C, docs/rewriting-story/04-design/
// EDGE-AI-STRATEGY.md — closes finding F-25). Three things joined this seam:
//   1. SERVER POLICY (EDGE-B): a per-agent policy fetched from
//      GET /api/edge/config?agentId=... now outranks every localStorage
//      device preference — HOSTED_ONLY never routes locally, EDGE_ONLY never
//      falls through to hosted (a local failure returns the typed
//      "edge-only-refused" reason the caller must SURFACE, not re-send).
//      This precedence is the fix for the 2026-08-07 "BYOK selected but edge
//      still answers" bug class: a per-device flag can no longer outrank
//      what the tenant configured server-side.
//   2. GUARDRAILS (EDGE-A): agents with guardrails enabled get the
//      pattern-tier screen (edgeGuardrails.ts) on input BEFORE local
//      inference and on the completed reply before return; agents whose
//      guardrails the pattern tier can't represent route hosted (honest
//      degradation). Fallthrough returns carry a PII-scrubbed copy of the
//      input (`scrubbedInput`) callers SHOULD prefer over the raw text.
//   3. COMPLEXITY (EDGE-C): estimateTurnComplexity finally has callers —
//      the signal picks fast-vs-capable models, and a complex turn with no
//      "capable"-tier model cached escalates straight to hosted
//      ("complexity-hosted") instead of burning latency on a doomed 1B
//      attempt.
// KNOWN GAP, reported not hidden: AgentPlayground.tsx (frozen by another
// session's uncommitted work) doesn't yet special-case "edge-only-refused"
// or consume `scrubbedInput` — its fallthrough still re-sends the raw text
// hosted. One-line wiring each once that file thaws; ChatPanel/AskAiHelper/
// CallPanel are wired in this pass.
//
// Wave 2b: TIER 2 (tier2.ts — a small instruct model on @huggingface/
// transformers' WebAssembly backend, in a Web Worker) fills the extension
// point that used to be a bare comment here. It is the reach fix: a device
// with NEITHER WebGPU (tier 1) NOR Chrome's Prompt API (tier 0) previously
// resolved straight to tier 3 and every turn went to the hosted pool and its
// quota. resolveTier's own `tier2Ready` input — declared in capabilities.ts
// since Wave 2 and set by nothing until now — is what this wave finally
// supplies, so such a device can resolve to tier 2 instead of 3.
//
// ORDER, and the one place it is not the literal "1, 2, 0". The ladder is
// tier 1, then tier 2, then tier 0, then hosted — EXCEPT when tier 0 is the
// tier resolveTier actually selected for this device, in which case tier 0
// goes first and tier 2 sits behind it. Two rules are being honoured at once:
// the wave's tier-2-before-tier-0 ordering, and the older, binding rule that
// the router never skips past the tier resolveTier picked (capabilities.ts
// ranks an available Prompt API above tier 2; a start tier is a decision, not
// a suggestion). The practical effect: a WebGPU device whose tier-1 turn
// fails now tries the CPU model before the browser's built-in one, and a
// device where the browser's built-in model IS the selected tier keeps using
// it first and gains the CPU model as a new, additional fallback.
//
// Tier 2 is gated EXACTLY like tier 1 and for the same reason: isTier2Ready()
// is non-null/true only when every file a load needs is already in this
// device's cache (tier2.ts walks the real Cache API), so nothing on this path
// can start a download. Its cost profile is different from every other tier
// and the UI has to say so — a CPU model is much slower than a GPU one. No
// tokens/sec figure appears anywhere in this seam or in tier2.ts because
// nobody has measured one.

import {
  detectCapabilities,
  edgeEnabled,
  estimateTurnComplexity,
  getEdgeOptIn,
  getEdgeRedactionMode,
  getEdgeRoutingPreference,
  getStoredModelOverride,
  resolveTier,
  type TurnComplexitySignal,
} from "./capabilities";
import { fetchEdgeAgentPolicy, type EdgeAgentPolicyInfo } from "./agentPolicy";
import { prepareHostedFallthrough, screenInput, screenOutput } from "./edgeGuardrails";
import { modelRedactionReady, redactForHosted, redactPatterns } from "./redactor";
import { getTier1RunModel, isTier1EngineWarm } from "./tier1";
import { isTier2Ready } from "./tier2";
import { reportEdgeTurn } from "./telemetry";
import { isEdgeOffline } from "./offline";
import { getCircuitBreakerState, requestCircuitBreaker } from "../requestLifecycle";

import {
  EDGE_CIRCUIT_BREAKER_POLICY,
  EDGE_TIER0_CIRCUIT_TARGET,
  EDGE_TIER1_CIRCUIT_TARGET,
  EDGE_TIER2_CIRCUIT_TARGET,
  subscribeEdgeRecovery,
  type EdgeRecoveryEvent,
} from "./router/circuit";
import { tryTier0, tryTier1, tryTier2, SYSTEM_PROMPT_ECHO_DETAIL } from "./router/attempts";
import { resetEdgeSession } from "./router/sessions";
import { evaluateEdgeSession, type EdgeSessionEvaluation } from "./router/evaluation";
import {
  TIER0_ACTIVITY_MODEL_ID,
  type HostedFallthroughReason,
  type RouteTurnOptions,
  type RouteTurnResult,
} from "./router/shared";

// Public surface preserved: everything that used to be defined here is still
// importable from "./tierRouter". The implementation is split under ./router/.
export {
  EDGE_CIRCUIT_BREAKER_POLICY,
  EDGE_TIER0_CIRCUIT_TARGET,
  EDGE_TIER1_CIRCUIT_TARGET,
  EDGE_TIER2_CIRCUIT_TARGET,
  SYSTEM_PROMPT_ECHO_DETAIL,
  evaluateEdgeSession,
  resetEdgeSession,
  subscribeEdgeRecovery,
};
export type { EdgeRecoveryEvent, EdgeSessionEvaluation, HostedFallthroughReason, RouteTurnOptions, RouteTurnResult };

// ---------------------------------------------------------------------------
// The router
// ---------------------------------------------------------------------------

/** UUID-ish check for the "sessionKey doubles as the agent id" convention
 * every chat surface follows — see RouteTurnOptions.agentId. */
function isLikelyAgentId(value: string | undefined | null): value is string {
  return !!value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

/**
 * routeTurn's public entry point. The routing itself is routeTurnCore below,
 * unchanged; this wrapper owns ONE extra thing — E-3's on-device redaction
 * upgrade (redactor.ts).
 *
 * Why a wrapper instead of another line inside the core: the pattern-tier
 * scrub is synchronous and already rides core's `fallthrough` constructor,
 * but the model-assisted pass is async, and threading an await through
 * fifteen synchronous return sites would be a far bigger change to a seam
 * whose whole contract is "never throws, never blocks". Here it is one
 * bounded await on exactly the path that needs it: a turn that is about to
 * leave the device. Local answers (`handled: true`) never touch it and pay
 * nothing. Never throws — a failed upgrade returns the core result verbatim.
 */
export async function routeTurn(
  input: string,
  options: RouteTurnOptions = {}
): Promise<RouteTurnResult> {
  const result = await routeTurnCore(input, options);
  // Nothing to scrub: the turn was answered locally, or it's an EDGE_ONLY
  // refusal where nothing may be sent hosted at all. (`=== true` rather than
  // a truthiness check because this project compiles with strictNullChecks
  // off, where only the explicit-literal form narrows the union — same idiom
  // as tier1.ts's tool loop.)
  if (result.handled === true) return result;
  if (result.reason === "edge-only-refused") return result;
  if (!modelRedactionReady()) return result;
  try {
    // The base is whatever core already produced — the guardrail scrub for a
    // guardrails-enabled agent, else the pattern-tier scrub — so the model
    // pass strictly adds to it and can never un-mask something.
    const base = result.scrubbedInput ?? input;
    const upgraded = await redactForHosted(base, { signal: options.signal });
    if (upgraded.stage !== "model" || upgraded.text === base) return result;
    return { ...result, scrubbedInput: upgraded.text };
  } catch {
    return result;
  }
}

async function routeTurnCore(
  input: string,
  options: RouteTurnOptions = {}
): Promise<RouteTurnResult> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") {
      return { handled: false, reason: "edge-disabled" };
    }

    // ---- Wave 2 step 0 (EDGE-B): server policy BEFORE device preferences —
    // policy outranks preference by design (see header). A null policyInfo
    // (no agent id, anonymous caller, pre-Wave-2 server, fetch failure) is
    // the documented degrade-to-old-behavior state, never an error.
    const policyAgentId = options.agentId ?? (isLikelyAgentId(options.sessionKey) ? options.sessionKey : null);
    const policyInfo: EdgeAgentPolicyInfo | null = policyAgentId
      ? await fetchEdgeAgentPolicy(policyAgentId)
      : null;
    const policy = policyInfo?.policy ?? null;
    const edgeOnly = policy === "EDGE_ONLY";
    const guardrailsActive = policyInfo?.guardrailsEnabled === true;
    // Fallthrough constructor: every hosted-bound {handled:false} return goes
    // through here so the EDGE-A scrub (finding E-2 — verbatim re-send) is
    // attached uniformly for guardrail-enabled agents. edge-only refusals
    // deliberately DON'T use it (nothing may go hosted for those).
    //
    // E-3 (2026-09-11): the device's own redaction preference now joins this
    // constructor. Precedence is "whichever masks more": a guardrails-enabled
    // agent keeps the full EDGE-A scrub exactly as before, and an agent
    // WITHOUT guardrails now still gets the preference's pattern tier
    // (default "secrets" — card numbers and government ids). Mode "off"
    // reproduces the pre-E-3 behavior byte for byte. The model-assisted pass
    // is not here — it's async; see the routeTurn wrapper above.
    const redactionMode = getEdgeRedactionMode();
    const scrubForHosted = (): string | undefined => {
      if (guardrailsActive) {
        const scrubbed = prepareHostedFallthrough(input);
        // "contact"/"model" mask the same classes prepareHostedFallthrough
        // does, so the guardrail scrub already covers them; "secrets" alone
        // masks less and must not be allowed to narrow a tenant posture.
        return scrubbed;
      }
      if (redactionMode === "off") return undefined;
      const scrubbed = redactPatterns(input, redactionMode);
      return scrubbed === input ? undefined : scrubbed;
    };
    const fallthrough = (reason: HostedFallthroughReason, detail?: string): RouteTurnResult => {
      const scrubbedInput = scrubForHosted();
      return {
        handled: false,
        reason,
        ...(detail ? { detail } : {}),
        ...(scrubbedInput !== undefined ? { scrubbedInput } : {}),
      };
    };

    if (policy === "HOSTED_ONLY") {
      return fallthrough("policy-hosted");
    }
    // EDGE-A: guardrails the pattern tier can't fully represent (semantic /
    // unported rules) mean hosted must govern this agent's turns — honest
    // degradation per the strategy. EDGE_ONLY + such guardrails is a config
    // contradiction: refuse (surfaced to the person) rather than silently
    // violating either the privacy policy or the guardrail posture.
    if (guardrailsActive && policyInfo && !policyInfo.guardrailsPatternRepresentable) {
      if (edgeOnly) {
        return {
          handled: false,
          reason: "edge-only-refused",
          detail: "this agent's guardrails need the platform's full safety checks, but its policy forbids hosted processing",
        };
      }
      return fallthrough("guardrails-hosted");
    }

    if (!edgeOnly) {
      // "Always use hosted" is a USER decision and beats everything EXCEPT a
      // server EDGE_ONLY policy (the plan's user-selection-always-wins rule,
      // now explicitly subordinate to tenant policy — see header §1).
      if (getStoredModelOverride() === "hosted") {
        return fallthrough("user-hosted-override");
      }
      // Routing preference (edge-first is the DEFAULT): "hosted-only" never
      // routes locally; getEdgeOptIn() is the one gate for the rest — it
      // answers true by default under edge-first, false under "ask" until the
      // person enables on-device themselves, and an explicit stored choice
      // (either way) always wins and persists.
      if (getEdgeRoutingPreference() === "hosted-only") {
        return fallthrough("user-hosted-override");
      }
      if (!getEdgeOptIn()) {
        return fallthrough("awaiting-user-choice");
      }
    }
    // EDGE_ONLY skips the device-preference gates above on purpose: the
    // tenant's policy says turns must stay on-device, and honoring a stale
    // per-device "hosted" pin would send content to exactly the place the
    // policy forbids. routeTurn still never downloads anything (E-0b): if no
    // model is ready the turn is refused, not served hosted.

    // ---- Wave 2 (EDGE-C): the turn-complexity signal, finally wired. The
    // router owns messageLength; callers thread toolsActive /
    // requestSmarterAnswer when they have them (see RouteTurnOptions).
    const complexitySignal: TurnComplexitySignal = {
      messageLength: input.length,
      toolsActive: options.toolsActive,
      requestSmarterAnswer: options.requestSmarterAnswer,
    };
    const complexity = estimateTurnComplexity(complexitySignal);

    const caps = await detectCapabilities();
    // getTier1RunModel never rejects and never downloads — non-null only when
    // a fitting model's weights are already cached on this device. Captured
    // (not just its null-ness) so a successful tier-1 turn can report its
    // real model id to the quality-telemetry call below, without a second
    // lookup — getTier1RunModel is memoized per session either way.
    const t1Model = await getTier1RunModel(caps, complexitySignal);
    // Wave 2b: the tier2Ready input resolveTier has declared since Wave 2 and
    // nothing has ever set. Same contract as tier1Ready — true ONLY when
    // every file a tier-2 load needs is already cached on this device
    // (tier2.ts walks the real Cache API; the answer is memoized per session,
    // and short-circuits synchronously to false when nothing was ever
    // installed here). It is what lets a device with no WebGPU and no Prompt
    // API resolve to 2 instead of falling to 3/hosted.
    const t2Ready = await isTier2Ready();
    const startTier = resolveTier(caps, { tier1Ready: t1Model !== null, tier2Ready: t2Ready });
    // Finding #4/#5: known up front, before any attempt — feeds tryTier1's
    // cold-engine timeout shortcut below.
    const t1ModelWarm = t1Model !== null && isTier1EngineWarm(t1Model.id);
    if (startTier === 3) {
      if (edgeOnly) {
        return {
          handled: false,
          reason: "edge-only-refused",
          detail: isEdgeOffline()
            ? "device is offline and no on-device model is ready"
            : "no on-device model is ready on this device",
        };
      }
      // No local tier is capable on this device at all — if we're also
      // offline, "you're offline" is the more actionable reason to surface
      // than "no local tier" (which reads like a permanent device verdict).
      return fallthrough(isEdgeOffline() ? "offline" : "no-local-tier");
    }

    // ---- EDGE-C escalation ("too hard for edge"): a complex turn with no
    // "capable"-tier model to serve it goes straight to hosted instead of
    // burning latency on a doomed small-model attempt — the "1.5B attempts
    // every turn and fails into hosted" finding (E-5). Skipped when:
    //   - the user pinned a specific model (user selection always wins);
    //   - policy is EDGE_ONLY (a best-effort local answer beats refusing);
    //   - the manifest carries no speedTier tags at all is NOT special-cased:
    //     the server seed + built-in fallback both tag every tier-1 row as of
    //     this wave, and an admin override that strips ALL tags reads as
    //     "this deployment opted out of tiering", where biggest-fitting-wins
    //     selection (t1Model) minus this escalation is the legacy behavior —
    //     t1Model?.speedTier is undefined there, so complex turns route
    //     hosted, the conservative choice for an untagged catalog.
    const storedOverride = getStoredModelOverride();
    const userPinnedModel = storedOverride !== "auto" && storedOverride !== "hosted";
    if (complexity === "complex" && !edgeOnly && !userPinnedModel && t1Model?.speedTier !== "capable") {
      return fallthrough(
        "complexity-hosted",
        t1Model ? `best cached on-device model (${t1Model.id}) is not capable-tier` : "no capable-tier model cached"
      );
    }

    // ---- EDGE-A input screen: BEFORE any local inference. Blocked input is
    // answered with the guardrail's refusal via the normal handled:true shape
    // — the hosted path would block it the same way, so falling through
    // would only leak the content further before refusing it anyway.
    if (guardrailsActive) {
      const inputScreen = screenInput(input);
      if (inputScreen.blocked) {
        return {
          handled: true,
          tier: startTier === 1 ? 1 : 0,
          text: inputScreen.message || "This request was blocked by this agent's safety guardrails.",
        };
      }
    }

    // Quality pass (2026-08-06): report this turn's outcome once it's known
    // — see telemetry.ts's header for why (fire-and-forget, never on the
    // critical path). Only turns that actually attempt a local tier are
    // worth reporting; the early no-local-tier/opt-out returns above never
    // touched a model at all.
    // NOT widened to tier 2, and not by oversight: telemetry.ts's
    // EdgeTurnReport types `tier` as `0 | 1`, telemetry.ts is outside this
    // wave's scope, and passing 2 through a cast would put a value in the
    // wire payload that the server's own contract does not describe. Tier-2
    // turns therefore report nothing at all today — a real gap in the quality
    // telemetry, written up rather than papered over; the one-line widening
    // is in this wave's cross-file notes.
    const turnStarted = Date.now();
    const report = (tier: 0 | 1, modelId: string | null, outcome: "success" | "fallback" | "error", reason?: string) =>
      reportEdgeTurn({
        agentId: options.sessionKey || "",
        sessionKey: options.sessionKey,
        tier,
        modelId,
        outcome,
        reason,
        latencyMs: Date.now() - turnStarted,
      });

    // Track whether any tokens reached the caller — a lower-tier retry is
    // only clean while nothing has been rendered yet (see header comment).
    let emittedTokens = false;
    const forward = options.onToken;
    const attemptOptions: RouteTurnOptions = {
      ...options,
      onToken: forward
        ? (delta: string) => {
            emittedTokens = true;
            forward(delta);
          }
        : undefined,
    };

    let lastDetail = "";
    // Set when a tier's circuit breaker was open and the attempt was skipped
    // entirely (no real attempt made) — the final return branches on this so
    // "circuit-open" (actionable: a backend is failing repeatedly) doesn't
    // get reported as the vaguer "all-tiers-failed".
    let skippedForCircuitOpen = false;
    // Set whenever a HIGHER tier was attempted first and didn't end up
    // answering (failed, or skipped for an open circuit breaker) and control
    // fell through to a lower one — carried into that lower tier's success
    // return so the UI can tell "this is the tier you expected" apart from
    // "this silently downgraded from the tier you expected" (see
    // RouteTurnResult's doc). Wave 2b: no longer only tier 1, because tier 2
    // sits between 1 and 0 on most devices and behind 0 on a Prompt-API one.
    let downgrade: { tier: 0 | 1 | 2; detail: string } | null = null;

    // ---- Tier 2 stage (shared by both call sites below) -----------------
    // Returns a RouteTurnResult when tier 2 settled the turn (answered, or
    // the caller aborted mid-generation), or null to continue down the
    // ladder. Written once and called from two places because the stage's
    // POSITION depends on which tier resolveTier picked — see the ordering
    // note in this file's header — while its behaviour does not.
    const runTier2Stage = async (): Promise<RouteTurnResult | null> => {
      if (!t2Ready) return null;
      // A partial stream can't be un-rendered, and mixing two tiers' streams
      // would corrupt the transcript — the same rule the tier-1 → tier-0
      // fallthrough already follows. Hosted (which the caller reaches by
      // discarding what it rendered) is the correct next step instead.
      if (emittedTokens) return null;
      if (getCircuitBreakerState(EDGE_TIER2_CIRCUIT_TARGET).open) {
        skippedForCircuitOpen = true;
        lastDetail = lastDetail || "tier 2 circuit breaker open";
        return null;
      }
      const attempt = await tryTier2(input, attemptOptions);
      if (attempt.ok) {
        requestCircuitBreaker.recordSuccess(EDGE_TIER2_CIRCUIT_TARGET);
        // EDGE-A output screen — same buffered check as the other two tiers.
        if (guardrailsActive) {
          const outputScreen = screenOutput(attempt.text);
          if (outputScreen.blocked) {
            return {
              handled: true,
              tier: 2,
              text: guardrailOutputRefusal(outputScreen.message),
              ...(downgrade ? { downgradedFrom: downgrade } : {}),
            };
          }
        }
        return {
          handled: true,
          tier: 2,
          text: attempt.text,
          ...(downgrade ? { downgradedFrom: downgrade } : {}),
        };
      }
      if (attempt.aborted) return { handled: false, reason: "aborted", detail: attempt.detail };
      requestCircuitBreaker.recordFailure(EDGE_TIER2_CIRCUIT_TARGET);
      lastDetail = attempt.detail || lastDetail;
      downgrade = { tier: 2, detail: lastDetail };
      return null;
    };

    // Tier 2 runs BEFORE tier 0 unless tier 0 is the tier resolveTier
    // actually selected for this device (startTier === 0) — the router never
    // skips past its own start tier. See this file's header for the full
    // reasoning behind the one exception.
    const tier2BeforeTier0 = startTier !== 0;

    // ---- Tier 1: WebLLM (weights already on-device) ---------------------
    if (startTier === 1) {
      if (getCircuitBreakerState(EDGE_TIER1_CIRCUIT_TARGET).open) {
        report(1, t1Model?.id ?? null, "error", "circuit breaker open");
        skippedForCircuitOpen = true;
        lastDetail = "tier 1 circuit breaker open";
        downgrade = { tier: 1, detail: lastDetail };
        // Skip straight to the lower-tier fallbacks below — do NOT call
        // tryTier1.
      } else {
        const attempt = await tryTier1(caps, input, attemptOptions, t1ModelWarm, complexitySignal);
        if (attempt.ok) {
          requestCircuitBreaker.recordSuccess(EDGE_TIER1_CIRCUIT_TARGET);
          // EDGE-A output screen (buffered on the accumulated text — see
          // edgeGuardrails.ts's header for why mid-stream tokens may have
          // rendered already; callers replace rendered content with the
          // final `text`, which is what makes this refusal-swap work).
          if (guardrailsActive) {
            const outputScreen = screenOutput(attempt.text);
            if (outputScreen.blocked) {
              report(1, t1Model?.id ?? null, "error", "guardrail-output-blocked");
              return {
                handled: true,
                tier: 1,
                text: guardrailOutputRefusal(outputScreen.message),
              };
            }
          }
          report(1, t1Model?.id ?? null, "success");
          return { handled: true, tier: 1, text: attempt.text };
        }
        if (attempt.aborted) return { handled: false, reason: "aborted", detail: attempt.detail };
        // D-9: context overflow is input-shaped, not model-health — no
        // circuit-breaker failure, no tier-0 retry (its window is smaller
        // and opaque). EDGE_ONLY surfaces the explicit refusal, never a
        // silent hosted fallthrough (the design's binding rule).
        if (attempt.contextExceeded) {
          report(1, t1Model?.id ?? null, "fallback", "context-window-exceeded");
          if (edgeOnly) {
            return { handled: false, reason: "edge-only-refused", detail: attempt.detail };
          }
          return fallthrough("context-window-exceeded", attempt.detail);
        }
        requestCircuitBreaker.recordFailure(EDGE_TIER1_CIRCUIT_TARGET);
        lastDetail = attempt.detail;
        const tier0Capable =
          caps.promptApi.availability === "available" ||
          caps.promptApi.availability === "downloadable" ||
          caps.promptApi.availability === "downloading";
        // Wave 2b: tier 2 is a second possible landing place, so "no lower
        // tier exists" is no longer the same question as "no Prompt API".
        // Without this, a WebGPU-only device whose tier-1 turn failed would
        // still have gone straight to hosted even with a CPU model sitting
        // installed on the device.
        const lowerTierAvailable = tier0Capable || t2Ready;
        if (emittedTokens || !lowerTierAvailable) {
          report(1, t1Model?.id ?? null, "error", lastDetail);
          if (edgeOnly) {
            return { handled: false, reason: "edge-only-refused", detail: lastDetail };
          }
          return fallthrough("all-tiers-failed", lastDetail);
        }
        report(1, t1Model?.id ?? null, "fallback", lastDetail);
        downgrade = { tier: 1, detail: lastDetail };
        // Clean fall-through to tier 2 / tier 0 below.
      }
    }

    // ---- Tier 2: CPU (WebAssembly) model, when it outranks tier 0 -------
    if (tier2BeforeTier0) {
      const settled = await runTier2Stage();
      if (settled) return settled;
    }

    // ---- Tier 0: Chrome built-in model ---------------------------------
    if (startTier <= 1) {
      if (getCircuitBreakerState(EDGE_TIER0_CIRCUIT_TARGET).open) {
        report(0, TIER0_ACTIVITY_MODEL_ID, "error", "circuit breaker open");
        skippedForCircuitOpen = true;
        lastDetail = lastDetail || "tier 0 circuit breaker open";
        downgrade = downgrade || { tier: 0, detail: lastDetail };
        // Skip straight to the tier-2/final fallback below — do NOT call
        // tryTier0.
      } else {
        const attempt = await tryTier0(input, attemptOptions);
        if (attempt.ok) {
          requestCircuitBreaker.recordSuccess(EDGE_TIER0_CIRCUIT_TARGET);
          // EDGE-A output screen — same buffered check as the tier-1 branch.
          if (guardrailsActive) {
            const outputScreen = screenOutput(attempt.text);
            if (outputScreen.blocked) {
              report(0, TIER0_ACTIVITY_MODEL_ID, "error", "guardrail-output-blocked");
              return {
                handled: true,
                tier: 0,
                text: guardrailOutputRefusal(outputScreen.message),
                ...(downgrade ? { downgradedFrom: downgrade } : {}),
              };
            }
          }
          report(0, TIER0_ACTIVITY_MODEL_ID, "success");
          return {
            handled: true,
            tier: 0,
            text: attempt.text,
            ...(downgrade ? { downgradedFrom: downgrade } : {}),
          };
        }
        if (attempt.aborted) return { handled: false, reason: "aborted", detail: attempt.detail };
        requestCircuitBreaker.recordFailure(EDGE_TIER0_CIRCUIT_TARGET);
        lastDetail = attempt.detail || lastDetail;
        report(0, TIER0_ACTIVITY_MODEL_ID, "error", lastDetail);
        downgrade = { tier: 0, detail: lastDetail };
      }
    }

    // ---- Tier 2: CPU (WebAssembly) model, when tier 0 outranked it ------
    // The other half of the ordering rule: on a device where resolveTier
    // picked tier 0, the browser's built-in model goes first and the CPU
    // model is the fallback behind it. The two call sites are mutually
    // exclusive on the same boolean, so the stage runs at most once per turn.
    if (!tier2BeforeTier0) {
      const settled = await runTier2Stage();
      if (settled) return settled;
    }

    // EDGE_ONLY: no local tier answered — refuse, never fall through. The
    // caller surfaces "this agent is configured to stay on-device"
    // (edgeFallbackLabel has the copy); it must NOT re-send hosted.
    if (edgeOnly) {
      return { handled: false, reason: "edge-only-refused", detail: lastDetail };
    }
    if (skippedForCircuitOpen) {
      return fallthrough("circuit-open", lastDetail);
    }
    // Same offline-override rule as the startTier===3 early return above,
    // simplified: only report "offline" here when no local tier actually
    // produced a failure detail of its own (device/resource/ceiling
    // failures keep "all-tiers-failed" so `detail` carries the real cause).
    if (isEdgeOffline() && !lastDetail) {
      return fallthrough("offline");
    }
    return fallthrough("all-tiers-failed", lastDetail);
  } catch (err) {
    // Belt-and-braces: nothing above should throw, but the contract is
    // absolute — the UI never sees an exception from this seam. No scrub
    // here: guardrailsActive isn't in scope if the throw happened before the
    // policy fetch, and a raw re-send here matches the pre-Wave-2 behavior
    // this catch has always had.
    return {
      handled: false,
      reason: "all-tiers-failed",
      detail: err instanceof Error ? err.message : String(err),
    };
  }
}

/** The rendered-reply replacement for an output-screen block: the guardrail's
 * own message plus one honest line about what happened to the streamed text
 * (mid-stream tokens may have been visible before the buffered check — see
 * edgeGuardrails.ts's header). */
function guardrailOutputRefusal(message: string | undefined): string {
  return (
    (message || "The on-device reply was withheld by this agent's safety guardrails.") +
    " The on-device reply was replaced with this notice."
  );
}

